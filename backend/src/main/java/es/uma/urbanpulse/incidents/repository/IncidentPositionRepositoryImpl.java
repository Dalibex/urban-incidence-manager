package es.uma.urbanpulse.incidents.repository;

import es.uma.urbanpulse.incidents.dto.IncidentPositionResponse;
import es.uma.urbanpulse.incidents.entity.Incident;
import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.Priority;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.util.List;
import java.util.UUID;
import org.locationtech.jts.geom.Point;
import org.springframework.data.jpa.domain.Specification;

/**
 * Spring Data picks it up by its name as the implementation of {@link IncidentPositionRepository}.
 */
class IncidentPositionRepositoryImpl implements IncidentPositionRepository {

    private final EntityManager entityManager;

    IncidentPositionRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<IncidentPositionResponse> findPositions(Specification<Incident> spec, int limit) {
        var cb = entityManager.getCriteriaBuilder();
        var query = cb.createTupleQuery();
        var incident = query.from(Incident.class);
        // The same path as the bounding box uses, so Hibernate joins the location only once.
        var coordinates = incident.get("location").get("coordinates");

        // The priority is stored as text, so its order is spelled out: HIGH first, none last.
        var priorityRank =
                cb.<Integer>selectCase()
                        .when(cb.equal(incident.get("priority"), Priority.HIGH), 0)
                        .when(cb.equal(incident.get("priority"), Priority.MEDIUM), 1)
                        .when(cb.equal(incident.get("priority"), Priority.LOW), 2)
                        .otherwise(3);

        query.multiselect(
                        incident.get("id").alias("id"),
                        incident.get("status").alias("status"),
                        incident.get("priority").alias("priority"),
                        coordinates.alias("coordinates"))
                .where(spec.toPredicate(incident, query, cb))
                .orderBy(
                        cb.asc(priorityRank),
                        cb.desc(incident.get("reportedAt")),
                        cb.asc(incident.get("id")));

        return entityManager.createQuery(query).setMaxResults(limit).getResultList().stream()
                .map(IncidentPositionRepositoryImpl::toPosition)
                .toList();
    }

    private static IncidentPositionResponse toPosition(Tuple row) {
        // A JTS point keeps x = longitude, y = latitude.
        var point = row.get("coordinates", Point.class);
        return new IncidentPositionResponse(
                row.get("id", UUID.class),
                row.get("status", IncidentStatus.class),
                row.get("priority", Priority.class),
                point.getY(),
                point.getX());
    }
}
