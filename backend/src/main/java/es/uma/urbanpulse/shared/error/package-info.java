/**
 * The errors a module's domain raises, with no dependency on HTTP: a rule that refuses the change
 * ({@link es.uma.urbanpulse.shared.error.RuleViolationException}) or data that is missing or not
 * valid ({@link es.uma.urbanpulse.shared.error.InvalidDataException}), each with a stable code from
 * the catalogue in {@code docs/api.md}. Only {@code shared.web.ApiExceptionHandler} knows which
 * status answers each kind (AGENTS.md, section 4; ADR-0020, "no dependency on HTTP").
 *
 * <p>Decisions: ADR-0010, ADR-0020 ({@code docs/adr/}).
 */
package es.uma.urbanpulse.shared.error;
