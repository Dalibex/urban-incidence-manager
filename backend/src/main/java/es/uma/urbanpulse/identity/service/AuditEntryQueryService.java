package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.identity.dto.AuditEntryResponse;
import es.uma.urbanpulse.identity.entity.AuditLog;
import es.uma.urbanpulse.identity.entity.AuditTargetType;
import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.identity.repository.AuditLogRepository;
import es.uma.urbanpulse.identity.repository.UserRepository;
import es.uma.urbanpulse.incidents.repository.IncidentRepository;
import es.uma.urbanpulse.shared.web.PageResponse;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the audit trail for the audit log screen (#41, RF18). It only reads: entries are written by
 * {@link AuditService}. The controller checks that the caller is an administrator.
 */
@Service
@Transactional(readOnly = true)
public class AuditEntryQueryService {

    private final AuditLogRepository auditLogs;
    private final UserRepository users;
    private final IncidentRepository incidents;
    private final JsonMapper jsonMapper;

    AuditEntryQueryService(
            AuditLogRepository auditLogs,
            UserRepository users,
            IncidentRepository incidents,
            JsonMapper jsonMapper) {
        this.auditLogs = auditLogs;
        this.users = users;
        this.incidents = incidents;
        this.jsonMapper = jsonMapper;
    }

    /**
     * One page of the entries that match {@code filter}. The actors' names and the targets' labels
     * are the current ones, read with the page: one query for the page with its actors, and one per
     * kind of target, whatever the number of rows.
     */
    public PageResponse<AuditEntryResponse> list(AuditEntryFilter filter, Pageable page) {
        var entries =
                auditLogs.findMatching(
                        filter.from(), filter.to(), filter.actorId(), filter.actions(), page);
        var labels = labelsOf(entries.getContent());
        return PageResponse.of(entries, entry -> toResponse(entry, labels));
    }

    private AuditEntryResponse toResponse(
            AuditLog entry, Map<AuditTargetType, Map<UUID, String>> labels) {
        var actor = entry.getActor();
        return new AuditEntryResponse(
                entry.getId(),
                entry.getInstant().toInstant(),
                new AuditEntryResponse.Actor(actor.getId(), actor.getName()),
                entry.getAction(),
                targetOf(entry, labels),
                detailsOf(entry));
    }

    private static AuditEntryResponse.Target targetOf(
            AuditLog entry, Map<AuditTargetType, Map<UUID, String>> labels) {
        if (entry.getTargetType() == null || entry.getTargetId() == null) {
            return null;
        }
        var label = labels.getOrDefault(entry.getTargetType(), Map.of()).get(entry.getTargetId());
        return new AuditEntryResponse.Target(entry.getTargetType(), entry.getTargetId(), label);
    }

    /** The labels of the targets of {@code entries}, by kind and id; a missing record has none. */
    private Map<AuditTargetType, Map<UUID, String>> labelsOf(List<AuditLog> entries) {
        var ids = new EnumMap<AuditTargetType, Set<UUID>>(AuditTargetType.class);
        for (var entry : entries) {
            if (entry.getTargetType() != null && entry.getTargetId() != null) {
                ids.computeIfAbsent(entry.getTargetType(), type -> new HashSet<>())
                        .add(entry.getTargetId());
            }
        }
        var labels = new EnumMap<AuditTargetType, Map<UUID, String>>(AuditTargetType.class);
        ids.forEach((type, targetIds) -> labels.put(type, labelsOf(type, targetIds)));
        return labels;
    }

    private Map<UUID, String> labelsOf(AuditTargetType type, Set<UUID> ids) {
        return switch (type) {
            case USER ->
                    users.findAllById(ids).stream()
                            .collect(Collectors.toMap(User::getId, User::getName));
            case INCIDENT ->
                    incidents.findTitlesByIdIn(ids).stream()
                            .collect(
                                    Collectors.toMap(
                                            IncidentRepository.Title::getId,
                                            IncidentRepository.Title::getTitle));
        };
    }

    /**
     * The stored {@code {"before": {...}, "after": {...}}}. {@code AuditService} always writes both
     * sides; a missing one reads as empty, the same as a side that does not exist.
     */
    private AuditEntryResponse.Details detailsOf(AuditLog entry) {
        if (entry.getDetails() == null) {
            return null;
        }
        var stored = jsonMapper.readValue(entry.getDetails(), StoredDetails.class);
        return new AuditEntryResponse.Details(stored.before(), stored.after());
    }

    private record StoredDetails(Map<String, Object> before, Map<String, Object> after) {}
}
