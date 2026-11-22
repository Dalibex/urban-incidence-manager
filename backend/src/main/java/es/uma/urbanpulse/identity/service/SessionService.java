package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.identity.dto.CreateSessionRequest;
import es.uma.urbanpulse.identity.dto.MfaChallengeResponse;
import es.uma.urbanpulse.identity.dto.SessionResponse;
import es.uma.urbanpulse.identity.dto.SignInResponse;
import es.uma.urbanpulse.identity.entity.MfaChallenge;
import es.uma.urbanpulse.identity.entity.RefreshToken;
import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.identity.repository.MfaChallengeRepository;
import es.uma.urbanpulse.identity.repository.RefreshTokenRepository;
import es.uma.urbanpulse.identity.repository.UserRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sign-in, refresh and sign-out (ADR-0004): a session is a refresh token row, and the access token
 * carries its id as {@code sid}.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final AccessTokenService accessTokens;
    private final CredentialCheck credentials;
    private final AccountSecurityProperties properties;
    private final MfaChallengeRepository challenges;
    private final MfaProperties mfa;
    private final Clock clock;

    public SessionService(
            UserRepository users,
            RefreshTokenRepository refreshTokens,
            AccessTokenService accessTokens,
            CredentialCheck credentials,
            AccountSecurityProperties properties,
            MfaChallengeRepository challenges,
            MfaProperties mfa,
            Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.credentials = credentials;
        this.properties = properties;
        this.challenges = challenges;
        this.mfa = mfa;
        this.clock = clock;
    }

    /**
     * Checks the credentials ({@link CredentialCheck}) and opens a session. An account that must
     * change its password gets no session; that is only said when the password is right, so a wrong
     * one does not reveal it.
     *
     * <p>A role in {@code MFA_REQUIRED_ROLES} gets no session either, but a challenge of the second
     * factor (#57, ADR-0021): {@link MfaService} opens the session once a valid code is sent for
     * it.
     *
     * <p>Not transactional on purpose: bcrypt would hold a database connection. The read and the
     * insert of the new session or challenge have their own.
     */
    public SignInResponse signIn(CreateSessionRequest request, String clientAddress) {
        var email = EmailAddresses.normalize(request.email());
        var user = credentials.verify(email, request.password(), clientAddress);
        if (Boolean.TRUE.equals(user.getPasswordChangeRequired())) {
            throw new PasswordChangeRequiredException();
        }
        if (mfa.requiredFor(user.getRole())) {
            return createChallenge(user);
        }
        return openSession(user, false);
    }

    /**
     * Exchanges a refresh token for a new pair and revokes the one used. A disabled account loses
     * every session. So does presenting a token that is already revoked, by a refresh or a
     * sign-out, before it expires: two parties hold it and the server cannot tell which one is the
     * user (#125). Both revocations are kept although the request fails, and the answer is the same
     * {@code 401} as for an unknown token.
     *
     * <p>A role in {@code MFA_REQUIRED_ROLES} also loses every session when the token was not
     * opened with a code of the second factor (#57, ADR-0021), so no refresh issues it tokens
     * without one.
     *
     * <p>Revoking every session also makes those tokens expire ({@link
     * RefreshTokenRepository#endAll}), so a token revoked by a password change or a disabled
     * account is only refused, and never closes a session opened afterwards (#275).
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public SessionResponse refresh(String presentedToken) {
        var tokenHash = RefreshTokens.hash(presentedToken);
        var userId =
                refreshTokens
                        .findUserIdByTokenHash(tokenHash)
                        .orElseThrow(InvalidRefreshTokenException::new);
        // The refreshes of one user run one after another. Otherwise revoking every session could
        // miss the one that a refresh with another token is opening at that moment.
        var user = users.findLockedById(userId).orElseThrow(InvalidRefreshTokenException::new);
        var token =
                refreshTokens
                        .findLockedByTokenHash(tokenHash)
                        .orElseThrow(InvalidRefreshTokenException::new);
        var now = now();
        if (!token.getExpiresAt().isAfter(now)) {
            throw new InvalidRefreshTokenException();
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            refreshTokens.endAll(user.getId(), now);
            throw new InvalidRefreshTokenException();
        }
        if (token.getRevokedAt() != null) {
            log.warn(
                    "Revoked refresh token of session {} presented again: revoking every session"
                            + " of user {}",
                    token.getId(),
                    user.getId());
            refreshTokens.endAll(user.getId(), now);
            throw new InvalidRefreshTokenException();
        }
        var secondFactor = Boolean.TRUE.equals(token.getSecondFactor());
        if (mfa.requiredFor(user.getRole()) && !secondFactor) {
            // Opened before #57 or before a change of role or of MFA_REQUIRED_ROLES: a sign-in
            // with a code is the only way back (ADR-0021).
            refreshTokens.endAll(user.getId(), now);
            throw new InvalidRefreshTokenException();
        }
        token.setRevokedAt(now);
        return openSession(user, secondFactor);
    }

    /** Revokes the session of the access token. Idempotent: a revoked session stays revoked. */
    @Transactional
    public void signOut(UUID sessionId) {
        refreshTokens.revokeIfActive(sessionId, now());
    }

    /**
     * Revokes every refresh token of the user and makes it expire, for #40 when an administrator
     * disables an account. Access tokens already issued keep working until they expire (about 15
     * minutes, ADR-0004).
     */
    @Transactional
    public void revokeAllSessions(UUID userId) {
        refreshTokens.endAll(userId, now());
    }

    /** Only the hash of the token is stored, like a refresh token. */
    private MfaChallengeResponse createChallenge(User user) {
        var now = now();
        var plainToken = RefreshTokens.generate();
        var challenge = new MfaChallenge();
        challenge.setUser(user);
        challenge.setTokenHash(RefreshTokens.hash(plainToken));
        challenge.setEnrollment(user.getMfaSecret() == null);
        challenge.setCreatedAt(now);
        challenge.setExpiresAt(now.plus(mfa.challengeTtl()));
        var saved = challenges.save(challenge);
        return new MfaChallengeResponse(
                plainToken, saved.getExpiresAt().toInstant(), saved.getEnrollment());
    }

    /**
     * Issues a token pair; {@link MfaService} calls it inside the transaction of a valid code.
     * {@code secondFactor} marks a session opened with a code, and a refresh passes on the mark of
     * the token it replaces.
     */
    SessionResponse openSession(User user, boolean secondFactor) {
        var now = now();
        var plainToken = RefreshTokens.generate();
        var refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setTokenHash(RefreshTokens.hash(plainToken));
        refreshToken.setExpiresAt(now.plus(properties.refreshTokenTtl()));
        refreshToken.setSecondFactor(secondFactor);
        var saved = refreshTokens.save(refreshToken);

        var accessToken = accessTokens.issue(user.getId(), user.getRole(), saved.getId());
        return new SessionResponse(
                accessToken.value(),
                accessToken.expiresAt(),
                plainToken,
                saved.getExpiresAt().toInstant());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
