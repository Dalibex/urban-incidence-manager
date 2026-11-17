package es.uma.urbanpulse.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Sort;

/** The page of a listing (docs/api.md, "Listings"). */
class PageRequestsTest {

    private static final Set<String> SORTABLE = Set.of("reportedAt", "updatedAt");
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "reportedAt");

    @Test
    void withoutSortItUsesTheDefaultOrderAndThenTheId() {
        var page = PageRequests.of(2, 20, null, SORTABLE, NEWEST_FIRST);

        assertThat(page.getPageNumber()).isEqualTo(2);
        assertThat(page.getPageSize()).isEqualTo(20);
        assertThat(page.getSort())
                .containsExactly(Sort.Order.desc("reportedAt"), Sort.Order.asc("id"));
    }

    @Test
    void aSortablePropertyReplacesTheDefaultAndIdStaysLast() {
        var page = PageRequests.of(0, 10, "updatedAt,asc", SORTABLE, NEWEST_FIRST);

        assertThat(page.getSort())
                .containsExactly(Sort.Order.asc("updatedAt"), Sort.Order.asc("id"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"title,asc", "id,desc", "reportedAt", "reportedAt,up", "", "a,b,c"})
    void anUnknownOrMalformedSortIsABadRequest(String sort) {
        assertThatThrownBy(() -> PageRequests.of(0, 20, sort, SORTABLE, NEWEST_FIRST))
                .isInstanceOfSatisfying(
                        MalformedRequestException.class,
                        ex -> {
                            assertThat(ex.status().value()).isEqualTo(400);
                            assertThat(ex.code()).contains("MALFORMED_REQUEST");
                        });
    }

    @ParameterizedTest
    @CsvSource({"21474837, 100", "2147483647, 2", "46341, 46341"})
    void aPageWhoseFirstRowDoesNotFitAnIntIsABadRequest(int page, int size) {
        assertThatThrownBy(() -> PageRequests.of(page, size, null, SORTABLE, NEWEST_FIRST))
                .isInstanceOfSatisfying(
                        MalformedRequestException.class,
                        ex -> {
                            assertThat(ex.status().value()).isEqualTo(400);
                            assertThat(ex.code()).contains("MALFORMED_REQUEST");
                        });
    }

    @ParameterizedTest
    @CsvSource({"21474836, 100", "46340, 46340", "0, 100", "2147483647, 1"})
    void aPageWhoseFirstRowFitsAnIntIsAccepted(int page, int size) {
        var request = PageRequests.of(page, size, null, SORTABLE, NEWEST_FIRST);

        assertThat(request.getPageNumber()).isEqualTo(page);
        assertThat(request.getPageSize()).isEqualTo(size);
    }
}
