package es.uma.urbanpulse.identity.dto;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.entity.User;
import java.util.UUID;

/** Public profile of a user. It never carries the password hash. */
public record UserResponse(UUID id, String name, String email, Role role) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole());
    }
}
