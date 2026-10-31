package es.uma.urbanpulse.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code createRegistration}. There is no {@code role}: a role sent by the client is
 * ignored because the record has nowhere to put it, and every new account is a citizen.
 */
public record CreateRegistrationRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull
                @Size(min = 8, message = "must be at least 8 characters")
                @MaxUtf8Bytes(PasswordRules.MAX_BYTES)
                String password) {

    /** Keeps the password out of logs and error messages. */
    @Override
    public String toString() {
        return "CreateRegistrationRequest[name=" + name + ", email=" + email + ", password=***]";
    }
}
