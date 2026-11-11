package es.uma.urbanpulse.identity.dto;

import es.uma.urbanpulse.identity.entity.Role;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code updateUser}. Every property is optional: an absent one, or {@code null}, stays as
 * it is, so an administrator who only renames an account never undoes a role change another one
 * made in the meantime.
 *
 * @param name a blank name is refused by the service, not here: {@code @NotBlank} would also refuse
 *     an absent name, and a {@code \S} pattern only knows ASCII white space, so a name of Unicode
 *     spaces would pass it and be stored empty after {@code strip()}
 */
public record UpdateUserRequest(@Size(max = 100) String name, Role role, Boolean enabled) {}
