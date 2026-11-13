package es.uma.urbanpulse.incidents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code getIncidentStatistics} outside a test transaction. {@link IncidentStatisticsApiIT} rolls
 * every test back, so the service joins the test's transaction and its {@code REPEATABLE_READ}
 * isolation is never applied there. Here the service opens its own read-only transaction with that
 * isolation, as in production, and the request must still succeed with groups that add up. It
 * writes nothing, so it leaves no data.
 *
 * <p>It does not change incidents while the four counts run, so it does not show that they share
 * one snapshot: that rests on {@code REPEATABLE_READ} in PostgreSQL, as the service says (#258, the
 * row of two people changing the same thing at once).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
class IncidentStatisticsTransactionIT {

    @Autowired private MockMvc mvc;
    @Autowired private TestTokens tokens;

    @Test
    void countsInItsOwnRepeatableReadTransaction() throws Exception {
        String body =
                mvc.perform(
                                get("/api/v1/incident-statistics")
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                tokens.bearer(Role.MUNICIPAL_OPERATOR)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        long total = ((Number) JsonPath.read(body, "$.total")).longValue();
        for (String list : List.of("byStatus", "byPriority", "byCategory", "byDepartment")) {
            List<Number> counts = JsonPath.read(body, "$." + list + "[*].count");
            assertThat(counts.stream().mapToLong(Number::longValue).sum())
                    .as(list)
                    .isEqualTo(total);
        }
    }
}
