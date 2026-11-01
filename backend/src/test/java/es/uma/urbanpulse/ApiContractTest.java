package es.uma.urbanpulse;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import org.junit.jupiter.api.Test;

/**
 * The contract check of {@link ApiContract} rejects responses that drift from {@code openapi.yml}
 * (#111). The responses are written by hand, so no implementation is needed.
 */
class ApiContractTest {

    private static final String PAGE_METADATA =
            "\"page\": {\"number\": 0, \"size\": 20, \"totalElements\": 1, \"totalPages\": 1}";

    private static final String VALID_SUMMARY =
            """
            {"id": "3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e", "title": "Broken streetlight",
             "status": "REPORTED", "category": null,
             "reportedAt": "2026-11-02T09:00:00Z", "updatedAt": "2026-11-02T09:00:00Z"}
            """;

    @Test
    void acceptsAResponseThatMatchesTheContract() {
        ValidationReport report = incidentPage(VALID_SUMMARY);

        assertThat(report.hasErrors()).as(report.getMessages().toString()).isFalse();
    }

    @Test
    void rejectsAPropertyOfTheWrongType() {
        assertRejected(incidentPage(VALID_SUMMARY.replace("\"Broken streetlight\"", "42")));
    }

    @Test
    void rejectsAValueOutsideTheEnum() {
        assertRejected(incidentPage(VALID_SUMMARY.replace("\"REPORTED\"", "\"OPEN\"")));
    }

    @Test
    void rejectsAValueWithTheWrongFormat() {
        assertRejected(
                incidentPage(
                        VALID_SUMMARY.replace(
                                "\"3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e\"", "\"not-a-uuid\"")));
    }

    @Test
    void rejectsAMissingRequiredProperty() {
        assertRejected(
                incidentPage(
                        VALID_SUMMARY.replace(", \"updatedAt\": \"2026-11-02T09:00:00Z\"", "")));
    }

    @Test
    void rejectsAnErrorBodyThatIsNotAProblem() {
        ValidationReport report =
                ApiContract.validator()
                        .validateResponse(
                                "/api/v1/incidents/3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e",
                                Request.Method.GET,
                                SimpleResponse.Builder.notFound()
                                        .withContentType("application/problem+json")
                                        .withBody("{\"error\": \"not found\"}")
                                        .build());

        assertRejected(report);
    }

    @Test
    void rejectsAStatusTheOperationDoesNotDocument() {
        ValidationReport report =
                ApiContract.validator()
                        .validateResponse(
                                "/api/v1/incidents",
                                Request.Method.GET,
                                SimpleResponse.Builder.status(418)
                                        .withContentType("application/problem+json")
                                        .withBody(
                                                "{\"type\": \"about:blank\", \"title\": \"Teapot\", \"status\": 418}")
                                        .build());

        assertRejected(report);
    }

    private static ValidationReport incidentPage(String summary) {
        return ApiContract.validator()
                .validateResponse(
                        "/api/v1/incidents",
                        Request.Method.GET,
                        SimpleResponse.Builder.ok()
                                .withContentType("application/json")
                                .withBody("{\"content\": [" + summary + "], " + PAGE_METADATA + "}")
                                .build());
    }

    private static void assertRejected(ValidationReport report) {
        assertThat(report.hasErrors()).as("the contract check should fail").isTrue();
    }
}
