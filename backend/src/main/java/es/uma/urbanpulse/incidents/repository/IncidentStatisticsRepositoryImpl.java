package es.uma.urbanpulse.incidents.repository;

import es.uma.urbanpulse.incidents.dto.IncidentStatisticsResponse.CategoryCount;
import es.uma.urbanpulse.incidents.dto.IncidentStatisticsResponse.DepartmentCount;
import es.uma.urbanpulse.incidents.entity.Incident;
import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.Priority;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.JoinType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Spring Data picks it up by its name as the implementation of {@link
 * IncidentStatisticsRepository}.
 */
class IncidentStatisticsRepositoryImpl implements IncidentStatisticsRepository {

    private final EntityManager entityManager;

    IncidentStatisticsRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Map<IncidentStatus, Long> countByStatus(Specification<Incident> spec) {
        return countByAttribute(spec, "status", IncidentStatus.class);
    }

    @Override
    public Map<Priority, Long> countByPriority(Specification<Incident> spec) {
        return countByAttribute(spec, "priority", Priority.class);
    }

    @Override
    public List<CategoryCount> countByCategory(Specification<Incident> spec) {
        var cb = entityManager.getCriteriaBuilder();
        var query = cb.createTupleQuery();
        var incident = query.from(Incident.class);
        // Left, so the incidents without category are counted as the group null.
        var category = incident.join("category", JoinType.LEFT);
        var id = category.get("id");
        var name = category.get("name");
        query.multiselect(id.alias("id"), name.alias("name"), cb.count(incident).alias("count"))
                .where(spec.toPredicate(incident, query, cb))
                .groupBy(id, name);
        return entityManager.createQuery(query).getResultList().stream()
                .map(
                        row ->
                                new CategoryCount(
                                        row.get("id", UUID.class),
                                        row.get("name", String.class),
                                        row.get("count", Long.class)))
                .toList();
    }

    @Override
    public List<DepartmentCount> countByDepartment(Specification<Incident> spec) {
        var cb = entityManager.getCriteriaBuilder();
        var query = cb.createTupleQuery();
        var incident = query.from(Incident.class);
        // The domain keeps at most one active assignment per incident (Incident.changeStatus), so
        // the join does not repeat an incident. The condition is in the ON clause, not in the
        // WHERE, so an incident without an active assignment still counts as the group null.
        var assignment = incident.join("assignments", JoinType.LEFT);
        assignment.on(cb.isTrue(assignment.get("isActive")));
        var department = assignment.join("department", JoinType.LEFT);
        var id = department.get("id");
        var name = department.get("name");
        query.multiselect(id.alias("id"), name.alias("name"), cb.count(incident).alias("count"))
                .where(spec.toPredicate(incident, query, cb))
                .groupBy(id, name);
        return entityManager.createQuery(query).getResultList().stream()
                .map(
                        row ->
                                new DepartmentCount(
                                        row.get("id", UUID.class),
                                        row.get("name", String.class),
                                        row.get("count", Long.class)))
                .toList();
    }

    private <T> Map<T, Long> countByAttribute(
            Specification<Incident> spec, String attribute, Class<T> type) {
        var cb = entityManager.getCriteriaBuilder();
        var query = cb.createTupleQuery();
        var incident = query.from(Incident.class);
        query.multiselect(incident.get(attribute).alias("group"), cb.count(incident).alias("count"))
                .where(spec.toPredicate(incident, query, cb))
                .groupBy(incident.get(attribute));
        // A HashMap, not Map.of: the group of the incidents without priority has a null key.
        var counts = new HashMap<T, Long>();
        for (Tuple row : entityManager.createQuery(query).getResultList()) {
            counts.put(row.get("group", type), row.get("count", Long.class));
        }
        return counts;
    }
}
