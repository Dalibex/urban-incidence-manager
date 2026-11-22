package es.uma.urbanpulse.geospatial.client;

import es.uma.urbanpulse.geospatial.service.AddressAnswerRejectedException;
import es.uma.urbanpulse.geospatial.service.AddressEnrichmentProperties;
import es.uma.urbanpulse.geospatial.service.AddressLookup;
import es.uma.urbanpulse.geospatial.service.AddressProvider;
import es.uma.urbanpulse.geospatial.service.AddressSourceUnavailableException;
import es.uma.urbanpulse.shared.text.Texts;
import es.uma.urbanpulse.urbancontext.dto.ContextSource;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The address at a point from Nominatim's reverse geocoding (ADR-0002): coordinates in, the street,
 * the house number and the postcode out, written as the normalized address of Málaga, such as
 * "Calle Larios, 5, 29005 Málaga".
 */
@Component
class NominatimClient implements AddressProvider {

    /** The municipality the addresses belong to, compared without accents or case. */
    static final String MUNICIPALITY = "Málaga";

    private static final String COUNTRY_CODE = "es";
    // The keys of Nominatim's address that name the municipality, by the size of the place.
    private static final List<String> MUNICIPALITY_KEYS =
            List.of("city", "town", "village", "municipality");
    // The keys that name the street, in the order they are taken.
    private static final List<String> STREET_KEYS =
            List.of("road", "pedestrian", "footway", "path", "cycleway", "square");
    // A real address is well under this; a longer value is not one.
    private static final int MAX_PART_LENGTH = 200;
    private static final int TOO_MANY_REQUESTS = 429;
    // A real answer is about 1 KiB; the limit keeps a hostile or misrouted answer from filling the
    // memory of the backend.
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final RestClient http;
    private final JsonMapper json;

    NominatimClient(AddressEnrichmentProperties properties, JsonMapper json) {
        // The JDK client never follows a redirect by default, so a 3xx is an answer it cannot read.
        var builder = HttpClient.newBuilder().connectTimeout(properties.nominatimTimeout());
        var proxy = properties.nominatimProxy();
        if (proxy != null) {
            // The deployment has no other way out (ADR-0011): the proxy is the only service with
            // internet. Unresolved, so the name is resolved by the JDK when it connects.
            builder.proxy(
                    ProxySelector.of(
                            InetSocketAddress.createUnresolved(proxy.getHost(), proxy.getPort())));
        }
        var requests = new JdkClientHttpRequestFactory(builder.build());
        requests.setReadTimeout(properties.nominatimTimeout());
        this.http =
                RestClient.builder()
                        .baseUrl(properties.nominatimBaseUrl().toString())
                        .requestFactory(requests)
                        // The usage policy asks every application to identify itself.
                        .defaultHeader(HttpHeaders.USER_AGENT, properties.nominatimUserAgent())
                        .build();
        this.json = json;
    }

    @Override
    public String source() {
        return ContextSource.OPENSTREETMAP.name();
    }

    @Override
    public AddressLookup lookUp(double latitude, double longitude) {
        try {
            return http.get()
                    .uri(
                            uri ->
                                    uri.path("/reverse")
                                            .queryParam("format", "jsonv2")
                                            .queryParam("lat", plain(latitude))
                                            .queryParam("lon", plain(longitude))
                                            // Building level, and only places with an address.
                                            .queryParam("zoom", 18)
                                            .queryParam("layer", "address")
                                            .queryParam("addressdetails", 1)
                                            .queryParam("accept-language", "es")
                                            .build())
                    .exchange(
                            (request, response) -> {
                                rejectErrors(response.getStatusCode());
                                return read(boundedBody(response.getBody()));
                            });
        } catch (ResourceAccessException e) {
            throw unreachable("Nominatim could not be reached", e);
        } catch (RestClientException e) {
            // The headers arrived but the body did not, for example past the read timeout.
            throw unreachable("Nominatim stopped answering", e);
        }
    }

    private static void rejectErrors(HttpStatusCode status) {
        // 429 is taken before the other 4xx.
        if (status.value() == TOO_MANY_REQUESTS || status.is5xxServerError()) {
            throw new AddressSourceUnavailableException("Nominatim answered " + status.value());
        }
        if (status.isError() || status.is3xxRedirection()) {
            throw new AddressAnswerRejectedException("Nominatim answered " + status.value());
        }
    }

    // Content-Length can be missing (chunked) or false, so the limit is on the bytes actually read.
    private static String boundedBody(InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new AddressAnswerRejectedException("Nominatim answered more than the limit");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // The message names the type of the cause (a timeout, a refused connection) but never the URL,
    // which holds the position of the report.
    private static AddressSourceUnavailableException unreachable(String what, Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return new AddressSourceUnavailableException(
                what + " (" + cause.getClass().getSimpleName() + ")");
    }

    // Double.toString writes 1.0E-4 for a value near 0.
    private static String plain(double degrees) {
        return BigDecimal.valueOf(degrees).toPlainString();
    }

    private AddressLookup read(String body) {
        if (body == null || body.isBlank()) {
            throw new AddressAnswerRejectedException("Nominatim answered without a body");
        }
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException e) {
            throw new AddressAnswerRejectedException("Nominatim answered JSON it cannot read", e);
        }
        if (!root.isObject()) {
            throw new AddressAnswerRejectedException("Nominatim answered something else than JSON");
        }
        // Nominatim answers 200 with {"error": "Unable to geocode"} where it knows no place, such
        // as the sea: there is no address of Málaga there.
        if (root.has("error")) {
            return new AddressLookup.OutsideArea();
        }
        var address = root.path("address");
        if (!address.isObject()) {
            throw new AddressAnswerRejectedException("Nominatim answered without an address");
        }
        if (!COUNTRY_CODE.equals(text(address, "country_code")) || !inTheMunicipality(address)) {
            return new AddressLookup.OutsideArea();
        }
        var street = first(address, STREET_KEYS);
        if (street == null) {
            return new AddressLookup.NoStreet();
        }
        return new AddressLookup.Found(
                normalized(street, text(address, "house_number"), text(address, "postcode")));
    }

    private static boolean inTheMunicipality(JsonNode address) {
        var municipality = comparable(MUNICIPALITY);
        for (var key : MUNICIPALITY_KEYS) {
            var name = text(address, key);
            if (name != null && comparable(name).equals(municipality)) {
                return true;
            }
        }
        return false;
    }

    // "Calle Larios, 5, 29005 Málaga": the house number and the postcode only when Nominatim has
    // them.
    private static String normalized(String street, String houseNumber, String postcode) {
        var address = new StringBuilder(street);
        if (houseNumber != null) {
            address.append(", ").append(houseNumber);
        }
        address.append(", ");
        if (postcode != null) {
            address.append(postcode).append(' ');
        }
        return address.append(MUNICIPALITY).toString();
    }

    private static String first(JsonNode address, List<String> keys) {
        for (var key : keys) {
            var value = text(address, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * A part of the address as stored: stripped, or {@code null} when Nominatim did not send it.
     *
     * @throws AddressAnswerRejectedException if it is not a text an address can hold
     */
    private static String text(JsonNode address, String key) {
        var value = address.path(key);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw new AddressAnswerRejectedException("Nominatim answered a " + key + " not a text");
        }
        var text = Texts.strip(value.asString());
        if (text.isEmpty()) {
            return null;
        }
        // PostgreSQL refuses the null character in a text, and an address has no control
        // characters.
        if (Texts.hasNullCharacter(text)
                || text.chars().anyMatch(Character::isISOControl)
                || Texts.length(text) > MAX_PART_LENGTH) {
            throw new AddressAnswerRejectedException(
                    "Nominatim answered a " + key + " that is not an address");
        }
        return text;
    }

    private static String comparable(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }
}
