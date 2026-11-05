package es.uma.urbanpulse.incidents.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Body of a lifecycle action: {@code IncidentActionRequest} and {@code IncidentReasonRequest} in
 * the contract, which differ only in whether the reason is required.
 *
 * <p>The reason has no constraint here: the domain checks it ({@code Incident.changeStatus}), so a
 * missing or invalid reason answers {@code 422} only after the role, the access, the version and
 * the transition (ADR-0020, "Errors").
 *
 * @param version the {@code version} of the incident the client read
 * @param reason reason, message to the citizen or resolution notes; {@code null} when not sent
 */
public record IncidentActionRequest(@NotNull @PositiveOrZero Integer version, String reason) {}
