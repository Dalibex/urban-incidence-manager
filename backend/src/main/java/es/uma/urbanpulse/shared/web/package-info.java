/**
 * HTTP concerns shared by every module: the RFC 9457 Problem Details error responses, the base
 * exception that carries a stable error {@code code} ({@code docs/api.md}), and the page of a
 * listing ({@link es.uma.urbanpulse.shared.web.PageRequests}, {@link
 * es.uma.urbanpulse.shared.web.PageResponse}). A module adds its errors by extending {@link
 * es.uma.urbanpulse.shared.web.ApiProblemException}, not with its own advice.
 *
 * <p>Decisions: ADR-0008, ADR-0010 ({@code docs/adr/}).
 */
package es.uma.urbanpulse.shared.web;
