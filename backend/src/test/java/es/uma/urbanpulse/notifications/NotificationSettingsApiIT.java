package es.uma.urbanpulse.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * #373: the citizen's notification channels, {@code getNotificationSettings} and {@code
 * updateNotificationSettings} of the contract ({@code docs/api/openapi.yml}). Email is on from
 * sign-up and push is off; a {@code PATCH} changes only the properties it carries. Only citizens
 * have channels.
 *
 * <p>The users are inserted with SQL, every check goes through HTTP and is validated against the
 * contract ({@link ApiContract}). Each test runs inside a transaction that is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
@Transactional
@DisplayName("Notification settings of the citizen")
class NotificationSettingsApiIT {

    private static final String SETTINGS = "/api/v1/users/me/notification-settings";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;

    private UUID citizen;

    @BeforeEach
    void createACitizen() {
        citizen = user(Role.CITIZEN);
    }

    @Nested
    @DisplayName("Reading them")
    class Reading {

        @Test
        @DisplayName("a new citizen has email on and push off")
        void aNewCitizenHasEmailOnAndPushOff() throws Exception {
            // Arrange: the citizen created above, never changed.

            // Act
            ResultActions answer = mvc.perform(as(citizen, Role.CITIZEN, get(SETTINGS)));

            // Assert
            answer.andExpect(status().isOk())
                    .andExpect(jsonPath("$.push").value(false))
                    .andExpect(jsonPath("$.email").value(true));
        }
    }

    @Nested
    @DisplayName("Changing them")
    class Changing {

        @ParameterizedTest(name = "{0} from push {1}, email {2} -> push {3}, email {4}")
        @CsvSource(
                delimiter = '|',
                value = {
                    "{\"push\": true}   | false | false | true  | false",
                    "{\"email\": true}  | true  | false | true  | true",
                    "{\"push\": false}  | true  | true  | false | true",
                    "{\"email\": false} | false | true  | false | false",
                    "{\"push\": true, \"email\": false} | false | true | true | false"
                })
        @DisplayName("changes only the channels present in the body, and keeps them")
        void changesOnlyThePresentChannels(
                String body,
                boolean pushBefore,
                boolean emailBefore,
                boolean pushAfter,
                boolean emailAfter)
                throws Exception {
            // Arrange
            jdbc.update(
                    "UPDATE users SET notify_by_push = ?, notify_by_email = ? WHERE id = ?",
                    pushBefore,
                    emailBefore,
                    citizen);

            // Act
            ResultActions answer = update(citizen, Role.CITIZEN, body);

            // Assert
            answer.andExpect(status().isOk())
                    .andExpect(jsonPath("$.push").value(pushAfter))
                    .andExpect(jsonPath("$.email").value(emailAfter));
            assertThat(storedChannels(citizen))
                    .containsEntry("notify_by_push", pushAfter)
                    .containsEntry("notify_by_email", emailAfter);
        }

        // minProperties: 1 in UpdateNotificationSettingsRequest.
        @Test
        @DisplayName("an empty body is unprocessable and changes nothing")
        void anEmptyBodyIsUnprocessable() throws Exception {
            // Arrange: the citizen created above, push off and email on.

            // Act
            ResultActions answer = update(citizen, Role.CITIZEN, "{}");

            // Assert
            answer.andExpect(status().isUnprocessableContent())
                    .andExpect(
                            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            assertThat(storedChannels(citizen))
                    .containsEntry("notify_by_push", false)
                    .containsEntry("notify_by_email", true);
        }
    }

    @Nested
    @DisplayName("Who may use them")
    class WhoMayUseThem {

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = Role.class,
                names = {"MUNICIPAL_OPERATOR", "TECHNICIAN", "ADMINISTRATOR", "ANALYST"})
        @DisplayName("staff may not read them: 403")
        void staffMayNotReadThem(Role role) throws Exception {
            // Arrange
            UUID staff = user(role);

            // Act
            ResultActions answer = mvc.perform(as(staff, role, get(SETTINGS)));

            // Assert
            answer.andExpect(status().isForbidden())
                    .andExpect(
                            content()
                                    .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = Role.class,
                names = {"MUNICIPAL_OPERATOR", "TECHNICIAN", "ADMINISTRATOR", "ANALYST"})
        @DisplayName("staff may not change them: 403, and nothing changes")
        void staffMayNotChangeThem(Role role) throws Exception {
            // Arrange
            UUID staff = user(role);

            // Act
            ResultActions answer = update(staff, role, "{\"push\": true}");

            // Assert
            answer.andExpect(status().isForbidden())
                    .andExpect(
                            content()
                                    .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
            assertThat(storedChannels(staff)).containsEntry("notify_by_push", false);
        }

        @Test
        @DisplayName("reading without a token is 401")
        void readingWithoutATokenIsUnauthorized() throws Exception {
            // Act
            ResultActions answer = mvc.perform(get(SETTINGS));

            // Assert
            answer.andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("changing without a token is 401")
        void changingWithoutATokenIsUnauthorized() throws Exception {
            // Act
            ResultActions answer =
                    mvc.perform(
                            patch(SETTINGS)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"push\": true}"));

            // Assert
            answer.andExpect(status().isUnauthorized());
        }
    }

    private ResultActions update(UUID user, Role role, String body) throws Exception {
        return mvc.perform(
                as(
                        user,
                        role,
                        patch(SETTINGS).contentType(MediaType.APPLICATION_JSON).content(body)));
    }

    private MockHttpServletRequestBuilder as(
            UUID user, Role role, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, tokens.bearer(user, role));
    }

    private Map<String, Object> storedChannels(UUID user) {
        return jdbc.queryForMap(
                "SELECT notify_by_push, notify_by_email FROM users WHERE id = ?", user);
    }

    private UUID user(Role role) {
        return jdbc.queryForObject(
                "INSERT INTO users (name, email, password_hash, role)"
                        + " VALUES (?, ?, '{bcrypt}not-used', ?) RETURNING id",
                UUID.class,
                "Test " + role,
                role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com",
                role.name());
    }
}
