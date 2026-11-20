package es.uma.urbanpulse.urbancontext.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class WeatherEnrichmentPropertiesTest {

    private static WeatherEnrichmentProperties withBaseUrl(String url) {
        return new WeatherEnrichmentProperties(
                true,
                url == null ? null : URI.create(url),
                null,
                Duration.ofSeconds(10),
                10,
                Duration.ofSeconds(2),
                Duration.ofMinutes(10));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://api.open-meteo.com",
                "https://api.open-meteo.com/",
                "http://localhost:8089",
                "http://LOCALHOST:1",
                "http://127.0.0.1:9",
                "http://[::1]:8089"
            })
    void acceptsHttpsAndHttpOnLoopback(String url) {
        assertThatCode(() -> withBaseUrl(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            textBlock =
                    """
                    http://api.open-meteo.com                | must be https, or http only for localhost
                    http://localhost.evil.example            | must be https, or http only for localhost
                    ftp://localhost                          | must be https, or http only for localhost
                    file:///etc/passwd                       | must have a host
                    https:///path                            | must have a host
                    /relative                                | must have a host
                    https://user:secret@api.open-meteo.com   | must not have user info, a query or a fragment
                    https://api.open-meteo.com?apikey=1      | must not have user info, a query or a fragment
                    https://api.open-meteo.com#frag          | must not have user info, a query or a fragment
                    """)
    void rejectsAnUnsafeBaseUrlNamingThePropertyAndTheReason(String url, String reason) {
        assertThatThrownBy(() -> withBaseUrl(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("urbanpulse.weather.open-meteo-base-url")
                .hasMessageContaining(reason);
    }

    private static WeatherEnrichmentProperties withProxy(String proxy) {
        return new WeatherEnrichmentProperties(
                true,
                URI.create("https://api.open-meteo.com"),
                proxy == null ? null : URI.create(proxy),
                Duration.ofSeconds(10),
                10,
                Duration.ofSeconds(2),
                Duration.ofMinutes(10));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "http://egress-proxy:3128", "http://egress-proxy:3128/"})
    void acceptsNoProxyOrAnHttpProxyWithAPort(String proxy) {
        assertThatCode(() -> withProxy(proxy)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            textBlock =
                    """
                    https://egress-proxy:3128         | must be http
                    http://egress-proxy               | must have a host and a port
                    http://user:secret@egress-proxy:3128 | must not have user info
                    http://egress-proxy:3128/path     | must not have user info, a path
                    http://egress-proxy:3128?a=1      | must not have user info, a path
                    http://egress-proxy:3128#frag     | must not have user info, a path
                    """)
    void rejectsAnUnsafeProxyNamingThePropertyAndTheReason(String url, String reason) {
        assertThatThrownBy(() -> withProxy(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("urbanpulse.weather.open-meteo-proxy")
                .hasMessageContaining(reason);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            nullValues = "NONE",
            textBlock =
                    """
                    ''                       | NONE
                    http://egress-proxy:3128 | http://egress-proxy:3128
                    """)
    void bindsTheProxyFromTheEnvironmentWhereEmptyMeansNoProxy(String value, URI expected) {
        var source =
                new MapConfigurationPropertySource(
                        Map.of(
                                "urbanpulse.weather.enabled", "true",
                                "urbanpulse.weather.open-meteo-base-url",
                                        "https://api.open-meteo.com",
                                "urbanpulse.weather.open-meteo-proxy", value,
                                "urbanpulse.weather.interval", "PT30S",
                                "urbanpulse.weather.batch-size", "20",
                                "urbanpulse.weather.open-meteo-timeout", "PT2S",
                                "urbanpulse.weather.max-delay", "PT1H"));

        var bound =
                new Binder(source).bind("urbanpulse.weather", WeatherEnrichmentProperties.class);

        assertThat(bound.get().openMeteoProxy()).isEqualTo(expected);
    }

    @Test
    void rejectsAMissingBaseUrl() {
        assertThatThrownBy(() -> withBaseUrl(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("urbanpulse.weather.open-meteo-base-url");
    }
}
