package es.uma.urbanpulse.identity.entity;

/**
 * What an audit entry records (RF18). The column has no {@code CHECK}, so a new action needs no
 * migration: add it here with the feature that records it.
 *
 * <p>Never rename or remove a constant: old entries keep the name, and loading an entry with an
 * unknown name fails. The V8 trigger also stops a migration from rewriting them unless it disables
 * the trigger on purpose.
 */
public enum AuditAction {
    // User management (#40).
    CREATE_USER,
    UPDATE_USER,
    CHANGE_ROLE,
    DISABLE_USER,
    ENABLE_USER,

    // Incident classification (#47).
    CLASSIFY_INCIDENT
}
