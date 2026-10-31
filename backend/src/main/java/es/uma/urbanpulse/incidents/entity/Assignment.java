package es.uma.urbanpulse.incidents.entity;

import es.uma.urbanpulse.identity.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import lombok.Setter;

/**
 * An assignment of an incident to a department and, optionally, one of its technicians. Created
 * only by the {@code ASSIGNMENT} transition of {@link Incident}, which deactivates the previous one
 * in the same change (ADR-0020); the constructor is package-private (ADR-0010).
 */
@Entity
@Table(name = "assignment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Assignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incident_id", nullable = false)
    private Incident incident;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "technician_id")
    private User technician;

    // Free text, not part of the lifecycle rules (ADR-0020 only needs department and technician).
    @Setter private String team;

    @Column(name = "assigned_at", nullable = false)
    private OffsetDateTime assignedAt;

    @Getter(AccessLevel.NONE)
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = Boolean.TRUE;

    /**
     * @param technician {@code null} when the incident is assigned to the department only
     */
    Assignment(
            Incident incident, Department department, User technician, OffsetDateTime assignedAt) {
        this.incident = incident;
        this.department = department;
        this.technician = technician;
        this.assignedAt = assignedAt;
    }

    public boolean isActive() {
        return isActive;
    }

    void deactivate() {
        isActive = Boolean.FALSE;
    }
}
