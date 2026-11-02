/**
 * Identity module: users, roles and permissions; sign-up, login, short-lived JWTs and rotating
 * hashed refresh tokens; audit log. The Spring Security configuration applied to every request is
 * in {@code shared.security}.
 *
 * <p>Decisions: ADR-0004, ADR-0010 ({@code docs/adr/}).
 */
package es.uma.urbanpulse.identity;
