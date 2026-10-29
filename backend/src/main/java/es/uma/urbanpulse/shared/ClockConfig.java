package es.uma.urbanpulse.shared;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application reads the time from this {@link Clock}, never from {@code Instant.now()}, so
 * tests can fix it (expiry of tokens, timestamps of the history). Always UTC.
 */
@Configuration
class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
