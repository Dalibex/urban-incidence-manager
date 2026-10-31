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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sign-in, refresh and sign-out (ADR-0004): a session is a refresh token row, and the access token
 * carries its id as {@code sid}.
 */
@Service
public class SessionService {

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final AccessTokenService accessTokens;
    private final PasswordEncoder passwordEncoder;
    private final AccountSecurityProperties properties;
    private final Clock clock;
    private final AttemptLimiter signInLimiter;

    // Compared against when the email has no account, so a wrong email takes as long as a wrong
    // password and the response time does not reveal which emails exist.
    private final String timingHash;

    public SessionService(
            UserRepository users,
            RefreshTokenRepository refreshTokens,
            AccessTokenService accessTokens,
            PasswordEncoder passwordEncoder,
            AccountSecurityProperties properties,
            Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
        this.signInLimiter = new AttemptLimiter(properties.signInAttempts(), clock);
        this.timingHash = passwordEncoder.encode(RefreshTokens.generate());
    }

    /**
     * Checks the credentials and opens a session. Attempts count per email and client address and a
     * right password gives them back, so only failures add up; once the limit is reached even the
     * right password waits for the window to end.
     *
     * <p>Not transactional on purpose: bcrypt takes about 100 ms and would hold a database
     * connection all that time. The read and the insert of the new session have their own.
     */
    public SessionResponse signIn(CreateSessionRequest request, String clientAddress) {
        var email = EmailAddresses.normalize(request.email());
        var limiterKey = email + "|" + clientAddress;
        signInLimiter.acquire(limiterKey);

        var user = users.findByEmail(email);
        var hash = user.map(User::getPasswordHash).orElse(timingHash);
        if (!passwordEncoder.matches(request.password(), hash) || user.isEmpty()) {
            throw new InvalidCredentialsException();
        }
        signInLimiter.reset(limiterKey);
        if (!Boolean.TRUE.equals(user.get().getEnabled())) {
            throw new AccountDisabledException();
        }
        return openSession(user.get());
    }

    /**
     * Exchanges a refresh token for a new pair and revokes the one used. A disabled account loses
     * every session; that revocation is kept although the request fails.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public SessionResponse refresh(String presentedToken) {
        var token =
                refreshTokens
                        .findByTokenHash(RefreshTokens.hash(presentedToken))
                        .orElseThrow(InvalidRefreshTokenException::new);
        var now = now();
        if (!token.getExpiresAt().isAfter(now)) {
            throw new InvalidRefreshTokenException();
        }
        var user = token.getUser();
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            refreshTokens.revokeAllActive(user.getId(), now);
            throw new InvalidRefreshTokenException();
        }
        if (refreshTokens.revokeIfActive(token.getId(), now) == 0) {
            throw new InvalidRefreshTokenException();
        }
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
