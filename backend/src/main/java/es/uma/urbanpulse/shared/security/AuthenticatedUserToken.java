package es.uma.urbanpulse.shared.security;

import java.util.Collection;
import java.util.Objects;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** Authentication built from a valid access token, with an {@link AuthenticatedUser} principal. */
final class AuthenticatedUserToken extends AbstractAuthenticationToken {

    private final AuthenticatedUser user;
    private final Jwt jwt;

    AuthenticatedUserToken(
            AuthenticatedUser user, Jwt jwt, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.user = user;
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override
    public AuthenticatedUser getPrincipal() {
        return user;
    }

    @Override
    public Jwt getCredentials() {
        return jwt;
    }

    @Override
    public String getName() {
        return user.id().toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AuthenticatedUserToken token
                && super.equals(token)
                && user.equals(token.user)
                && jwt.getTokenValue().equals(token.jwt.getTokenValue());
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), user, jwt.getTokenValue());
    }
}
