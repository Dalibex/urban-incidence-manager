package es.uma.urbanpulse.shared.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Limits of the evidence policy (ADR-0013), read from the environment (Twelve-Factor III): {@code
 * EVIDENCE_MAX_FILE_SIZE} and {@code EVIDENCE_MAX_FILES_PER_INCIDENT}. They are checked when the
 * application starts, so a limit that would refuse every photo stops the start instead.
 *
 * @param maxFileSize the largest photo accepted
 * @param maxFilesPerIncident how many photos an incident may have
 */
@ConfigurationProperties("urbanpulse.storage.evidence")
public record EvidenceProperties(DataSize maxFileSize, int maxFilesPerIncident) {

    public EvidenceProperties {
        if (maxFileSize == null || maxFileSize.toBytes() <= 0) {
            throw new IllegalArgumentException("EVIDENCE_MAX_FILE_SIZE must be a positive size");
        }
        if (maxFilesPerIncident < 1) {
            throw new IllegalArgumentException("EVIDENCE_MAX_FILES_PER_INCIDENT must be 1 or more");
        }
    }
}
