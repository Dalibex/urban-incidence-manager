package es.uma.urbanpulse.shared.security;

import es.uma.urbanpulse.identity.entity.Role;
import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * Turns a validated access token into an {@link AuthenticatedUserToken}. The role becomes the
 * authority {@code ROLE_<role>}, which is what {@code hasRole('ADMINISTRATOR')} checks.
 *
 * <p>A token with a well-formed signature but unusable claims (a role that does not exist, an id
 * that is not a UUID) is rejected as an invalid token: {@code 401}, never {@code 500}.
 */
final class AuthenticatedUserConverter implements Converter<Jwt, AuthenticatedUserToken> {

    private static final String AUTHORITY_PREFIX = "ROLE_";

    @Override
    public AuthenticatedUserToken convert(Jwt jwt) {
        var user =
                new AuthenticatedUser(
                        uuidClaim(jwt.getSubject(), "sub"),
                        roleClaim(jwt.getClaimAsString(JwtClaimNames.ROLE)),
                        uuidClaim(
                                jwt.getClaimAsString(JwtClaimNames.SESSION_ID),
                                JwtClaimNames.SESSION_ID));
        var authority = new SimpleGrantedAuthority(AUTHORITY_PREFIX + user.role().name());
        return new AuthenticatedUserToken(user, jwt, List.of(authority));
    }

    private static UUID uuidClaim(String value, String claim) {
        if (value == null) {
            throw invalidClaim(claim);
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw invalidClaim(claim);
        }
    }

    private static Role roleClaim(String value) {
        if (value == null) {
            throw invalidClaim(JwtClaimNames.ROLE);
        }
        try {
            return Role.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw invalidClaim(JwtClaimNames.ROLE);
        }
    }

    private static InvalidBearerTokenException invalidClaim(String claim) {
        return new InvalidBearerTokenException("Missing or invalid claim: " + claim);
    }
}
