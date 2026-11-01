package es.uma.urbanpulse;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import java.net.URL;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * Checks that the responses of the HTTP integration tests match the contract (#111): the schema of
 * their operation, status and content type in {@code docs/api/openapi.yml}, error bodies included.
 *
 * <p>Import {@link EveryResponse} in a MockMvc test and every request to {@code /api/v1} is
 * checked, with no change to the test methods:
 *
 * <pre>{@code
 * @SpringBootTest
 * @AutoConfigureMockMvc
 * @Import({TestcontainersConfiguration.class, ApiContract.EveryResponse.class})
 * class SomeApiIT { ... }
 * }</pre>
 *
 * <p>It reads the copy of the contract that the build puts in the jar ({@code
 * /static/openapi.yml}), the same one Swagger UI shows. Only the response is checked: tests send
 * invalid requests on purpose (a page size over 100, a body that is not JSON), and the contract
 * also says what the API answers to them. Requests outside {@code /api/v1} (health, documentation)
 * are not part of the contract and are skipped.
 */
public final class ApiContract {

    private static final String CONTRACT = "/static/openapi.yml";
    private static final String BASE_PATH = "/api/v1";

    private static final OpenApiInteractionValidator VALIDATOR = createValidator();
    private static final ResultMatcher MATCHES_CONTRACT =
            OpenApiValidationMatchers.openApi().isValid(VALIDATOR);

    private ApiContract() {}

    /** Fails when a response to {@code /api/v1} does not match its operation and status. */
    public static ResultMatcher matchesContract() {
        return result -> {
            if (result.getRequest().getRequestURI().startsWith(BASE_PATH + "/")) {
                MATCHES_CONTRACT.match(result);
            }
        };
    }

    /** The validator itself, to check a response built by hand (for example, a tampered one). */
    public static OpenApiInteractionValidator validator() {
        return VALIDATOR;
    }

    /** Adds {@link #matchesContract()} to every request of the MockMvc of the test context. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class EveryResponse {

        @Bean
        MockMvcBuilderCustomizer contractValidation() {
            return builder -> builder.alwaysExpect(matchesContract());
        }
    }

    private static OpenApiInteractionValidator createValidator() {
        URL contract = ApiContract.class.getResource(CONTRACT);
        if (contract == null) {
            throw new IllegalStateException(
                    CONTRACT
                            + " is not on the classpath: run the build, which copies the contract");
        }
        return OpenApiInteractionValidator.createForSpecificationUrl(contract.toString())
                .withBasePathOverride(BASE_PATH)
                .withLevelResolver(
                        LevelResolver.create()
                                .withLevel("validation.request", ValidationReport.Level.IGNORE)
                                .build())
                .build();
    }
}
