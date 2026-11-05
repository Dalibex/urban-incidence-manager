package es.uma.urbanpulse.incidents.dto;

/**
 * Body of a lifecycle action: {@code IncidentActionRequest} and {@code IncidentReasonRequest} in
 * the contract, which differ only in whether the reason is required.
 *
 * <p>Nothing is checked with Bean Validation, which would run before the role check: the {@code
 * version} is checked by {@code IncidentLifecycleService} and the reason by the domain ({@code
 * Incident.changeStatus}), so invalid data answers {@code 422} only after the role, the access, the
 * version and the transition (ADR-0020, "Errors").
 *
 * @param version the {@code version} of the incident the client read; {@code null} gets a {@code
 *     422} on this field
 * @param reason reason, message to the citizen or resolution notes; {@code null} when not sent
 */
public record IncidentActionRequest(Integer version, String reason) {}
