/**
 * Response bodies of the incident operations, separate from the JPA entities. Each response with
 * staff-only properties has a {@code Staff} and a {@code Citizen} type, so a citizen never receives
 * them, not even as {@code null} (docs/api.md, "Absent and null").
 *
 * <p>Decisions: ADR-0008, ADR-0010, ADR-0020 ({@code docs/adr/}).
 */
package es.uma.urbanpulse.incidents.dto;
