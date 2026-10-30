package es.uma.urbanpulse.shared.security;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.service.AccessTokenService;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;

/**
 * Real access tokens for integration tests, signed with the test secret, so requests go through the
 * same validation as in production. Import it with {@code @Import(TestTokens.class)}:
 *
 * <pre>{@code
 * mvc.perform(get("/api/v1/incidents").header(AUTHORIZATION, tokens.bearer(Role.CITIZEN)))
 * }</pre>
 *
 * <p>The user in the token does not need to exist in the database. When the endpoint reads the user
 * (owner of an incident, assignee...), create it first and pass its id.
 */
@TestComponent
public class TestTokens {

    private final AccessTokenService accessTokens;

    TestTokens(AccessTokenService accessTokens) {
        this.accessTokens = accessTokens;
    }

    /** {@code Authorization} header value for a new random user with this role. */
    public String bearer(Role role) {
        return bearer(UUID.randomUUID(), role);
    }

    /** {@code Authorization} header value for an existing user. */
    public String bearer(UUID userId, Role role) {
        return "Bearer " + accessTokens.issue(userId, role, UUID.randomUUID()).value();
    }
}
