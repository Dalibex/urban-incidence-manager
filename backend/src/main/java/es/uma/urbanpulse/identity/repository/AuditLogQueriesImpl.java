package es.uma.urbanpulse.identity.repository;

import es.uma.urbanpulse.identity.entity.AuditAction;
import es.uma.urbanpulse.identity.entity.AuditLog;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.query.QueryUtils;
import org.springframework.data.support.PageableExecutionUtils;

/** The Criteria API implementation of {@link AuditLogQueries}; it only reads. */
class AuditLogQueriesImpl implements AuditLogQueries {

    private final EntityManager entityManager;

    AuditLogQueriesImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Page<AuditLog> findMatching(
            Instant from, Instant to, UUID actorId, Set<AuditAction> actions, Pageable page) {
        var cb = entityManager.getCriteriaBuilder();

        var query = cb.createQuery(AuditLog.class);
        var entry = query.from(AuditLog.class);
        // The fetch join loads the actors with the page, so their names cost no query per row.
        entry.fetch("actor");
        query.select(entry)
                .where(conditions(cb, entry, from, to, actorId, actions))
                .orderBy(QueryUtils.toOrders(page.getSort(), entry, cb));
        var content =
                entityManager
                        .createQuery(query)
                        .setFirstResult(Math.toIntExact(page.getOffset()))
                        .setMaxResults(page.getPageSize())
                        .getResultList();

        // Counted apart and without the fetch join, which a count query does not allow.
        var countQuery = cb.createQuery(Long.class);
        var counted = countQuery.from(AuditLog.class);
        countQuery
                .select(cb.count(counted))
                .where(conditions(cb, counted, from, to, actorId, actions));
        return PageableExecutionUtils.getPage(
                content, page, () -> entityManager.createQuery(countQuery).getSingleResult());
    }

    private static Predicate[] conditions(
            CriteriaBuilder cb,
            Root<AuditLog> entry,
            Instant from,
            Instant to,
            UUID actorId,
            Set<AuditAction> actions) {
        var conditions = new ArrayList<Predicate>();
        if (from != null) {
            conditions.add(
                    cb.greaterThanOrEqualTo(entry.get("instant"), from.atOffset(ZoneOffset.UTC)));
        }
        if (to != null) {
            conditions.add(cb.lessThan(entry.get("instant"), to.atOffset(ZoneOffset.UTC)));
        }
        if (actorId != null) {
            conditions.add(cb.equal(entry.get("actor").get("id"), actorId));
        }
        if (actions != null && !actions.isEmpty()) {
            conditions.add(entry.get("action").in(actions));
        }
        return conditions.toArray(Predicate[]::new);
    }
}
