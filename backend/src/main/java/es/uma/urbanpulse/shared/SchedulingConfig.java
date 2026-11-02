package es.uma.urbanpulse.shared;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Runs the {@code @Scheduled} jobs inside the backend, such as the clean-up of old refresh tokens.
 * Valid while there is a single replica (ADR-0011): with more, each one would run every job.
 */
@Configuration
@EnableScheduling
class SchedulingConfig {}
