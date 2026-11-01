package es.uma.urbanpulse.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.Map;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DemoDatasetConfigTest {

    private final DemoDatasetConfig config = new DemoDatasetConfig();

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void stopsTheStartWithoutAPassword(String password) {
        assertThatIllegalStateException()
                .isThrownBy(() -> config.demoPasswordPlaceholder(password))
                .withMessageContaining("DEMO_PASSWORD");
    }

    @Test
    void passesThePasswordToFlywayAndKeepsTheOtherPlaceholders() {
        var flyway = new FluentConfiguration().placeholders(Map.of("other", "value"));

        config.demoPasswordPlaceholder("s3cret 'quoted'").customize(flyway);

        assertThat(flyway.getPlaceholders())
                .containsEntry(DemoDatasetConfig.PASSWORD_PLACEHOLDER, "s3cret 'quoted'")
                .containsEntry("other", "value");
    }
}
