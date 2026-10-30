package es.uma.urbanpulse.shared.security;

import es.uma.urbanpulse.identity.entity.Role;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * The user who sent the request, taken from a valid access token. Controllers receive it with
 * {@code @AuthenticationPrincipal AuthenticatedUser user}, so business code never reads the JWT.
 *
 * <p>The role comes from the token, so a role change takes effect when the token expires (about 15
 * minutes, ADR-0004). Disabling an account revokes its refresh tokens, which bounds the exposure to
 * that same window.
 *
 * <p>Serializable because Spring Security's {@code Authentication}, which holds it, is.
 *
 * @param id user id ({@code sub})
 * @param role the user's role ({@code role})
 * @param sessionId id of the refresh token issued with the access token ({@code sid})
 */
public record AuthenticatedUser(UUID id, Role role, UUID sessionId) implements Serializable {

    public AuthenticatedUser {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(sessionId, "sessionId");
    }
}
