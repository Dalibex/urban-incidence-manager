package es.uma.urbanpulse.identity.controller;

import es.uma.urbanpulse.identity.service.FirstAdministratorRejectedException;
import es.uma.urbanpulse.identity.service.FirstAdministratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The one-off admin process that creates the first administrator (#126, 12-factor XII), run with
 * the same image and configuration as the backend (docs/deployment.md):
 *
 * <pre>docker compose --profile app run --rm backend --spring.profiles.include=bootstrap-admin
 * </pre>
 *
 * <p>The values come from the environment (12-factor III) and the password is never logged. The
 * bean only exists with the {@value #PROFILE} profile, so a normal start never runs it. With that
 * profile no web server starts ({@code application-bootstrap-admin.yaml}) and {@code
 * UrbanpulseApplication} ends the process when this runner returns. A rejection stops the start, so
 * the process exits with an error and the reason in the log.
 */
@Component
@Profile(FirstAdministratorCommand.PROFILE)
class FirstAdministratorCommand implements ApplicationRunner {

    static final String PROFILE = "bootstrap-admin";

    private static final Logger log = LoggerFactory.getLogger(FirstAdministratorCommand.class);

    private final FirstAdministratorService service;
    private final String name;
    private final String email;
    private final String password;

    FirstAdministratorCommand(
            FirstAdministratorService service,
            @Value("${BOOTSTRAP_ADMIN_NAME:}") String name,
            @Value("${BOOTSTRAP_ADMIN_EMAIL:}") String email,
            @Value("${BOOTSTRAP_ADMIN_PASSWORD:}") String password) {
        this.service = service;
        this.name = name;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            switch (service.createIfAbsent(name, email, password)) {
                case CREATED ->
                        log.info(
                                "Created the first administrator with BOOTSTRAP_ADMIN_EMAIL. Sign"
                                        + " in with it, then remove BOOTSTRAP_ADMIN_PASSWORD from"
                                        + " the environment.");
                case ALREADY_PRESENT ->
                        log.info("An enabled administrator already exists: nothing was changed.");
            }
        } catch (FirstAdministratorRejectedException e) {
            throw new IllegalStateException(
                    "The first administrator was not created and nothing was changed: "
                            + e.getMessage()
                            + ". Check BOOTSTRAP_ADMIN_NAME, BOOTSTRAP_ADMIN_EMAIL and"
                            + " BOOTSTRAP_ADMIN_PASSWORD (docs/deployment.md).",
                    e);
        }
    }
}
