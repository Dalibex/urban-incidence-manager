package es.uma.urbanpulse.identity.dto;

import es.uma.urbanpulse.shared.text.Text;
import jakarta.validation.constraints.NotNull;

/** Body of {@code createMfaEnrollment}. */
public record CreateMfaEnrollmentRequest(
        @NotNull @Text(max = 200, visible = false) String challengeToken) {

    @Override
    public String toString() {
        return "CreateMfaEnrollmentRequest[challengeToken=***]";
    }
}
