package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.entity.User;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * The filters of {@code listUsers}, combined with AND. An absent filter, or an empty role list,
 * does not restrict the list.
 *
 * @param name text the name contains, ignoring case; {@code null} for any name
 * @param email text the email contains, ignoring case; {@code null} for any email
 */
public record UserFilter(String name, String email, List<Role> roles) {

    // Escapes the wildcards of LIKE, so "50%" or "a_b" are searched as typed. Not a backslash,
    // which PostgreSQL also reads as its default escape and Java would need to double.
    private static final char ESCAPE = '!';

    public UserFilter {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    Specification<User> specification() {
        return Specification.allOf(contains("name", name), contains("email", email), hasRole());
    }

    private static Specification<User> contains(String property, String text) {
        if (text == null) {
            return Specification.unrestricted();
        }
        var pattern = "%" + escapeWildcards(text) + "%";
        // Both sides lower-cased by the database: Java and PostgreSQL do not always agree (a final
        // sigma), and the stored value is lower-cased there anyway.
        return (user, query, builder) ->
                builder.like(
                        builder.lower(user.get(property)),
                        builder.lower(builder.literal(pattern)),
                        ESCAPE);
    }

    private Specification<User> hasRole() {
        if (roles.isEmpty()) {
            return Specification.unrestricted();
        }
        return (user, query, builder) -> user.get("role").in(roles);
    }

    private static String escapeWildcards(String text) {
        return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
