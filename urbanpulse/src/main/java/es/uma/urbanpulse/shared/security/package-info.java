/**
 * Spring Security configuration: the filter chain applied to every request (ADR-0004).
 *
 * <p>Currently {@code /actuator/health/**} is open, because the CI smoke test calls it, and every
 * other endpoint needs HTTP Basic. When the JWT filter is implemented it replaces HTTP Basic here,
 * and the health endpoint stays open. Users, roles, login and tokens stay in {@code identity}.
 *
 * <p>Decisions: ADR-0004, ADR-0010 ({@code docs/adr/}).
 */
package es.uma.urbanpulse.shared.security;
