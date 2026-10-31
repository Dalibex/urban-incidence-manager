/**
 * JPA entities for the incidents module.
 *
 * <p>Following the MVC architecture and traditional CRUD model, entities are mapped directly to the
 * database schema without Aggregate Roots. The exception is the lifecycle (ADR-0020): {@link
 * es.uma.urbanpulse.incidents.entity.Incident} is the only class that changes the status of an
 * incident and creates its {@link es.uma.urbanpulse.incidents.entity.StatusChange} and {@link
 * es.uma.urbanpulse.incidents.entity.Assignment} entries, after checking {@link
 * es.uma.urbanpulse.incidents.entity.IncidentTransition}. Their constructors are package-private,
 * so no other code can write history without that check (ADR-0010).
 *
 * <p>Decisions: ADR-0010, ADR-0020 ({@code docs/adr/}).
 */
package es.uma.urbanpulse.incidents.entity;
