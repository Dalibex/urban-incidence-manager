package es.uma.urbanpulse.identity.dto;

import es.uma.urbanpulse.shared.text.Text;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Body of {@code createMfaVerification}. */
public record CreateMfaVerificationRequest(
        @NotNull @Text(max = 200, visible = false) String challengeToken,
        @NotNull @Pattern(regexp = "^[0-9]{6}$") String code) {

    @Override
    public String toString() {
        return "CreateMfaVerificationRequest[challengeToken=***, code=***]";
    }
}
