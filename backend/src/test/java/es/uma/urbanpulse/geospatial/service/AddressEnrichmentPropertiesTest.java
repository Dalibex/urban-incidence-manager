package es.uma.urbanpulse.geospatial.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AddressEnrichmentPropertiesTest {

    private static AddressEnrichmentProperties withProxy(URI proxy) {
        return new AddressEnrichmentProperties(
                true,
                URI.create("https://nominatim.openstreetmap.org"),
                proxy,
                "UrbanPulse",
                Duration.ofSeconds(30),
                10,
                Duration.ofSeconds(1),
                Duration.ofSeconds(3),
                Duration.ofHours(24));
    }

    @Test
    void anUnsetProxyCallsNominatimDirectly() {
        // An unset NOMINATIM_PROXY reaches the binder as an empty string.
        assertThat(withProxy(URI.create("")).nominatimProxy()).isNull();
    }

    @Test
    void theEgressProxyIsKept() {
        var proxy = URI.create("http://egress-proxy:3128");

        assertThat(withProxy(proxy).nominatimProxy()).isEqualTo(proxy);
    }
}
