package es.uma.urbanpulse.incidents.service;

import static org.assertj.core.api.Assertions.assertThat;

import es.uma.urbanpulse.incidents.dto.CreateIncidentRequest;
import es.uma.urbanpulse.incidents.dto.CreateIncidentRequest.ReportedLocation;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The hash that tells a retry from a different report with the same key (#289). */
class ReportFingerprintTest {

    private static final UUID CATEGORY = UUID.fromString("0b6f7c1e-3d2a-4f5b-8c9d-1e2f3a4b5c6d");

    @Test
    void theSameReportGivesTheSameHash() {
        assertThat(ReportFingerprint.of(report("Broken streetlight", "The lamp is off.")))
                .isEqualTo(ReportFingerprint.of(report("Broken streetlight", "The lamp is off.")))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void everyFieldOfTheReportChangesTheHash() {
        var original = ReportFingerprint.of(report("Broken streetlight", "The lamp is off."));
        var location = new ReportedLocation(36.7213, -4.4214, 12.5);

        assertThat(
                        new String[] {
                            ReportFingerprint.of(report("Fallen tree", "The lamp is off.")),
                            ReportFingerprint.of(report("Broken streetlight", "It is dark.")),
                            ReportFingerprint.of(
                                    new CreateIncidentRequest(
                                            "Broken streetlight",
                                            "The lamp is off.",
                                            null,
                                            location)),
                            ReportFingerprint.of(
                                    request(new ReportedLocation(36.7214, -4.4214, 12.5))),
                            ReportFingerprint.of(
                                    request(new ReportedLocation(36.7213, -4.4215, 12.5))),
                            ReportFingerprint.of(
                                    request(new ReportedLocation(36.7213, -4.4214, 12.6))),
                            ReportFingerprint.of(
                                    request(new ReportedLocation(36.7213, -4.4214, null)))
                        })
                .doesNotContain(original)
                .doesNotHaveDuplicates();
    }

    // Without the length of each value, "ab" + "c" and "a" + "bc" would be the same text.
    @Test
    void movingTextFromOneFieldToTheNextChangesTheHash() {
        assertThat(ReportFingerprint.of(report("Broken street", "light")))
                .isNotEqualTo(ReportFingerprint.of(report("Broken", " streetlight")));
    }

    // Swapped coordinates are another place, never the same report.
    @Test
    void swappingLatitudeAndLongitudeChangesTheHash() {
        assertThat(ReportFingerprint.of(request(new ReportedLocation(36.0, -4.0, null))))
                .isNotEqualTo(
                        ReportFingerprint.of(request(new ReportedLocation(-4.0, 36.0, null))));
    }

    private static CreateIncidentRequest report(String title, String description) {
        return new CreateIncidentRequest(
                title, description, CATEGORY, new ReportedLocation(36.7213, -4.4214, 12.5));
    }

    private static CreateIncidentRequest request(ReportedLocation location) {
        return new CreateIncidentRequest(
                "Broken streetlight", "The lamp is off.", CATEGORY, location);
    }
}
