/**
 * Spring Security configuration: the filter chain applied to every request (ADR-0004).
 *
 * <p>{@code /actuator/health/**} (the CI smoke test calls it), the API documentation (contract,
 * Swagger UI and {@code /v3/api-docs}) and the sign-up, sign-in and refresh operations are open;
 * every other endpoint needs a valid JWT access token. This package validates the tokens and
 * exposes the caller as an {@link es.uma.urbanpulse.shared.security.AuthenticatedUser}; users,
 * roles, sign-in and the issuing of tokens stay in {@code identity}.
 *
 * <p>Decisions: ADR-0004, ADR-0010 ({@code docs/adr/}).
 */
package es.uma.urbanpulse.shared.security;
