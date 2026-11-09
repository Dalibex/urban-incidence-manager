/**
 * Storage of the evidence files outside the relational database (ADR-0013). The rest of the code
 * only knows {@link es.uma.urbanpulse.shared.storage.StorageService}; {@link
 * es.uma.urbanpulse.shared.storage.LocalFsStorage} keeps the files on the {@code evidence_data}
 * volume. The keys are generated here, never taken from the client, and are not public URLs.
 *
 * <p>Decisions: ADR-0010, ADR-0013 ({@code docs/adr/}).
 */
package es.uma.urbanpulse.shared.storage;
