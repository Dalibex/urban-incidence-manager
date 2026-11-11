package es.uma.urbanpulse.identity.dto;

import es.uma.urbanpulse.identity.entity.User;
import java.util.UUID;

/** {@code TechnicianSummary}: an enabled technician, as the assignment form shows it, no email. */
public record TechnicianSummary(UUID id, String name) {

    public static TechnicianSummary from(User user) {
        return new TechnicianSummary(user.getId(), user.getName());
    }
}
