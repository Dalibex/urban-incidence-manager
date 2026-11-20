package es.uma.urbanpulse.urbancontext.service;

import static org.assertj.core.api.Assertions.assertThat;

import es.uma.urbanpulse.urbancontext.entity.Weather;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The mapping of WMO codes to {@link Weather} of ADR-0018. */
class WeatherMappingTest {

    @Test
    void clearSkyIsSunny() {
        assertThat(WeatherMapping.of(0, 5.0)).contains(Weather.SUNNY);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void aFewCloudsArePartlyCloudy(int code) {
        assertThat(WeatherMapping.of(code, 5.0)).contains(Weather.PARTLY_CLOUDY);
    }

    @Test
    void overcastIsCloudy() {
        assertThat(WeatherMapping.of(3, 5.0)).contains(Weather.CLOUDY);
    }

    @ParameterizedTest
    @ValueSource(ints = {45, 48})
    void fogIsFoggy(int code) {
        assertThat(WeatherMapping.of(code, 5.0)).contains(Weather.FOGGY);
    }

    @Test
    void drizzleRainAndShowersAreRainy() {
        var codes = IntStream.concat(IntStream.rangeClosed(51, 67), IntStream.rangeClosed(80, 82));
        assertThat(codes.boxed())
                .allSatisfy(
                        code -> assertThat(WeatherMapping.of(code, 5.0)).contains(Weather.RAINY));
    }

    @Test
    void snowIsSnowy() {
        var codes = IntStream.concat(IntStream.rangeClosed(71, 77), IntStream.of(85, 86));
        assertThat(codes.boxed())
                .allSatisfy(
                        code -> assertThat(WeatherMapping.of(code, 5.0)).contains(Weather.SNOWY));
    }

    @Test
    void thunderstormsAreStormy() {
        assertThat(IntStream.rangeClosed(95, 99).boxed())
                .allSatisfy(
                        code -> assertThat(WeatherMapping.of(code, 5.0)).contains(Weather.STORMY));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 45, 48})
    void strongWindMakesASkyWithoutPrecipitationWindy(int code) {
        assertThat(WeatherMapping.of(code, 40.0)).contains(Weather.WINDY);
        assertThat(WeatherMapping.of(code, 90.0)).contains(Weather.WINDY);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 45, 48})
    void windJustUnderTheLimitIsNotWindy(int code) {
        assertThat(WeatherMapping.of(code, 39.9))
                .hasValueSatisfying(weather -> assertThat(weather).isNotEqualTo(Weather.WINDY));
    }

    @ParameterizedTest
    @ValueSource(ints = {51, 63, 67, 71, 77, 80, 85, 95, 99})
    void strongWindDoesNotOverridePrecipitation(int code) {
        assertThat(WeatherMapping.of(code, 80.0))
                .hasValueSatisfying(weather -> assertThat(weather).isNotEqualTo(Weather.WINDY));
    }

    @Test
    void aMissingWindMapsByTheCodeAlone() {
        assertThat(WeatherMapping.of(0, null)).contains(Weather.SUNNY);
        assertThat(WeatherMapping.of(61, null)).contains(Weather.RAINY);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 4, 44, 46, 50, 68, 70, 78, 79, 83, 84, 87, 94, 100})
    void aCodeOutsideTheTableGivesNothing(int code) {
        assertThat(WeatherMapping.of(code, 5.0)).isEmpty();
        assertThat(WeatherMapping.of(code, 90.0)).isEmpty();
        assertThat(WeatherMapping.of(code, null)).isEmpty();
    }
}
