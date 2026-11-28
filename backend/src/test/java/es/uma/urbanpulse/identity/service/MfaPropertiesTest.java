package es.uma.urbanpulse.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.service.AccountSecurityProperties.AttemptLimit;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;

@ExtendWith(OutputCaptureExtension.class)
class MfaPropertiesTest {

    // Base64 of 32 bytes, of 31 bytes and of 33 bytes.
    private static final String KEY = "dGVzdC1rZXktb2YtdGhlLXNlY29uZC1mYWN0b3ItMzI=";
    private static final String SHORT_KEY = "YS1rZXktb2YtMzEtYnl0ZXMtb25seS1ub3QtMzIhIQ==";
    private static final String LONG_KEY = "dGVzdC1rZXktb2YtdGhlLXNlY29uZC1mYWN0b3ItMzIh";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(MfaConfiguration.class)
                    .withPropertyValues(
                            "urbanpulse.security.mfa.issuer=UrbanPulse",
                            "urbanpulse.security.mfa.challenge-ttl=PT5M",
                            "urbanpulse.security.mfa.code-attempts-per-challenge=5",
                            "urbanpulse.security.mfa.code-attempts.max=10",
                            "urbanpulse.security.mfa.code-attempts.window=PT15M");

    @Test
    void acceptsTheBase64Of32Bytes() {
        assertThat(properties(KEY).encryptionKeyBytes()).hasSize(32);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {SHORT_KEY, LONG_KEY, "not base64!"})
    void refusesAKeyThatIsNotTheBase64Of32Bytes(String key) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties(key))
                .withMessageContaining("MFA_ENCRYPTION_KEY")
                .withNoCause();
    }

    @Test
    void neverPrintsTheKey() {
        assertThat(properties(KEY).toString()).doesNotContain(KEY);
    }

    @Test
    void asksForACodeOnlyFromTheListedRoles() {
        var properties = properties(KEY);

        assertThat(properties.requiredFor(Role.ADMINISTRATOR)).isTrue();
        assertThat(properties.requiredFor(Role.CITIZEN)).isFalse();
    }

    @Test
    void doesNotStartWithoutAKey() {
        runner.run(
                context ->
                        assertThat(context)
                                .hasFailed()
                                .getFailure()
                                .rootCause()
                                .hasMessageContaining("MFA_ENCRYPTION_KEY"));
    }

    // #435: without a value, the limit of consecutive wrong codes is the most NIST allows.
    @Test
    void limitsConsecutiveWrongCodesTo100ByDefault() {
        runner.withPropertyValues("urbanpulse.security.mfa.encryption-key=" + KEY)
                .run(
                        context ->
                                assertThat(
                                                context.getBean(MfaProperties.class)
                                                        .maxConsecutiveWrongCodes())
                                        .isEqualTo(100));
    }

    // #435: no limit, or one above NIST's 100 consecutive failures, stops the start.
    @ParameterizedTest
    @ValueSource(strings = {"0", "101"})
    void doesNotStartWithALimitOfWrongCodesOutside1To100(String limit) {
        runner.withPropertyValues(
                        "urbanpulse.security.mfa.encryption-key=" + KEY,
                        "urbanpulse.security.mfa.max-consecutive-wrong-codes=" + limit)
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .rootCause()
                                        .hasMessageContaining("max-consecutive-wrong-codes"));
    }

    @Test
    void bindsAnEmptyListOfRolesAsNoRole() {
        runner.withPropertyValues(
                        "urbanpulse.security.mfa.encryption-key=" + KEY,
                        "urbanpulse.security.mfa.required-roles=")
                .run(
                        context ->
                                assertThat(context.getBean(MfaProperties.class).requiredRoles())
                                        .isEmpty());
    }

    @Test
    void bindsTheRolesSeparatedByCommas() {
        runner.withPropertyValues(
                        "urbanpulse.security.mfa.encryption-key=" + KEY,
                        "urbanpulse.security.mfa.required-roles=TECHNICIAN,ANALYST")
                .run(
                        context ->
                                assertThat(context.getBean(MfaProperties.class).requiredRoles())
                                        .containsExactlyInAnyOrder(Role.TECHNICIAN, Role.ANALYST));
    }

    @Test
    void doesNotStartWithCitizenAmongTheRoles() {
        runner.withPropertyValues(
                        "urbanpulse.security.mfa.encryption-key=" + KEY,
                        "urbanpulse.security.mfa.required-roles=TECHNICIAN,CITIZEN")
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .rootCause()
                                        .hasMessageContaining("must not include CITIZEN")
                                        .hasMessageContaining("mobile app"));
    }

    @Test
    void warnsAtStartWhenNoRoleNeedsTheSecondFactor(CapturedOutput output) {
        runner.withPropertyValues(
                        "urbanpulse.security.mfa.encryption-key=" + KEY,
                        "urbanpulse.security.mfa.required-roles=")
                .run(context -> assertThat(context).hasNotFailed());

        assertThat(output)
                .containsOnlyOnce(
                        "MFA_REQUIRED_ROLES is empty: no sign-in asks for a second factor");
    }

    @Test
    void doesNotWarnWhenARoleNeedsTheSecondFactor(CapturedOutput output) {
        runner.withPropertyValues(
                        "urbanpulse.security.mfa.encryption-key=" + KEY,
                        "urbanpulse.security.mfa.required-roles=TECHNICIAN")
                .run(context -> assertThat(context).hasNotFailed());

        assertThat(output).doesNotContain("MFA_REQUIRED_ROLES is empty");
    }

    private static MfaProperties properties(String key) {
        return new MfaProperties(
                Set.of(Role.ADMINISTRATOR),
                key,
                "UrbanPulse",
                Duration.ofMinutes(5),
                5,
                new AttemptLimit(10, Duration.ofMinutes(15)),
                100);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MfaProperties.class)
    static class MfaConfiguration {}
}
