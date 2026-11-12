package es.uma.urbanpulse.incidents.entity;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.shared.error.FieldError;
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
import jakarta.persistence.OneToOne;
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
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
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

    // maxLength of the reason in the contract (IncidentActionRequest, IncidentReasonRequest).
    static final int MAX_REASON_LENGTH = 2000;

    /** maxLength of {@code priorityReason} in the contract ({@code UpdateIncidentRequest}). */
    public static final int MAX_PRIORITY_REASON_LENGTH = 2000;

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

    @OneToOne(mappedBy = "incident", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
    private Location location;

    /**
     * A citizen reports an incident: it starts in {@code REPORTED} with its first history entry,
     * which has no previous status (ADR-0020, row "— → REPORTED"). The location is a separate
     * entity, saved by the caller in the same transaction.
     *
     * @param category optional: {@code null} when the citizen does not choose one
     * @throws InvalidTransitionException if the reporter is not a citizen
     */
    public static Incident report(
            User reporter,
            String title,
            String description,
            Category category,
            Location location,
            Instant now) {
        Objects.requireNonNull(reporter, "reporter");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(location, "location");
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
        incident.location = location;
        location.setIncident(incident);
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
     * @throws InvalidIncidentDataException if data the transition needs is missing or not valid
     */
    public StatusChange changeStatus(
            IncidentTransition transition, User actor, TransitionDetails details, Instant now) {
        Objects.requireNonNull(details, "details");
        return changeStatus(transition, actor, () -> details, now);
    }

    /**
     * The same, for a transition whose data the caller has to look up first, such as the department
     * and the technician of an assignment. {@code details} runs only after the status and the actor
     * have been checked, so a transition that is not allowed is refused before its data, in the
     * order of ADR-0020.
     *
     * @param details looks up the data; it may throw {@link InvalidIncidentDataException}
     */
    public StatusChange changeStatus(
            IncidentTransition transition,
            User actor,
            Supplier<TransitionDetails> details,
            Instant now) {
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(details, "details");
        checkAllowed(transition, actor);
        return apply(transition, actor, Objects.requireNonNull(details.get(), "details"), now);
    }

    private StatusChange apply(
            IncidentTransition transition, User actor, TransitionDetails details, Instant now) {
        requireData(transition, details);

        var instant = utc(now);
        var change =
                new StatusChange(this, actor, instant, status, transition.target())
                        .withReason(details.reason())
                        .withAssociatedData(associatedData(transition, details));
        if (transition == IncidentTransition.ASSIGNMENT) {
            assignments.stream().filter(Assignment::isActive).forEach(Assignment::deactivate);
            assignments.add(
                    new Assignment(
                            this,
                            details.department(),
                            details.technician(),
                            details.team(),
                            instant));
        }
        if (transition == IncidentTransition.CLOSURE) {
            closedAt = instant;
        }
        status = transition.target();
        updatedAt = instant;
        statusChanges.add(change);
        return change;
    }

    /**
     * Checks that the table allows the transition from the current status and for this actor,
     * without changing anything. {@link #changeStatus} runs it first; a caller that refuses the
     * request for another reason uses it to keep the order of the errors of ADR-0020, where a
     * transition that is not allowed comes before invalid data.
     *
     * @throws InvalidTransitionException if the table does not allow it from the current status or
     *     for this actor
     */
    public void checkAllowed(IncidentTransition transition, User actor) {
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(actor, "actor");
        if (!transition.startsFrom(status)) {
            throw InvalidTransitionException.fromStatus(status, transition.target());
        }
        if (!mayPerform(transition, actor)) {
            throw InvalidTransitionException.notAllowedFor(transition);
        }
    }

    /**
     * Whether the category and the priority may still change: not on a {@code REJECTED} or {@code
     * CLOSED} incident (ADR-0020, "Classification is not a transition").
     *
     * @throws ClassificationNotAllowedException if they may not
     */
    public void checkClassifiable() {
        if (status == IncidentStatus.REJECTED || status == IncidentStatus.CLOSED) {
            throw new ClassificationNotAllowedException(status);
        }
    }

    /** Sets or changes the category. Validation needs it (ADR-0020). */
    public void classify(Category category, Instant now) {
        this.category = Objects.requireNonNull(category, "category");
        updatedAt = utc(now);
    }

    /**
     * Sets the priority with its justification and the origin of the decision, which are always
     * kept together (AGENTS.md, section 3). Validation needs it (ADR-0020).
     *
     * @throws InvalidIncidentDataException if the justification is blank or too long
     */
    public void prioritize(
            Priority priority, String reason, PrioritySource source, User setter, Instant now) {
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(setter, "setter");
        var invalid = priorityErrors(priority, reason);
        if (!invalid.isEmpty()) {
            throw new InvalidIncidentDataException(invalid);
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

    /** The active assignment; empty while the incident has none. There is never more than one. */
    public Optional<Assignment> activeAssignment() {
        return assignments.stream().filter(Assignment::isActive).findFirst();
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
        var invalid = new ArrayList<FieldError>();
        var reason = details.reason();
        if (isBlank(reason)) {
            if (transition.requiresReason()) {
                invalid.add(new FieldError("reason", "This change needs a reason."));
            } else if (reason != null) {
                invalid.add(new FieldError("reason", "Write the reason, or leave it out."));
            }
        } else if (reason.codePointCount(0, reason.length()) > MAX_REASON_LENGTH) {
            // Code points, as JSON Schema counts maxLength: an emoji is one character.
            invalid.add(
                    new FieldError(
                            "reason",
                            "The reason can have at most " + MAX_REASON_LENGTH + " characters."));
        }
        if (transition == IncidentTransition.ASSIGNMENT) {
            invalid.addAll(assignmentErrors(details));
        }
        if (transition == IncidentTransition.VALIDATION) {
            if (category == null) {
                invalid.add(
                        new FieldError("category", "Classify the incident before validating it."));
            }
            if (priority == null) {
                invalid.add(
                        new FieldError(
                                "priority", "Set a priority before validating the incident."));
            }
        }
        if (!invalid.isEmpty()) {
            throw new InvalidIncidentDataException(invalid);
        }
    }

    /**
     * What is wrong with the data of an assignment, in the order of the fields of {@code
     * CreateAssignmentRequest}; empty when it is valid. A service that finds an id pointing to
     * nothing uses it to report that error together with the rest.
     *
     * @param details the department is {@code null} when it is missing, the technician when the
     *     incident is assigned to the department only
     */
    public static List<FieldError> assignmentErrors(TransitionDetails details) {
        var invalid = new ArrayList<FieldError>();
        if (details.department() == null) {
            invalid.add(new FieldError("departmentId", "An assignment needs a department."));
        } else if (!Boolean.TRUE.equals(details.department().getIsActive())) {
            invalid.add(new FieldError("departmentId", "The department is not active."));
        }
        // Only an active technician can accept the incident later (ADR-0020).
        var technician = details.technician();
        if (technician != null
                && (technician.getRole() != Role.TECHNICIAN
                        || !Boolean.TRUE.equals(technician.getEnabled()))) {
            invalid.add(new FieldError("technicianId", "Choose an active technician."));
        }
        // Counted in characters (code points), like maxLength in the contract, not in UTF-16 units.
        var team = details.team();
        if (team != null
                && (onlyWhiteSpace(team)
                        || team.codePointCount(0, team.length()) > Assignment.TEAM_MAX_LENGTH)) {
            invalid.add(
                    new FieldError(
                            "team",
                            "The team needs between 1 and "
                                    + Assignment.TEAM_MAX_LENGTH
                                    + " characters."));
        }
        return invalid;
    }

    /**
     * What is wrong with a priority and its justification, as {@code UpdateIncidentRequest} sends
     * them; empty when they are valid. The justification travels only with a priority, and a
     * priority needs one with a visible character ({@code pattern: "\\S"}, with the white space of
     * the contract) and at most {@link #MAX_PRIORITY_REASON_LENGTH} characters. A service that
     * checks the rest of the request uses it to report every invalid field at once.
     *
     * @param priority {@code null} when the request keeps the current one
     * @param reason {@code null} when the request does not send it
     */
    public static List<FieldError> priorityErrors(Priority priority, String reason) {
        if (priority == null) {
            return reason == null
                    ? List.of()
                    : List.of(
                            new FieldError(
                                    "priorityReason",
                                    "Send the justification together with a priority."));
        }
        if (reason == null || onlyWhiteSpace(reason)) {
            return List.of(new FieldError("priorityReason", "A priority needs its justification."));
        }
        // Code points, as JSON Schema counts maxLength: an emoji is one character.
        if (reason.codePointCount(0, reason.length()) > MAX_PRIORITY_REASON_LENGTH) {
            return List.of(
                    new FieldError(
                            "priorityReason",
                            "The justification can have up to "
                                    + MAX_PRIORITY_REASON_LENGTH
                                    + " characters."));
        }
        return List.of();
    }

    /**
     * Internal data of the transition, never shown to citizens (ADR-0020). Built by hand: it holds
     * a fixed action name, UUIDs and the team, the only free text, which is escaped.
     */
    private static String associatedData(IncidentTransition transition, TransitionDetails details) {
        return switch (transition) {
            case INFORMATION_REQUEST -> INFORMATION_REQUESTED;
            case ASSIGNMENT -> {
                var data =
                        new StringBuilder("{\"departmentId\": \"")
                                .append(details.department().getId())
                                .append('"');
                if (details.technician() != null) {
                    data.append(", \"technicianId\": \"")
                            .append(details.technician().getId())
                            .append('"');
                }
                if (details.team() != null) {
                    data.append(", \"team\": ").append(jsonString(details.team()));
                }
                yield data.append('}').toString();
            }
            default -> null;
        };
    }

    /** The text as a JSON string: quotes, backslashes and control characters escaped. */
    private static String jsonString(String text) {
        var json = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') {
                json.append('\\').append(c);
            } else if (c < 0x20) {
                json.append("\\u%04x".formatted((int) c));
            } else {
                json.append(c);
            }
        }
        return json.append('"').toString();
    }

    private static boolean sameUser(User one, User other) {
        return one != null && one.getId() != null && one.getId().equals(other.getId());
    }

    /**
     * Whether a reason or a justification is missing: {@code null}, or nothing that the {@code
     * pattern: "\\S"} of the contract accepts, so a text of no-break spaces is blank too.
     */
    private static boolean isBlank(String text) {
        return text == null || onlyWhiteSpace(text);
    }

    /**
     * Whether the text has no character that the {@code pattern: "\\S"} of the contract accepts.
     * JSON Schema patterns use the {@code \s} of ECMA-262, which also counts no-break spaces such
     * as U+00A0 and U+FEFF; {@link String#isBlank} does not.
     */
    private static boolean onlyWhiteSpace(String text) {
        return text.codePoints()
                .allMatch(
                        c ->
                                Character.getType(c) == Character.SPACE_SEPARATOR
                                        || (c >= '\t' && c <= '\r')
                                        || c == 0x2028
                                        || c == 0x2029
                                        || c == 0xFEFF);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(Objects.requireNonNull(instant, "now"), ZoneOffset.UTC);
    }
}
