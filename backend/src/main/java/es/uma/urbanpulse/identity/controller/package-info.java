/**
 * Adapters of the identity module: the HTTP ones for sign-up, sessions and the current user, as
 * described in {@code docs/api/openapi.yml}, and the one-off process that creates the first
 * administrator. They translate requests and their results; the rules live in {@code
 * identity.service}.
 *
 * <p>Decisions: ADR-0004, ADR-0008, ADR-0010 ({@code docs/adr/}).
 */
package es.uma.urbanpulse.identity.controller;
