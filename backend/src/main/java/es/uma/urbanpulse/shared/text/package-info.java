/**
 * The text rule of the contract, shared by every request that takes text: a {@code maxLength} and
 * {@code minLength} counted in characters (code points), a {@code pattern: "\\S"} with the white
 * space of ECMA-262, and no null character, which PostgreSQL cannot store. Bean Validation uses it
 * through {@link es.uma.urbanpulse.shared.text.Text}, and the domain through {@link
 * es.uma.urbanpulse.shared.text.Texts} when it checks a field after the role and the state.
 *
 * <p>Decisions: ADR-0008 ({@code docs/adr/}).
 */
package es.uma.urbanpulse.shared.text;
