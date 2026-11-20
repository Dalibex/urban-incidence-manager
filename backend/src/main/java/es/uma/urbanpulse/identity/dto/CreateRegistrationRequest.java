package es.uma.urbanpulse.identity.dto;

import es.uma.urbanpulse.shared.text.Text;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code createRegistration}. There is no {@code role}: a role sent by the client is
 * ignored because the record has nowhere to put it, and every new account is a citizen.
 */
public record CreateRegistrationRequest(
        @NotNull @Text(max = 100) String name,
        @NotNull @Email @Text(max = 254) String email,
        @NotNull @Text(min = 8, visible = false) @MaxUtf8Bytes(PasswordRules.MAX_BYTES)
                String password) {

    /** Keeps the password out of logs and error messages. */
    @Override
    public String toString() {
        return "CreateRegistrationRequest[name=" + name + ", email=" + email + ", password=***]";
    }
}
