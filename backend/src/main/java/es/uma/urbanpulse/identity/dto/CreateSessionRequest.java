package es.uma.urbanpulse.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code createSession}. The byte limit also applies here: bcrypt refuses a longer password
 * with an exception, which would otherwise be a {@code 500}.
 */
public record CreateSessionRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotEmpty @MaxUtf8Bytes(PasswordRules.MAX_BYTES) String password) {

    @Override
    public String toString() {
        return "CreateSessionRequest[email=" + email + ", password=***]";
    }
}
