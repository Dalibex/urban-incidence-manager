package es.uma.urbanpulse.incidents.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.uma.urbanpulse.shared.web.MalformedRequestException;
import org.junit.jupiter.api.Test;

/** The checks of the bounding box of {@code listIncidentLocations} that need no database. */
class BoundingBoxTest {

    @Test
    void noLimitMeansNoBox() {
        assertThat(BoundingBox.fromLimits(null, null, null, null)).isEmpty();
    }

    @Test
    void theFourLimitsMakeABox() {
        assertThat(BoundingBox.fromLimits(36.68, -4.52, 36.76, -4.34))
                .contains(new BoundingBox(36.68, -4.52, 36.76, -4.34));
    }

    @Test
    void onlySomeLimitsAreRejected() {
        assertThatThrownBy(() -> BoundingBox.fromLimits(36.68, -4.52, 36.76, null))
                .isInstanceOf(MalformedRequestException.class);
        assertThatThrownBy(() -> BoundingBox.fromLimits(36.68, null, null, null))
                .isInstanceOf(MalformedRequestException.class);
    }

    @Test
    void aMinimumOverItsMaximumIsRejected() {
        // South over north.
        assertThatThrownBy(() -> new BoundingBox(36.76, -4.52, 36.68, -4.34))
                .isInstanceOf(MalformedRequestException.class)
                .hasMessageContaining("minLatitude");
        // West over east.
        assertThatThrownBy(() -> new BoundingBox(36.68, -4.34, 36.76, -4.52))
                .isInstanceOf(MalformedRequestException.class)
                .hasMessageContaining("minLongitude");
    }

    @Test
    void aBoxOfOnePointIsAllowed() {
        assertThat(new BoundingBox(36.7213, -4.4214, 36.7213, -4.4214).minLatitude())
                .isEqualTo(36.7213);
    }

    @Test
    void theRangeLimitsAreIncluded() {
        assertThat(new BoundingBox(-90, -180, 90, 180).maxLongitude()).isEqualTo(180);
    }

    @Test
    void aLimitOutOfRangeIsRejected() {
        assertThatThrownBy(() -> new BoundingBox(-90.1, -4.52, 36.76, -4.34))
                .isInstanceOf(MalformedRequestException.class);
        assertThatThrownBy(() -> new BoundingBox(36.68, -4.52, 36.76, 180.1))
                .isInstanceOf(MalformedRequestException.class);
    }

    @Test
    void aLatitudeAndLongitudeSwappedAreRejectedWhenOutOfRange() {
        // A longitude of Málaga is a valid latitude, but -180 is not.
        assertThatThrownBy(() -> new BoundingBox(-180, 36.68, -4.34, 36.76))
                .isInstanceOf(MalformedRequestException.class);
    }

    @Test
    void notANumberIsRejected() {
        assertThatThrownBy(() -> new BoundingBox(Double.NaN, -4.52, 36.76, -4.34))
                .isInstanceOf(MalformedRequestException.class);
        assertThatThrownBy(() -> new BoundingBox(36.68, -4.52, 36.76, Double.POSITIVE_INFINITY))
                .isInstanceOf(MalformedRequestException.class);
    }
}
