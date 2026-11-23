package es.uma.urbanpulse.incidents;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code listCategories} with the icon of each category (#267), checked against the contract of
 * every response ({@link ApiContract}). The initial categories come from the migrations, so their
 * fixed ids name them here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
@Transactional
class CategoryApiIT {

    private static final String CATEGORIES = "/api/v1/categories";
    private static final String OTHER = "474f62e6-521a-4958-ba99-e0d46b420233";

    @Autowired private MockMvc mvc;
    @Autowired private TestTokens tokens;

    @Test
    void eachInitialCategoryHasItsIcon() throws Exception {
        mvc.perform(get(CATEGORIES).header(HttpHeaders.AUTHORIZATION, citizenToken()))
                .andExpect(status().isOk())
                .andExpect(iconOf("fd4a9835-80be-4b73-bc9f-a042bed62b61", "STREET_LIGHTING"))
                .andExpect(iconOf("265293f4-456f-4a2d-b637-1c3a263815e4", "PAVEMENT"))
                .andExpect(iconOf("cffa0e9c-1e8e-4502-aa41-1d0d4e2306ee", "WASTE"))
                .andExpect(iconOf("16fff6f7-e8ab-4841-ae3e-9dff80314c01", "URBAN_FURNITURE"))
                .andExpect(iconOf("edaddd74-d0d9-4185-adc8-e278c468eb29", "PARKS"))
                .andExpect(iconOf("3a73f376-34c4-4112-b85a-89f104c0f1f4", "SIGNAGE"));
    }

    // "Other" has no drawing of its own: the clients draw their generic icon for it.
    @Test
    void otherHasNoIcon() throws Exception {
        mvc.perform(get(CATEGORIES).header(HttpHeaders.AUTHORIZATION, citizenToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].icon", OTHER).value(contains(nullValue())));
    }

    private static ResultMatcher iconOf(String categoryId, String icon) {
        return jsonPath("$[?(@.id == '%s')].icon", categoryId).value(contains(icon));
    }

    private String citizenToken() {
        return tokens.bearer(Role.CITIZEN);
    }
}
