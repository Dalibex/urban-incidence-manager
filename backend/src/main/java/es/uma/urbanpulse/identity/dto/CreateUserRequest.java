package es.uma.urbanpulse.identity.dto;

import es.uma.urbanpulse.identity.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code createUser}: a staff account with the initial password the administrator typed,
 * under the same rules as {@code createRegistration}. {@code CITIZEN} passes validation here and is
 * refused by the service, with the other rules of the role.
 */
public record CreateUserRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull
                @Size(min = 8, message = "must be at least 8 characters")
                @MaxUtf8Bytes(PasswordRules.MAX_BYTES)
                String password,
        @NotNull Role role) {

    /** Keeps the password out of logs and error messages. */
    @Override
    public String toString() {
        return "CreateUserRequest[name="
                + name
                + ", email="
                + email
                + ", password=***, role="
                + role
                + "]";
    }
}
