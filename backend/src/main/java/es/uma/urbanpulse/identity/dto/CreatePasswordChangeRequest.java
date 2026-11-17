package es.uma.urbanpulse.identity.dto;

import es.uma.urbanpulse.shared.text.Text;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code createPasswordChange}. The new password follows the rules of sign-up; the current
 * one only the byte limit, like sign-in, so bcrypt never gets a password it would refuse with a
 * {@code 500}.
 */
public record CreatePasswordChangeRequest(
        @NotNull @Email @Text(max = 254) String email,
        @NotEmpty @MaxUtf8Bytes(PasswordRules.MAX_BYTES) String currentPassword,
        @NotNull @Text(min = 8, visible = false) @MaxUtf8Bytes(PasswordRules.MAX_BYTES)
                String newPassword) {

    /** Keeps both passwords out of logs and error messages. */
    @Override
    public String toString() {
        return "CreatePasswordChangeRequest[email="
                + email
                + ", currentPassword=***, newPassword=***]";
    }
}
