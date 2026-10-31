/**
 * Request and response bodies of the identity operations, separate from the JPA entities so no
 * password hash or internal field is ever serialised by accident.
 *
 * <p>Decisions: ADR-0008, ADR-0010 ({@code docs/adr/}).
 */
package es.uma.urbanpulse.identity.dto;
