package es.uma.urbanpulse.identity.dto;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.text.Text;

/**
 * Body of {@code updateUser}. Every property is optional: an absent one, or {@code null}, stays as
 * it is, so an administrator who only renames an account never undoes a role change another one
 * made in the meantime.
 *
 * @param name {@code null} keeps the name; {@link Text} leaves it alone, unlike {@code @NotBlank},
 *     and refuses a name of white space or no-break spaces, which would be stored empty or look
 *     empty
 */
public record UpdateUserRequest(@Text(max = 100) String name, Role role, Boolean enabled) {}
