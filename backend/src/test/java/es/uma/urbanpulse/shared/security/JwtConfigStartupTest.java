package es.uma.urbanpulse.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Without a usable {@code JWT_SECRET} the application must not start. */
class JwtConfigStartupTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(JwtConfig.class)
                    .withBean(Clock.class, Clock::systemUTC)
                    .withPropertyValues("urbanpulse.security.jwt.access-token-ttl=PT15M");

    @Test
    void doesNotStartWithoutSecret() {
        runner.run(
                context ->
                        assertThat(context)
                                .hasFailed()
                                .getFailure()
                                .rootCause()
                                .hasMessageContaining("JWT_SECRET"));
    }

    @Test
    void doesNotStartWithAnEmptySecret() {
        runner.withPropertyValues("urbanpulse.security.jwt.secret=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void startsWithA256BitSecret() {
        runner.withPropertyValues("urbanpulse.security.jwt.secret=a-secret-of-exactly-thirty-two-b")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
