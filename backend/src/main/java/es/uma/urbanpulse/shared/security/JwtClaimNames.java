package es.uma.urbanpulse.shared.security;

/**
 * Type, issuer and claims of an UrbanPulse access token. The standard claims {@code sub} (user id),
 * {@code iat} and {@code exp} are also present. No personal data goes into the token: it is only
 * signed, not encrypted, so anyone holding it can read it.
 */
public final class JwtClaimNames {

    /** {@code typ} header of an access token (RFC 9068). */
    public static final String ACCESS_TOKEN_TYPE = "at+jwt";

    public static final String ISSUER = "urbanpulse";

    /** The user's {@link es.uma.urbanpulse.identity.entity.Role}, by name. */
    public static final String ROLE = "role";

    /** Id of the refresh token issued with this access token, so sign-out can revoke it. */
    public static final String SESSION_ID = "sid";

    private JwtClaimNames() {}
}
