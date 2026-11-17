/**
 * Urban context module: external data about an incident and its surroundings (urban context,
 * external observations). Each new incident gets a {@code PENDING} context, and a worker fills its
 * weather from Open-Meteo after the report. Open Data Málaga and OpenStreetMap are the accepted
 * sources (ADR-0009), Open-Meteo gives the weather (ADR-0018), and the traffic provider is proposed
 * in ADR-0024. Enrichment must never block the creation of an incident.
 *
 * <p>Decisions: ADR-0009, ADR-0018 ({@code docs/adr/}).
 */
package es.uma.urbanpulse.urbancontext;
