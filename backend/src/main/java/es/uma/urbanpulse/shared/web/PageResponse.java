package es.uma.urbanpulse.shared.web;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * One page of a listing: its items and {@code PageMetadata} of {@code docs/api/openapi.yml}, the
 * shape every paginated response has ({@code docs/api.md}, "Listings").
 */
public record PageResponse<T>(List<T> content, PageMetadata page) {

    public PageResponse {
        content = List.copyOf(content);
    }

    public record PageMetadata(int number, int size, long totalElements, int totalPages) {}

    /** The page with each item converted to its response. */
    public static <E, T> PageResponse<T> of(Page<E> page, Function<? super E, T> toResponse) {
        return new PageResponse<>(
                page.getContent().stream().<T>map(toResponse).toList(),
                new PageMetadata(
                        page.getNumber(),
                        page.getSize(),
                        page.getTotalElements(),
                        page.getTotalPages()));
    }
}
