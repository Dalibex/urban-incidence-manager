package es.uma.urbanpulse.shared.web;

import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Builds the page of a listing from its {@code page}, {@code size} and {@code sort} parameters, as
 * {@code docs/api.md} ("Listings") asks: a {@code Pageable} argument would reduce {@code size}
 * silently and end an unknown {@code sort} property in {@code 500}. The controller validates the
 * range of {@code page} and {@code size}; this class checks {@code sort} and adds {@code id} as the
 * last criterion, so the order is stable between pages.
 */
public final class PageRequests {

    /** The id property every sortable entity has. */
    static final String ID = "id";

    private static final Pattern SORT = Pattern.compile("^([a-zA-Z]+),(asc|desc)$");

    private PageRequests() {}

    /**
     * @param sort {@code property,asc} or {@code property,desc}; {@code null} for the default
     * @param sortable the properties the listing documents in the contract
     * @param defaultSort the order when {@code sort} is absent
     * @throws MalformedRequestException if {@code sort} is malformed or not one of {@code sortable}
     */
    public static PageRequest of(
            int page, int size, String sort, Set<String> sortable, Sort defaultSort) {
        return PageRequest.of(page, size, order(sort, sortable, defaultSort).and(Sort.by(ID)));
    }

    private static Sort order(String sort, Set<String> sortable, Sort defaultSort) {
        if (sort == null) {
            return defaultSort;
        }
        var matcher = SORT.matcher(sort);
        if (!matcher.matches() || !sortable.contains(matcher.group(1))) {
            throw new MalformedRequestException(
                    "Sort by one of "
                            + String.join(", ", sortable.stream().sorted().toList())
                            + ", as property,asc or property,desc.");
        }
        return Sort.by(Sort.Direction.fromString(matcher.group(2)), matcher.group(1));
    }
}
