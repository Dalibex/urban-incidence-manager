package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.identity.entity.AuditAction;
import es.uma.urbanpulse.identity.entity.AuditLog;
import es.uma.urbanpulse.identity.repository.AuditLogRepository;
import es.uma.urbanpulse.identity.repository.UserRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Appends entries to the audit trail (RF18); rules in {@code docs/security.md}. */
@Service
public class AuditService {

    private final AuditLogRepository auditLogs;
    private final UserRepository users;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    AuditService(
            AuditLogRepository auditLogs,
            UserRepository users,
            JsonMapper jsonMapper,
            Clock clock) {
        this.auditLogs = auditLogs;
        this.users = users;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /**
     * Records that {@code actorId} did {@code action} on {@code target}, with the values the change
     * replaced and the ones it set; pass an empty map for a side that does not exist, such as the
     * {@code before} of a creation. A field that had no value is recorded with a {@code null} value
     * ({@code "category": null}), not left out; {@code Map.of} rejects nulls, so use a {@code
     * HashMap} then.
     *
     * <p>It joins the caller's transaction and refuses to run without one ({@code MANDATORY}), so
     * the entry is committed or rolled back with the change it records. {@code REQUIRED} would open
     * its own transaction when the caller forgot one, and the entry could outlive a failed change.
     *
     * <p>Values can only be scalars (text, numbers, booleans, enums, UUIDs), so an entity with its
     * hash or email cannot end up in an entry that can never be deleted. Keep them to identifiers
     * and domain values (a role, a priority): never a password, a token or personal data the entry
     * does not need, such as an email.
     *
     * <p>{@code actorId} should come from the authenticated user: an unknown id only fails when the
     * transaction flushes, through the foreign key.
     *
     * @throws IllegalArgumentException if a value is not a scalar
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
            UUID actorId,
            AuditAction action,
            AuditTarget target,
            Map<String, ?> before,
            Map<String, ?> after) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(target, "target");

        var details = new LinkedHashMap<String, Object>();
        details.put("before", scalarsOnly(Objects.requireNonNull(before, "before")));
        details.put("after", scalarsOnly(Objects.requireNonNull(after, "after")));

        auditLogs.save(
                new AuditLog(
                        // A reference, not a SELECT: the foreign key already checks the actor.
                        users.getReferenceById(actorId),
                        OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
                        action,
                        target.type(),
                        target.id(),
                        jsonMapper.writeValueAsString(details)));
    }

    private static Map<String, ?> scalarsOnly(Map<String, ?> values) {
        values.forEach(
                (field, value) -> {
                    if (!isScalar(value)) {
                        throw new IllegalArgumentException(
                                "Audit value of '%s' must be a scalar, not a %s"
                                        .formatted(field, value.getClass().getSimpleName()));
                    }
                });
        return values;
    }

    private static boolean isScalar(Object value) {
        return value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Enum<?>
                || value instanceof UUID;
    }
}
