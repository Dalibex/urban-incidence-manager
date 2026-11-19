package es.uma.urbanpulse.shared.web;

import static es.uma.urbanpulse.shared.web.ForwardedRequests.newClientAddress;
import static es.uma.urbanpulse.shared.web.ForwardedRequests.newEmail;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Mirrors the deployment Compose: Tomcat trusts the {@code X-Forwarded-*} headers only from nginx's
 * fixed address, so any other peer (here 127.0.0.1, like a process on the Docker host calling the
 * backend directly) cannot forge the client address, which would dodge the per-address limits, or
 * the {@code Location}. That a trusted peer's headers are used is tested in {@link
 * ForwardedHeadersBehindProxyIT}.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "server.forward-headers-strategy=native",
            // SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES of the deployment Compose: nginx only.
            "server.tomcat.remoteip.internal-proxies=10\\.203\\.217\\.10",
            "urbanpulse.security.sign-up-attempts.max=1"
        })
@Import({TestcontainersConfiguration.class, TestTokens.class})
class ForwardedHeadersFromAnUntrustedPeerIT {

    private static final String INCIDENT =
            """
            {"title": "Street light off", "description": "Reported directly.",
             "location": {"latitude": 36.7213, "longitude": -4.4214, "accuracy": 10.0}}
            """;

    @Autowired private Environment environment;
    @Autowired private TestTokens tokens;
    @Autowired private JdbcTemplate jdbc;

    private ForwardedRequests requests;
    private UUID citizenId;

    @BeforeEach
    void connect() {
        requests = new ForwardedRequests(environment);
    }

    @AfterEach
    void deleteTheRowsOfTheTest() {
        if (citizenId != null) {
            jdbc.update(
                    "DELETE FROM status_change WHERE incident_id IN (SELECT id FROM incident WHERE reporter_id = ?)",
                    citizenId);
            jdbc.update(
                    "DELETE FROM urban_context WHERE incident_id IN (SELECT id FROM incident WHERE reporter_id = ?)",
                    citizenId);
            jdbc.update(
                    "DELETE FROM location WHERE incident_id IN (SELECT id FROM incident WHERE reporter_id = ?)",
                    citizenId);
            jdbc.update("DELETE FROM incident WHERE reporter_id = ?", citizenId);
            jdbc.update(
                    "DELETE FROM users u WHERE u.id = ? AND NOT EXISTS (SELECT 1 FROM audit_log a WHERE a.actor_id = u.id)",
                    citizenId);
        }
    }

    @Test
    void aForgedClientAddressDoesNotDodgeTheSignUpLimitOfTheRealPeer() throws Exception {
        assertThat(requests.signUp(newEmail(), newClientAddress()).statusCode()).isEqualTo(201);

        assertThat(requests.signUp(newEmail(), newClientAddress()).statusCode()).isEqualTo(429);
    }

    @Test
    void forgedForwardedHostProtoAndPortDoNotChangeTheLocation() throws Exception {
        // Inserted, not signed up: every request comes from the same real peer, whose sign-up
        // limit the other test uses.
        citizenId =
                jdbc.queryForObject(
                        "INSERT INTO users (name, email, password_hash, role)"
                                + " VALUES ('Test citizen', ?, '{bcrypt}not-used', 'CITIZEN')"
                                + " RETURNING id",
                        UUID.class,
                        newEmail());

        var created =
                requests.post(
                        "/api/v1/incidents",
                        INCIDENT,
                        Map.of(
                                "Authorization", tokens.bearer(citizenId, Role.CITIZEN),
                                "X-Forwarded-Proto", "https",
                                "X-Forwarded-Host", "evil.example",
                                "X-Forwarded-Port", "443"));

        assertThat(created.statusCode()).isEqualTo(201);
        String incidentId = JsonPath.read(created.body(), "$.id");
        var port = environment.getRequiredProperty("local.server.port");
        assertThat(created.headers().firstValue("Location"))
                .hasValue("http://localhost:" + port + "/api/v1/incidents/" + incidentId);
    }
}
