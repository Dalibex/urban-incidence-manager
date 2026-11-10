package es.uma.urbanpulse.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code createPasswordChange}. The new password follows the rules of sign-up; the current
 * one only the byte limit, like sign-in, so bcrypt never gets a password it would refuse with a
 * {@code 500}.
 */
public record CreatePasswordChangeRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotEmpty @MaxUtf8Bytes(PasswordRules.MAX_BYTES) String currentPassword,
        @NotNull
                @Size(min = 8, message = "must be at least 8 characters")
                @MaxUtf8Bytes(PasswordRules.MAX_BYTES)
                String newPassword) {

    /** Keeps both passwords out of logs and error messages. */
    @Override
    public String toString() {
        return "CreatePasswordChangeRequest[email="
                + email
                + ", currentPassword=***, newPassword=***]";
    }
}
