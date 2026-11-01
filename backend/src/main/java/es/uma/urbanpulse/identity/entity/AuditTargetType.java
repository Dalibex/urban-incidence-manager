package es.uma.urbanpulse.identity.entity;

/** The kind of record an audit entry is about; {@code target_id} has no foreign key. */
public enum AuditTargetType {
    USER,
    INCIDENT
}
