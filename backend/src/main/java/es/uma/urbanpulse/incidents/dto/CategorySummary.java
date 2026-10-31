package es.uma.urbanpulse.incidents.dto;

import es.uma.urbanpulse.incidents.entity.Category;
import java.util.UUID;

/** {@code CategorySummary}: the category embedded in an incident, so no extra request is needed. */
public record CategorySummary(UUID id, String name) {

    /** {@code null} while the incident has no category. */
    static CategorySummary of(Category category) {
        return category == null ? null : new CategorySummary(category.getId(), category.getName());
    }
}
