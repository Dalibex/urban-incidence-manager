package es.uma.urbanpulse.incidents.service;

import es.uma.urbanpulse.identity.entity.AuditAction;
import es.uma.urbanpulse.identity.entity.AuditLog;
import es.uma.urbanpulse.identity.entity.AuditTargetType;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.repository.AuditLogRepository;
import es.uma.urbanpulse.identity.repository.UserRepository;
import es.uma.urbanpulse.identity.service.AuditService;
import es.uma.urbanpulse.identity.service.AuditTarget;
import es.uma.urbanpulse.incidents.dto.CategorySummary;
import es.uma.urbanpulse.incidents.dto.ClassificationChangeResponse;
import es.uma.urbanpulse.incidents.dto.IncidentResponse;
import es.uma.urbanpulse.incidents.dto.UpdateIncidentRequest;
import es.uma.urbanpulse.incidents.entity.Category;
import es.uma.urbanpulse.incidents.entity.ClassificationNotAllowedException;
import es.uma.urbanpulse.incidents.entity.Incident;
import es.uma.urbanpulse.incidents.entity.InvalidIncidentDataException;
import es.uma.urbanpulse.incidents.entity.Priority;
import es.uma.urbanpulse.incidents.entity.PrioritySource;
import es.uma.urbanpulse.incidents.repository.CategoryRepository;
import es.uma.urbanpulse.incidents.repository.IncidentRepository;
import es.uma.urbanpulse.shared.error.FieldError;
import es.uma.urbanpulse.shared.web.PageResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Classifies an incident: sets or corrects its category and its priority (#47, RF10). It is not a
 * lifecycle action, so it adds no history entry (ADR-0020): each change records a {@code
 * CLASSIFY_INCIDENT} audit entry with the values it replaced and the ones it set, in the same
 * transaction, and the classification changes of an incident are read back from those entries, with
 * no second copy.
 */
@Service
@Transactional
public class IncidentClassificationService {

    // The keys of the audit details, and of ClassificationValues in the response.
    private static final String CATEGORY_ID = "categoryId";
    private static final String CATEGORY = "category";
    private static final String PRIORITY = "priority";
    private static final String PRIORITY_REASON = "priorityReason";

    private final IncidentRepository incidents;
    private final CategoryRepository categories;
    private final UserRepository users;
    private final AuditService audit;
    private final AuditLogRepository auditLogs;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    IncidentClassificationService(
            IncidentRepository incidents,
            CategoryRepository categories,
            UserRepository users,
            AuditService audit,
            AuditLogRepository auditLogs,
            JsonMapper jsonMapper,
            Clock clock) {
        this.incidents = incidents;
        this.categories = categories;
        this.users = users;
        this.audit = audit;
        this.auditLogs = auditLogs;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /**
     * Applies the category and the priority of the request, checked in the order of the lifecycle
     * actions (ADR-0020): the incident, its {@code version}, its status, then the data. Only the
     * values that change are recorded; a request that changes nothing leaves the incident, its
     * {@code version} and the audit trail as they were.
     *
     * @return the incident after the change, with its new {@code version}
     * @throws IncidentNotFoundException if it does not exist or the caller may not see it
     * @throws ObjectOptimisticLockingFailureException if the incident changed since the caller read
     *     it, now or while this change was being saved
     * @throws ClassificationNotAllowedException if the incident is {@code REJECTED} or {@code
     *     CLOSED}
     * @throws InvalidIncidentDataException if the {@code version} is missing, nothing is sent, the
     *     category is unknown or inactive, or the justification is missing, too long or sent
     *     without a priority; every invalid field at once
     */
    public IncidentResponse classify(
            UUID incidentId, UpdateIncidentRequest request, UUID userId, Role role) {
        var incident = visibleIncident(incidentId, userId, role);
        if (request.version() == null || request.version() < 0) {
            // Without a version there is no conflict to detect: like any other invalid data, it is
            // a 422 only if the incident can still be classified (ADR-0020).
            incident.checkClassifiable();
            throw InvalidIncidentDataException.invalidVersion();
        }
        // Checked before the status: a stale caller must reload, even if the change would still be
        // allowed.
        if (incident.getVersion().intValue() != request.version().intValue()) {
            throw new ObjectOptimisticLockingFailureException(Incident.class, incidentId);
        }
        incident.checkClassifiable();
        var category = checkedCategory(request);

        var before = new LinkedHashMap<String, Object>();
        var after = new LinkedHashMap<String, Object>();
        var now = clock.instant();
        if (category != null && !category.getId().equals(idOf(incident.getCategory()))) {
            before.put(CATEGORY_ID, idOf(incident.getCategory()));
            after.put(CATEGORY_ID, category.getId());
            incident.classify(category, now);
        }
        var priority = request.priority();
        if (priority != null) {
            boolean priorityChanges = priority != incident.getPriority();
            boolean reasonChanges = !request.priorityReason().equals(incident.getPriorityReason());
            if (priorityChanges) {
                before.put(PRIORITY, incident.getPriority());
                after.put(PRIORITY, priority);
            }
            if (reasonChanges) {
                before.put(PRIORITY_REASON, incident.getPriorityReason());
                after.put(PRIORITY_REASON, request.priorityReason());
            }
            if (priorityChanges || reasonChanges) {
                incident.prioritize(
                        priority,
                        request.priorityReason(),
                        PrioritySource.MANUAL,
                        users.getReferenceById(userId),
                        now);
            }
        }
        if (!after.isEmpty()) {
            audit.record(
                    userId,
                    AuditAction.CLASSIFY_INCIDENT,
                    AuditTarget.incident(incidentId),
                    before,
                    after);
            // Writes now, so a change committed by someone else after the read above fails here,
            // and the response carries the new version.
            incidents.flush();
        }
        return IncidentResponse.of(incident, role);
    }

    /**
     * The classification changes of the incident, from its {@code CLASSIFY_INCIDENT} audit entries.
     * The names of their categories are read in one query for the whole page.
     *
     * @throws IncidentNotFoundException if it does not exist or the caller may not see it
     */
    @Transactional(readOnly = true)
    public PageResponse<ClassificationChangeResponse> changes(
            UUID incidentId, UUID userId, Role role, Pageable page) {
        visibleIncident(incidentId, userId, role);
        var entries =
                auditLogs.findByActionAndTargetTypeAndTargetId(
                        AuditAction.CLASSIFY_INCIDENT, AuditTargetType.INCIDENT, incidentId, page);
        var details =
                entries.getContent().stream()
                        .collect(
                                Collectors.toMap(
                                        AuditLog::getId,
                                        entry -> jsonMapper.readTree(entry.getDetails())));
        var categoryIds = new HashSet<UUID>();
        details.values()
                .forEach(
                        detail ->
                                List.of(detail.path("before"), detail.path("after"))
                                        .forEach(
                                                side -> {
                                                    var id = side.get(CATEGORY_ID);
                                                    if (id != null && !id.isNull()) {
                                                        categoryIds.add(
                                                                UUID.fromString(id.asString()));
                                                    }
                                                }));
        Map<UUID, Category> names =
                categories.findAllById(categoryIds).stream()
                        .collect(Collectors.toMap(Category::getId, Function.identity()));

        return PageResponse.of(
                entries,
                entry -> {
                    var detail = details.get(entry.getId());
                    return new ClassificationChangeResponse(
                            entry.getId(),
                            entry.getInstant().toInstant(),
                            new ClassificationChangeResponse.AuditActor(
                                    entry.getActor().getId(), entry.getActor().getName()),
                            values(detail.path("before"), names),
                            values(detail.path("after"), names));
                });
    }

    /**
     * The active category of the request, or {@code null} when it sends none. Every invalid field
     * of the request is reported here, at once.
     */
    private Category checkedCategory(UpdateIncidentRequest request) {
        var invalid = new ArrayList<FieldError>();
        Category category = null;
        if (request.categoryId() == null && request.priority() == null) {
            invalid.add(new FieldError(CATEGORY_ID, "Send a category, a priority or both."));
        }
        if (request.categoryId() != null) {
            category =
                    categories
                            .findById(request.categoryId())
                            .filter(found -> Boolean.TRUE.equals(found.getIsActive()))
                            .orElse(null);
            if (category == null) {
                invalid.add(
                        new FieldError(
                                CATEGORY_ID, "The category does not exist or is not active."));
            }
        }
        invalid.addAll(Incident.priorityErrors(request.priority(), request.priorityReason()));
        if (!invalid.isEmpty()) {
            throw InvalidIncidentDataException.of(invalid);
        }
        return category;
    }

    /** {@code ClassificationValues}: one side of an entry, with the category by its name. */
    private static Map<String, Object> values(JsonNode side, Map<UUID, Category> categoriesById) {
        var values = new LinkedHashMap<String, Object>();
        if (side.has(CATEGORY_ID)) {
            var id = side.get(CATEGORY_ID);
            values.put(
                    CATEGORY,
                    id.isNull() ? null : summary(UUID.fromString(id.asString()), categoriesById));
        }
        if (side.has(PRIORITY)) {
            var priority = side.get(PRIORITY);
            values.put(PRIORITY, priority.isNull() ? null : Priority.valueOf(priority.asString()));
        }
        if (side.has(PRIORITY_REASON)) {
            var reason = side.get(PRIORITY_REASON);
            values.put(PRIORITY_REASON, reason.isNull() ? null : reason.asString());
        }
        return values;
    }

    private static CategorySummary summary(UUID id, Map<UUID, Category> categoriesById) {
        // Categories are deactivated, never deleted, so every id of the trail is still there.
        var category =
                Objects.requireNonNull(
                        categoriesById.get(id), () -> "Category " + id + " of the audit trail");
        return new CategorySummary(category.getId(), category.getName());
    }

    private Incident visibleIncident(UUID incidentId, UUID userId, Role role) {
        return incidents
                .findOne(
                        IncidentReadAccess.visibleTo(userId, role)
                                .and(IncidentReadAccess.withId(incidentId)))
                .orElseThrow(() -> new IncidentNotFoundException(incidentId));
    }

    private static UUID idOf(Category category) {
        return category == null ? null : category.getId();
    }
}
