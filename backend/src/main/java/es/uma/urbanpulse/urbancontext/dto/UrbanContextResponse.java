package es.uma.urbanpulse.urbancontext.dto;

import es.uma.urbanpulse.urbancontext.entity.UrbanContext;
import es.uma.urbanpulse.urbancontext.entity.UrbanContextStatus;
import java.time.Instant;
import java.util.Set;

/**
 * {@code UrbanContextResponse}: the urban context of an incident, for staff. Each part is {@code
 * null} while it is not there, never a default value, and {@code status} says why.
 */
public record UrbanContextResponse(
        UrbanContextStatus status, Instant capturedAt, WeatherContext weather) {

    // The statuses with a context to show (docs/api/openapi.yml, UrbanContextResponse). With any
    // other, a value left in the row is not current, so it is not sent.
    private static final Set<UrbanContextStatus> WITH_CONTEXT =
            Set.of(UrbanContextStatus.COMPLETE, UrbanContextStatus.PARTIAL);

    public static UrbanContextResponse of(UrbanContext context) {
        if (!WITH_CONTEXT.contains(context.getStatus())) {
            return new UrbanContextResponse(context.getStatus(), null, null);
        }
        var capturedAt = context.getCapturedAt();
        return new UrbanContextResponse(
                context.getStatus(),
                capturedAt == null ? null : capturedAt.toInstant(),
                WeatherContext.of(context.getWeather()));
    }

    /**
     * An incident reported before the enrichment existed has no context, and will never get one: it
     * is not {@code PENDING}, or a client would wait for it forever.
     */
    public static UrbanContextResponse unavailable() {
        return new UrbanContextResponse(UrbanContextStatus.UNAVAILABLE, null, null);
    }
}
