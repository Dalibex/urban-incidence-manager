package es.uma.urbanpulse.identity.dto;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.text.Text;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code createUser}: a staff account with the initial password the administrator typed,
 * under the same rules as {@code createRegistration}. {@code CITIZEN} passes validation here and is
 * refused by the service, with the other rules of the role.
 */
public record CreateUserRequest(
        @NotNull @Text(max = 100) String name,
        @NotNull @Email @Text(max = 254) String email,
        @NotNull @Text(min = 8, visible = false) @MaxUtf8Bytes(PasswordRules.MAX_BYTES)
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
