/**
 * Analytics module: read-only indicators, zone analysis and reports. It owns no tables: it reads
 * those of the incidents module with aggregate queries and never writes. Today it holds the
 * operational report of RF19 ({@code getIncidentReport}, #42).
 *
 * <p>Decisions: ADR-0010 ({@code docs/adr/}).
 */
package es.uma.urbanpulse.analytics;
