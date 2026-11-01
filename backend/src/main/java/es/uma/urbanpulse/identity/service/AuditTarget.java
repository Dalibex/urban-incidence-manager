package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.identity.entity.AuditTargetType;
import java.util.Objects;
import java.util.UUID;

/** The record an audit entry is about. */
public record AuditTarget(AuditTargetType type, UUID id) {

    public AuditTarget {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
    }

    public static AuditTarget user(UUID userId) {
        return new AuditTarget(AuditTargetType.USER, userId);
    }

    public static AuditTarget incident(UUID incidentId) {
        return new AuditTarget(AuditTargetType.INCIDENT, incidentId);
    }
}
