package es.uma.urbanpulse.notifications.service;

import es.uma.urbanpulse.identity.repository.RefreshTokenRepository;
import es.uma.urbanpulse.identity.repository.UserRepository;
import es.uma.urbanpulse.notifications.repository.PushTokenRepository;
import es.uma.urbanpulse.shared.security.AuthenticatedUser;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Expo push token of the citizen's phone (#373, ADR-0031): {@code createPushToken} and {@code
 * createPushTokenDeletion}. Only citizens may call them; the security filters refuse the other
 * roles ({@code ApiAuthorizationRules}). Ending every session of a user deletes its tokens ({@code
 * SessionService.endEverySession}).
 */
@Service
@Transactional
public class PushTokenService {

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PushTokenRepository pushTokens;
    private final Clock clock;

    public PushTokenService(
            UserRepository users,
            RefreshTokenRepository refreshTokens,
            PushTokenRepository pushTokens,
            Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.pushTokens = pushTokens;
        this.clock = clock;
    }

    /**
     * Registers the token for the citizen, moving it from another user if needed. Unlike other
     * operations, it checks that the session of the access token is still active, so an access
     * token issued before every session ended cannot bring back a deleted token.
     *
     * <p>The user is locked first and the session is read {@code FOR SHARE}, both until the commit:
     * the end of every session, which locks the same user, and a sign-out, which updates the
     * session row, wait for the registration, and the end of every session then deletes its token.
     *
     * @throws InsufficientAuthenticationException if the user does not exist or the session is
     *     missing, revoked or expired; nothing is stored then
     */
    public void register(AuthenticatedUser citizen, String token) {
        users.findLockedById(citizen.id())
                .orElseThrow(
                        () -> new InsufficientAuthenticationException("The user does not exist"));
        var now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        var active =
                refreshTokens
                        .findShareLockedById(citizen.sessionId())
                        .filter(
                                session ->
                                        session.getRevokedAt() == null
                                                && session.getExpiresAt().isAfter(now))
                        .isPresent();
        if (!active) {
            throw new InsufficientAuthenticationException("The session has ended");
        }
        pushTokens.register(citizen.id(), token, now);
    }

    /** Deletes the token by its value, whoever holds it; an unknown token changes nothing. */
    public void delete(String token) {
        pushTokens.deleteByToken(token);
    }
}
