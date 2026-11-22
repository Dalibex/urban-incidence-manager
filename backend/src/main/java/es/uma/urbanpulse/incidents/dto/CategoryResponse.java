package es.uma.urbanpulse.incidents.dto;

import es.uma.urbanpulse.incidents.entity.CategoryIcon;
import java.util.UUID;

/**
 * @param icon {@code null} when the category has none: the clients draw their generic icon
 */
public record CategoryResponse(UUID id, String name, String description, CategoryIcon icon) {}
