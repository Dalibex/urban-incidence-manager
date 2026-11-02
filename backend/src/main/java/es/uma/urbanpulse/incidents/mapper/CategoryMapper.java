package es.uma.urbanpulse.incidents.mapper;

import es.uma.urbanpulse.incidents.dto.CategoryResponse;
import es.uma.urbanpulse.incidents.entity.Category;

public final class CategoryMapper {

    private CategoryMapper() {}

    public static CategoryResponse toResponse(Category category) {
        return new CategoryResponse(
                category.getId(), category.getName(), category.getDescription());
    }
}
