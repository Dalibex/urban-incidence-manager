package es.uma.urbanpulse.incidents.entity;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.shared.web.FieldError;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An incident and its lifecycle (ADR-0020). The status only changes through {@link #report} and
 * {@link #changeStatus}, which check the transition table and write the history entry; there are no
 * setters, so no code can change the status or the history without that check.
 *
 * <p>The history ({@link StatusChange}) and the assignments ({@link Assignment}) are created only
 * here and saved with the incident (cascade), so they are written in the same transaction as the
 * status.
 */
@Entity
@Table(name = "incident")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Incident {

    // associated_data of an information request (ADR-0020), so the inbox can tell it apart.
    private static final String INFORMATION_REQUESTED = "{\"action\": \"INFORMATION_REQUESTED\"}";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "priority_setter_id")
    private User prioritySetter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IncidentStatus status;

    @Enumerated(EnumType.STRING)
    private Priority priority;

    @Column(name = "priority_reason")
    private String priorityReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority_source")
    private PrioritySource prioritySource;

    @Version
    @Column(nullable = false)
    private Integer version;

    @Column(name = "reported_at", nullable = false)
    private OffsetDateTime reportedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Getter(AccessLevel.NONE)
    @OneToMany(mappedBy = "incident", cascade = CascadeType.PERSIST)
    @OrderBy("instant")
    private List<StatusChange> statusChanges = new ArrayList<>();

    @Getter(AccessLevel.NONE)
    @OneToMany(mappedBy = "incident", cascade = CascadeType.PERSIST)
    private List<Assignment> assignments = new ArrayList<>();

    /**
     * A citizen reports an incident: it starts in {@code REPORTED} with its first history entry,
     * which has no previous status (ADR-0020, row "— → REPORTED"). The location is a separate
     * entity, saved by the caller in the same transaction.
     *
     * @param category optional: {@code null} when the citizen does not choose one
     * @throws InvalidTransitionException if the reporter is not a citizen
     */
    public static Incident report(
            User reporter, String title, String description, Category category, Instant now) {
        Objects.requireNonNull(reporter, "reporter");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
        if (reporter.getRole() != Role.CITIZEN) {
            throw InvalidTransitionException.reportOnlyByCitizens();
        }
        var incident = new Incident();
        incident.reporter = reporter;
        incident.title = title;
        incident.description = description;
        incident.category = category;
        incident.status = IncidentStatus.REPORTED;
        incident.reportedAt = utc(now);
        incident.updatedAt = incident.reportedAt;
        incident.statusChanges.add(
                new StatusChange(
                        incident, reporter, incident.reportedAt, null, IncidentStatus.REPORTED));
        return incident;
    }

    /**
     * Applies a transition of the table: checks the current status, the actor and the required
     * data, then changes the status and adds exactly one history entry. Nothing changes if a check
     * fails.
     *
     * @return the new history entry
     * @throws InvalidTransitionException if the table does not allow it from the current status or
     *     for this actor
     * @throws MissingTransitionDataException if data the transition needs is missing
     */
    public StatusChange changeStatus(
            IncidentTransition transition, User actor, TransitionDetails details, Instant now) {
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(details, "details");
        if (!transition.startsFrom(status)) {
            throw InvalidTransitionException.fromStatus(status, transition.target());
        }
        if (!mayPerform(transition, actor)) {
            throw InvalidTransitionException.notAllowedFor(transition);
        }
        requireData(transition, details);

        var instant = utc(now);
        var change =
                new StatusChange(this, actor, instant, status, transition.target())
                        .withReason(details.reason())
                        .withAssociatedData(associatedData(transition, details));
        if (transition == IncidentTransition.ASSIGNMENT) {
            assignments.stream().filter(Assignment::isActive).forEach(Assignment::deactivate);
            assignments.add(
                    new Assignment(this, details.department(), details.technician(), instant));
        }
        if (transition == IncidentTransition.CLOSURE) {
            closedAt = instant;
        }
        status = transition.target();
        updatedAt = instant;
        statusChanges.add(change);
        return change;
    }

    /** Sets or changes the category. Validation needs it (ADR-0020). */
    public void classify(Category category, Instant now) {
        this.category = Objects.requireNonNull(category, "category");
        updatedAt = utc(now);
    }

    /**
     * Sets the priority with its justification and the origin of the decision, which are always
     * kept together (AGENTS.md, section 3). Validation needs it (ADR-0020).
     */
    public void prioritize(
            Priority priority, String reason, PrioritySource source, User setter, Instant now) {
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(setter, "setter");
        if (isBlank(reason)) {
            throw new IllegalArgumentException("A priority needs its justification.");
        }
        this.priority = priority;
        this.priorityReason = reason;
        this.prioritySource = source;
        this.prioritySetter = setter;
        updatedAt = utc(now);
    }

    /** The history, oldest first. Read-only: entries are only added by the transitions. */
    public List<StatusChange> getStatusChanges() {
        return Collections.unmodifiableList(statusChanges);
    }

    /** Every assignment, the active one included. Read-only: the transitions manage them. */
    public List<Assignment> getAssignments() {
        return Collections.unmodifiableList(assignments);
    }

    /** Whether the user is the technician of the active assignment (ADR-0020). */
    public boolean isAssignedTo(User technician) {
        return assignments.stream()
                .filter(Assignment::isActive)
                .map(Assignment::getTechnician)
                .anyMatch(assigned -> sameUser(assigned, technician));
    }

    private boolean mayPerform(IncidentTransition transition, User actor) {
        if (!transition.performer().hasRole(actor.getRole())) {
            return false;
        }
        return transition.performer() != IncidentTransition.Performer.ASSIGNED_TECHNICIAN
                || isAssignedTo(actor);
    }

    private void requireData(IncidentTransition transition, TransitionDetails details) {
        var missing = new ArrayList<FieldError>();
        if (transition.requiresReason() && isBlank(details.reason())) {
            missing.add(new FieldError("reason", "This change needs a reason."));
        }
        if (transition == IncidentTransition.ASSIGNMENT && details.department() == null) {
            missing.add(new FieldError("departmentId", "An assignment needs a department."));
        }
        if (transition == IncidentTransition.VALIDATION) {
            if (category == null) {
                missing.add(
                        new FieldError("category", "Classify the incident before validating it."));
            }
            if (priority == null) {
                missing.add(
                        new FieldError(
                                "priority", "Set a priority before validating the incident."));
            }
        }
        if (!missing.isEmpty()) {
            throw new MissingTransitionDataException(missing);
        }
    }

    /**
     * Internal data of the transition, never shown to citizens (ADR-0020). Built by hand because it
     * only holds a fixed action name and UUIDs, which need no escaping.
     */
    private static String associatedData(IncidentTransition transition, TransitionDetails details) {
        return switch (transition) {
            case INFORMATION_REQUEST -> INFORMATION_REQUESTED;
            case ASSIGNMENT ->
                    details.technician() == null
                            ? "{\"departmentId\": \"%s\"}".formatted(details.department().getId())
                            : "{\"departmentId\": \"%s\", \"technicianId\": \"%s\"}"
                                    .formatted(
                                            details.department().getId(),
                                            details.technician().getId());
            default -> null;
        };
    }

    private static boolean sameUser(User one, User other) {
        return one != null && one.getId() != null && one.getId().equals(other.getId());
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(Objects.requireNonNull(instant, "now"), ZoneOffset.UTC);
    }
}
