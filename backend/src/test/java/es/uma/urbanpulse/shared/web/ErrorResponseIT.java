package es.uma.urbanpulse.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * What a client error leaves behind, found when running M1 of #142 on #149: one log line that its
 * {@code traceId} leads to, and messages in English whatever language the client or the machine
 * uses.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ApiContract.EveryResponse.class})
@ExtendWith(OutputCaptureExtension.class)
class ErrorResponseIT {

    private static final String INVALID_SIGN_UP =
            """
            {"name": "", "email": "not-an-email", "password": "short"}
            """;

    @Autowired private MockMvc mvc;

    @Test
    void aClientErrorWritesOneLogLineWithItsId(CapturedOutput output) throws Exception {
        var id = "m1-" + UUID.randomUUID();

        mvc.perform(signUp().header(RequestIdFilter.HEADER, id))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.traceId").value(id));

        assertThat(output.getAll().lines().filter(line -> line.contains("[" + id + "]")))
                .anySatisfy(
                        line ->
                                assertThat(line)
                                        .contains("Answered 422 to POST /api/v1/registrations"))
                .noneSatisfy(line -> assertThat(line).contains("not-an-email"));
    }

    @Test
    void validationMessagesAreInEnglishWhateverTheClientAsks() throws Exception {
        mvc.perform(signUp().header(HttpHeaders.ACCEPT_LANGUAGE, "es-ES,es;q=0.9"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(
                        jsonPath("$.errors[?(@.field == 'name')].message")
                                .value("must not be blank"))
                .andExpect(
                        jsonPath("$.errors[?(@.field == 'email')].message")
                                .value("must be a well-formed email address"));
    }

    private static MockHttpServletRequestBuilder signUp() {
        var address = UUID.randomUUID().hashCode();
        return post("/api/v1/registrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(INVALID_SIGN_UP)
                .with(
                        request -> {
                            request.setRemoteAddr(
                                    "10.149." + (address & 0xff) + "." + ((address >> 8) & 0xff));
                            return request;
                        });
    }
}
