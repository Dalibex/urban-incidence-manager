package es.uma.urbanpulse.incidents.service;

import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.incidents.dto.CreateIncidentRequest;
import es.uma.urbanpulse.incidents.entity.Category;
import es.uma.urbanpulse.incidents.entity.Incident;
import es.uma.urbanpulse.incidents.entity.InvalidIncidentDataException;
import es.uma.urbanpulse.incidents.entity.Location;
import es.uma.urbanpulse.incidents.repository.CategoryRepository;
import es.uma.urbanpulse.incidents.repository.IncidentRepository;
import java.time.Clock;
import java.time.Instant;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IncidentService {

    private final IncidentRepository incidents;
    private final CategoryRepository categories;
    private final Clock clock;
    private final GeometryFactory geometryFactory = new GeometryFactory();

    public IncidentService(
            IncidentRepository incidents, CategoryRepository categories, Clock clock) {
        this.incidents = incidents;
        this.categories = categories;
        this.clock = clock;
    }

    @Transactional
    public Incident createIncident(CreateIncidentRequest request, User reporter) {
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
                geometryFactory.createPoint(
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
        return incidents.save(incident);
    }
}
