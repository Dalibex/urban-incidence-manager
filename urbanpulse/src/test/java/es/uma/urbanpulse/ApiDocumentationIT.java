package es.uma.urbanpulse;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** The OpenAPI contract and Swagger UI are public; the API itself is not (docs/api.md). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApiDocumentationIT {

    @Autowired private MockMvc mvc;

    @Test
    void servesTheContractCopiedIntoTheJarWithoutAuthentication() throws Exception {
        mvc.perform(get("/openapi.yml"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("openapi: 3.1.0")));
    }

    @Test
    void servesSwaggerUiWithoutAuthentication() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void keepsTheApiProtected() throws Exception {
        mvc.perform(get("/api/v1/incidents")).andExpect(status().isUnauthorized());
    }
}
