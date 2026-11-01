package es.uma.urbanpulse.shared;

import java.util.HashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Gives the demo dataset (#54, docs/demo.md) the password of the demo users, read from the
 * environment (Twelve-Factor III): {@code DEMO_PASSWORD} becomes the Flyway placeholder {@code
 * demo_password} of {@code db/demo-data/R__demo_data.sql}.
 *
 * <p>The check runs before Flyway applies any migration, so a missing password stops the start. A
 * placeholder written in {@code application-demo.yaml} would not: Spring Boot leaves an unresolved
 * {@code ${DEMO_PASSWORD}} there as literal text, and every demo user would get it as password.
 */
@Configuration
@Profile("demo")
class DemoDatasetConfig {

    static final String PASSWORD_PLACEHOLDER = "demo_password";

    @Bean
    FlywayConfigurationCustomizer demoPasswordPlaceholder(
            @Value("${DEMO_PASSWORD:}") String password) {
        if (password.isBlank()) {
            throw new IllegalStateException(
                    "The demo profile needs DEMO_PASSWORD, the password of the demo users"
                            + " (docs/demo.md)");
        }
        return configuration -> {
            var placeholders = new HashMap<>(configuration.getPlaceholders());
            placeholders.put(PASSWORD_PLACEHOLDER, password);
            configuration.placeholders(placeholders);
        };
    }
}
