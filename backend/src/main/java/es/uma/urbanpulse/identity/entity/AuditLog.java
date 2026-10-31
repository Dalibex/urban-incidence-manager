package es.uma.urbanpulse.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An append-only entry of the audit trail (RF18), written through {@code AuditService}.
 *
 * <p>It has no setters and Hibernate never writes changes to an {@link Immutable} entity, so the
 * application cannot update an entry. {@code @Immutable} does not stop a delete: that is the job of
 * {@code AuditLogRepository}, which has no {@code delete}, and of the database, which rejects any
 * {@code UPDATE}, {@code DELETE} or {@code TRUNCATE} on the table (migration V7).
 */
@Entity
@Table(name = "audit_log")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id", nullable = false)
    private User actor;

    @Column(nullable = false)
    private OffsetDateTime instant;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditAction action;

    @Column(name = "target_id")
    private UUID targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type")
    private AuditTargetType targetType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String details;

    public AuditLog(
            User actor,
            OffsetDateTime instant,
            AuditAction action,
            AuditTargetType targetType,
            UUID targetId,
            String details) {
        this.actor = actor;
        this.instant = instant;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.details = details;
    }
}
