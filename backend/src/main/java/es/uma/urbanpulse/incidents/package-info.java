/**
 * Incidents module: the incident as the central entity. Reporting, location, categories, priority,
 * lifecycle with allowed transitions, audited status history, assignments, comments and evidence
 * metadata. Evidence binaries are stored through {@code StorageService} (ADR-0013).
 *
 * <p>Decisions: ADR-0010, ADR-0013 ({@code docs/adr/}).
 */
package es.uma.urbanpulse.incidents;
