package es.uma.urbanpulse.incidents.service;

import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.identity.repository.UserRepository;
import es.uma.urbanpulse.incidents.dto.CreateIncidentRequest;
import es.uma.urbanpulse.incidents.entity.Category;
import es.uma.urbanpulse.incidents.entity.IdempotencyKey;
import es.uma.urbanpulse.incidents.entity.Incident;
import es.uma.urbanpulse.incidents.entity.InvalidIncidentDataException;
import es.uma.urbanpulse.incidents.entity.Location;
import es.uma.urbanpulse.incidents.repository.CategoryRepository;
import es.uma.urbanpulse.incidents.repository.IncidentRepository;
import es.uma.urbanpulse.urbancontext.service.UrbanContextService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class IncidentReportService {

    private final IncidentRepository incidents;
    private final CategoryRepository categories;
    private final UserRepository users;
    private final UrbanContextService urbanContexts;
    private final Clock clock;
    // A business metric next to the technical ones of Micrometer, read by the load tests in
    // Grafana: how many reports the system accepts while it is under load.
    private final Counter incidentsCreated;

    public IncidentReportService(
            IncidentRepository incidents,
            CategoryRepository categories,
            UserRepository users,
            UrbanContextService urbanContexts,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.incidents = incidents;
        this.urbanContexts = urbanContexts;
        this.categories = categories;
        this.users = users;
        this.clock = clock;
        this.incidentsCreated =
                // Not "...created": Prometheus reserves the _created suffix and would drop it.
                Counter.builder("urbanpulse.incidents.reported")
                        .description("Incidents reported and stored")
                        .register(meterRegistry);
    }

    /** A report sent without an {@code Idempotency-Key}: every call creates an incident. */
    @Transactional
    public Incident createIncident(CreateIncidentRequest request, UUID reporterId) {
        User reporter = users.findById(reporterId).orElseThrow(IncidentReportService::unknownUser);
        return report(request, reporter, null);
    }

    /**
     * A report sent with an {@code Idempotency-Key} (#289). A key this citizen already used with
     * the same report returns that incident, as it is now, and creates nothing; with a different
     * report it is refused.
     *
     * @throws IdempotencyKeyReusedException if the key was used for a different report
     */
    @Transactional
    public Incident createIncident(
            CreateIncidentRequest request, UUID reporterId, UUID idempotencyKey) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        // Locked until the transaction ends: two requests with the same key at once are checked
        // one after the other, so the second finds the incident of the first instead of creating
        // another. The unique constraint of V17 is the database rule behind it.
        User reporter =
                users.findLockedById(reporterId).orElseThrow(IncidentReportService::unknownUser);
        var key = new IdempotencyKey(idempotencyKey, ReportFingerprint.of(request));
        var previous = incidents.findByIdempotencyKey(reporterId, idempotencyKey);
        if (previous.isPresent()) {
            var stored = previous.get();
            // Found by its key, so it has one.
            if (!stored.idempotencyKey().orElseThrow().isSameRequest(key.requestHash())) {
                throw new IdempotencyKeyReusedException();
            }
            return stored;
        }
        return report(request, reporter, key);
    }

    /**
     * @param key {@code null} for a report sent without an {@code Idempotency-Key}
     */
    private Incident report(CreateIncidentRequest request, User reporter, IdempotencyKey key) {
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

        Instant now = Instant.now(clock);
        var incident =
                key == null
                        ? Incident.report(
                                reporter,
                                request.title(),
                                request.description(),
                                category,
                                location,
                                now)
                        : Incident.report(
                                reporter,
                                request.title(),
                                request.description(),
                                category,
                                location,
                                now,
                                key);
        Incident saved = incidents.saveAndFlush(incident);
        // Only a PENDING row in the same transaction: the weather is fetched after the report, so a
        // source that is down never delays or fails it (RF26, ADR-0018).
        urbanContexts.createPending(saved);
        // Counted once the report is committed: a rejected report, or one whose transaction rolls
        // back, is never counted.
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        incidentsCreated.increment();
                    }
                });
        return saved;
    }

    private static InsufficientAuthenticationException unknownUser() {
        return new InsufficientAuthenticationException("The user does not exist");
    }
}
