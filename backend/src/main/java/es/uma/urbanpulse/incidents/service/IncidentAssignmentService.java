package es.uma.urbanpulse.incidents.service;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.identity.repository.UserRepository;
import es.uma.urbanpulse.incidents.dto.CreateAssignmentRequest;
import es.uma.urbanpulse.incidents.dto.IncidentResponse;
import es.uma.urbanpulse.incidents.entity.Department;
import es.uma.urbanpulse.incidents.entity.Incident;
import es.uma.urbanpulse.incidents.entity.IncidentTransition;
import es.uma.urbanpulse.incidents.entity.InvalidIncidentDataException;
import es.uma.urbanpulse.incidents.entity.TransitionDetails;
import es.uma.urbanpulse.incidents.repository.DepartmentRepository;
import es.uma.urbanpulse.shared.error.FieldError;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assigns an incident to a department and, optionally, a technician and a team (#184, ADR-0020). It
 * is the {@code ASSIGNMENT} transition of {@link IncidentLifecycleService}: the previous active
 * assignment is deactivated, the new one created and the status changed to {@code ASSIGNED} in one
 * transaction. This class only turns the ids of the request into the department and the technician.
 */
@Service
@Transactional
public class IncidentAssignmentService {

    // The fields of CreateAssignmentRequest, in the order its errors are listed.
    private static final List<String> FIELDS = List.of("departmentId", "technicianId", "team");

    private final IncidentLifecycleService lifecycle;
    private final DepartmentRepository departments;
    private final UserRepository users;

    public IncidentAssignmentService(
            IncidentLifecycleService lifecycle,
            DepartmentRepository departments,
            UserRepository users) {
        this.lifecycle = lifecycle;
        this.departments = departments;
        this.users = users;
    }

    /**
     * @return the incident after the change, with its new {@code version}
     * @throws IncidentNotFoundException if it does not exist or the caller may not see it
     * @throws org.springframework.orm.ObjectOptimisticLockingFailureException if the incident
     *     changed since the caller read it
     * @throws es.uma.urbanpulse.incidents.entity.InvalidTransitionException if the incident is not
     *     in a status it can be assigned from
     * @throws InvalidIncidentDataException if the department is missing, unknown or inactive, the
     *     technician is unknown or not an active technician, or the team is blank or too long
     */
    public IncidentResponse assign(
            UUID incidentId, CreateAssignmentRequest request, UUID userId, Role role) {
        return lifecycle.changeStatus(
                incidentId,
                request.version(),
                IncidentTransition.ASSIGNMENT,
                () -> details(request),
                userId,
                role);
    }

    /**
     * Looks up the department and the technician. An id that points to nothing is refused here,
     * together with whatever else is wrong in the request, so the {@code 422} lists every invalid
     * field at once. The rest (a missing or inactive department, a user who is not an active
     * technician, the team) is checked by the domain with {@link Incident#assignmentErrors}.
     */
    private TransitionDetails details(CreateAssignmentRequest request) {
        var unknown = new LinkedHashMap<String, FieldError>();
        Department department = null;
        if (request.departmentId() != null) {
            department = departments.findById(request.departmentId()).orElse(null);
            if (department == null) {
                unknown.put(
                        "departmentId",
                        new FieldError("departmentId", "The department does not exist."));
            }
        }
        User technician = null;
        if (request.technicianId() != null) {
            technician = users.findById(request.technicianId()).orElse(null);
            if (technician == null) {
                // The same message as a user who is not a technician: the operator only needs to
                // choose another one, and the API does not reveal which user ids exist.
                unknown.put(
                        "technicianId",
                        new FieldError("technicianId", "Choose an active technician."));
            }
        }
        var details = TransitionDetails.assignment(department, technician, request.team());
        if (!unknown.isEmpty()) {
            var invalid = new ArrayList<>(unknown.values());
            // An unknown department reads as a missing one to the domain: its error is replaced.
            Incident.assignmentErrors(details).stream()
                    .filter(error -> !unknown.containsKey(error.field()))
                    .forEach(invalid::add);
            invalid.sort(Comparator.comparingInt(error -> FIELDS.indexOf(error.field())));
            throw InvalidIncidentDataException.unknownReferences(invalid);
        }
        return details;
    }
}
