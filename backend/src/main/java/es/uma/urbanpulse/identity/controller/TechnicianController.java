package es.uma.urbanpulse.identity.controller;

import es.uma.urbanpulse.identity.dto.TechnicianSummary;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.repository.UserRepository;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code listTechnicians}: the technicians an incident can be assigned to (#190). It is the same
 * rule the assignment checks, an enabled {@code TECHNICIAN}, so every technician listed here is
 * accepted by {@code createIncidentAssignment}.
 */
@RestController
@RequestMapping("/api/v1/technicians")
@PreAuthorize("hasAnyRole('MUNICIPAL_OPERATOR', 'ADMINISTRATOR')")
class TechnicianController {

    private final UserRepository users;

    TechnicianController(UserRepository users) {
        this.users = users;
    }

    @GetMapping
    List<TechnicianSummary> list() {
        return users.findByRoleAndEnabledTrueOrderByNameAscIdAsc(Role.TECHNICIAN).stream()
                .map(TechnicianSummary::from)
                .toList();
    }
}
