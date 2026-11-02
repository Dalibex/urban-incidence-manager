/**
 * HTTP concerns shared by every module: the RFC 9457 Problem Details error responses, the base
 * exception that carries a stable error {@code code} ({@code docs/api.md}), the id of each request
 * ({@link es.uma.urbanpulse.shared.web.RequestIdFilter}) and the page of a listing ({@link
 * es.uma.urbanpulse.shared.web.PageRequests}, {@link es.uma.urbanpulse.shared.web.PageResponse}). A
 * module adds its errors by extending {@link es.uma.urbanpulse.shared.web.ApiProblemException} from
 * its services, or the domain errors of {@code shared.error} from its entities, never with its own
 * advice.
 *
 * <p>Decisions: ADR-0008, ADR-0010 ({@code docs/adr/}).
 */
package es.uma.urbanpulse.shared.web;
