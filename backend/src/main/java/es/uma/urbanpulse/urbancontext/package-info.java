/**
 * Urban context module: external data about an incident and its surroundings (urban context,
 * external observations). Only the entities exist. Open Data Málaga and OpenStreetMap are the
 * accepted sources (ADR-0009); weather and traffic providers are proposed in ADR-0018, not decided.
 * Enrichment must never block the creation of an incident.
 *
 * <p>Decisions: ADR-0009 ({@code docs/adr/}).
 */
package es.uma.urbanpulse.urbancontext;
