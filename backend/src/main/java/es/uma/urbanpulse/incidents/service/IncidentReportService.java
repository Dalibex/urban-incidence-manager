package es.uma.urbanpulse.incidents.service;

import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.identity.repository.UserRepository;
import es.uma.urbanpulse.incidents.dto.CreateIncidentRequest;
import es.uma.urbanpulse.incidents.entity.Category;
import es.uma.urbanpulse.incidents.entity.Incident;
import es.uma.urbanpulse.incidents.entity.InvalidIncidentDataException;
import es.uma.urbanpulse.incidents.entity.Location;
import es.uma.urbanpulse.incidents.repository.CategoryRepository;
import es.uma.urbanpulse.incidents.repository.IncidentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IncidentReportService {

    private final IncidentRepository incidents;
    private final CategoryRepository categories;
    private final UserRepository users;
    private final Clock clock;
    // A business metric next to the technical ones of Micrometer, read by the load tests in
    // Grafana: how many reports the system accepts while it is under load.
    private final Counter incidentsCreated;

    public IncidentReportService(
            IncidentRepository incidents,
            CategoryRepository categories,
            UserRepository users,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.incidents = incidents;
        this.categories = categories;
        this.users = users;
        this.clock = clock;
        this.incidentsCreated =
                // Not "...created": Prometheus reserves the _created suffix and would drop it.
                Counter.builder("urbanpulse.incidents.reported")
                        .description("Incidents reported and stored")
                        .register(meterRegistry);
    }

    @Transactional
    public Incident createIncident(CreateIncidentRequest request, UUID reporterId) {
        User reporter =
                users.findById(reporterId)
                        .orElseThrow(
                                () ->
                                        new InsufficientAuthenticationException(
                                                "The user does not exist"));
        Category category = null;
        if (request.categoryId() != null) {
            category =
                    categories
                            .findById(request.categoryId())
                            .orElseThrow(InvalidIncidentDataException::invalidCategory);
            if (!Boolean.TRUE.equals(category.getIsActive())) {
                throw InvalidIncidentDataException.invalidCategory();
            }
        }

        var reported = request.location();
        var location = new Location();
        location.setCoordinates(
                Location.WGS84.createPoint(
                        new Coordinate(reported.longitude(), reported.latitude())));
        location.setAccuracy(reported.accuracy());
        // address, neighborhood and district stay null (RF26: graceful degradation).

        var incident =
                Incident.report(
                        reporter,
                        request.title(),
                        request.description(),
                        category,
                        location,
                        Instant.now(clock));
        Incident saved = incidents.saveAndFlush(incident);
        // Only after the row is written: a rejected report is not counted.
        incidentsCreated.increment();
        return saved;
    }
}
