package es.uma.urbanpulse.incidents.controller;

import es.uma.urbanpulse.incidents.dto.CategoryResponse;
import es.uma.urbanpulse.incidents.mapper.CategoryMapper;
import es.uma.urbanpulse.incidents.repository.CategoryRepository;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryRepository categories;

    public CategoryController(CategoryRepository categories) {
        this.categories = categories;
    }

    @GetMapping
    public List<CategoryResponse> listCategories() {
        return categories.findByIsActiveTrueOrderByNameAsc().stream()
                .map(CategoryMapper::toResponse)
                .toList();
    }
}
