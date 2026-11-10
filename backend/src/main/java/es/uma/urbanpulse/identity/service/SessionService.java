package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.identity.dto.CreateSessionRequest;
import es.uma.urbanpulse.identity.dto.SessionResponse;
import es.uma.urbanpulse.identity.entity.RefreshToken;
import es.uma.urbanpulse.identity.entity.User;
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
    private final Clock clock;

    public SessionService(
            UserRepository users,
            RefreshTokenRepository refreshTokens,
            AccessTokenService accessTokens,
            CredentialCheck credentials,
            AccountSecurityProperties properties,
            Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.credentials = credentials;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Checks the credentials ({@link CredentialCheck}) and opens a session. An account that must
     * change its password gets no session; that is only said when the password is right, so a wrong
     * one does not reveal it.
     *
     * <p>Not transactional on purpose: bcrypt would hold a database connection. The read and the
     * insert of the new session have their own.
     */
    public SessionResponse signIn(CreateSessionRequest request, String clientAddress) {
        var email = EmailAddresses.normalize(request.email());
        var user = credentials.verify(email, request.password(), clientAddress);
        if (Boolean.TRUE.equals(user.getPasswordChangeRequired())) {
            throw new PasswordChangeRequiredException();
        }
        return openSession(user);
    }

    /**
     * Exchanges a refresh token for a new pair and revokes the one used. A disabled account loses
     * every session. So does presenting a token that is already revoked, by a refresh or a
     * sign-out, before it expires: two parties hold it and the server cannot tell which one is the
     * user (#125). Both revocations are kept although the request fails, and the answer is the same
     * {@code 401} as for an unknown token.
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
            refreshTokens.revokeAllActive(user.getId(), now);
            throw new InvalidRefreshTokenException();
        }
        if (token.getRevokedAt() != null) {
            log.warn(
                    "Revoked refresh token of session {} presented again: revoking every session"
                            + " of user {}",
                    token.getId(),
                    user.getId());
            refreshTokens.revokeAllActive(user.getId(), now);
            throw new InvalidRefreshTokenException();
        }
        token.setRevokedAt(now);
        return openSession(user);
    }

    /** Revokes the session of the access token. Idempotent: a revoked session stays revoked. */
    @Transactional
    public void signOut(UUID sessionId) {
        refreshTokens.revokeIfActive(sessionId, now());
    }

    /**
     * Revokes every refresh token of the user, for #40 when an administrator disables an account.
     * Access tokens already issued keep working until they expire (about 15 minutes, ADR-0004).
     */
    @Transactional
    public void revokeAllSessions(UUID userId) {
        refreshTokens.revokeAllActive(userId, now());
    }

    private SessionResponse openSession(User user) {
        var now = now();
        var plainToken = RefreshTokens.generate();
        var refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setTokenHash(RefreshTokens.hash(plainToken));
        refreshToken.setExpiresAt(now.plus(properties.refreshTokenTtl()));
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
