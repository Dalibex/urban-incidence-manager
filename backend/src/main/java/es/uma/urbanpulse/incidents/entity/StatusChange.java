package es.uma.urbanpulse.incidents.entity;

import es.uma.urbanpulse.identity.entity.User;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One entry of the status history of an incident (ADR-0020, RF18). Entries are only created by
 * {@link Incident}, after it has checked the transition, and are never changed: the constructor is
 * package-private and there are no setters (ADR-0010).
 */
@Entity
@Table(name = "status_change")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StatusChange {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incident_id", nullable = false)
    private Incident incident;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id", nullable = false)
    private User actor;

    @Column(nullable = false)
    private OffsetDateTime instant;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status")
    private IncidentStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false)
    private IncidentStatus newStatus;

    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "associated_data", columnDefinition = "jsonb")
    private String associatedData;

    @Column(name = "visible_to_citizen", nullable = false)
    private Boolean visibleToCitizen;

    /**
     * Every transition of ADR-0020 is visible to the citizen (each row of "Citizen sees" includes
     * the status); what the citizen may not see is the reason of some of them and the associated
     * data.
     *
     * @param previousStatus {@code null} for the first entry, when the incident is reported
     */
    StatusChange(
            Incident incident,
            User actor,
            OffsetDateTime instant,
            IncidentStatus previousStatus,
            IncidentStatus newStatus) {
        this.incident = incident;
        this.actor = actor;
        this.instant = instant;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.visibleToCitizen = Boolean.TRUE;
    }

    StatusChange withReason(String reason) {
        this.reason = reason;
        return this;
    }

    StatusChange withAssociatedData(String associatedData) {
        this.associatedData = associatedData;
        return this;
    }

    /** Whether the citizen sees this entry in the history of their incident. */
    public boolean isVisibleToCitizen() {
        return Boolean.TRUE.equals(visibleToCitizen);
    }

    /**
     * Whether the citizen sees the reason of this entry: only that of an information request or a
     * rejection (ADR-0020, "Citizen sees").
     */
    public boolean citizenSeesReason() {
        return IncidentTransition.between(previousStatus, newStatus)
                .map(IncidentTransition::citizenSeesReason)
                .orElse(false);
    }
}
