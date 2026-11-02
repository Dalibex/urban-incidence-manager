package es.uma.urbanpulse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class UrbanpulseApplication {

    /** Set by the profile of a one-off admin process, such as {@code bootstrap-admin}. */
    private static final String ONE_OFF_PROCESS = "urbanpulse.one-off-process";

    static void main(String[] args) {
        var context = SpringApplication.run(UrbanpulseApplication.class, args);
        // A one-off process (12-factor XII) ends once its runner has returned. Today the JVM would
        // also end by itself; the call keeps it so if a non-daemon thread, such as a scheduler's,
        // is ever added.
        if (context.getEnvironment().getProperty(ONE_OFF_PROCESS, Boolean.class, false)) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
