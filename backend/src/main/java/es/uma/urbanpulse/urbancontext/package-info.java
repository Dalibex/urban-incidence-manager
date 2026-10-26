/**
 * Urban context module: external data. Adapters for Open Data Málaga and OpenStreetMap (ADR-0009),
 * Open-Meteo and TomTom (ADR-0018); loading of reference data, geocoding and asynchronous
 * enrichment of incidents with timeouts and graceful degradation. External calls are cached in
 * Redis (ADR-0012) and never block the creation of an incident.
 *
 * <p>Decisions: ADR-0009, ADR-0018 ({@code docs/adr/}).
 */
package es.uma.urbanpulse.urbancontext;
