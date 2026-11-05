package es.uma.urbanpulse.incidents.controller;

import es.uma.urbanpulse.incidents.dto.DepartmentResponse;
import es.uma.urbanpulse.incidents.repository.DepartmentRepository;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code listDepartments}: the departments incidents can be assigned to, for the assignment form
 * and the staff filters (#184).
 */
@RestController
@RequestMapping("/api/v1/departments")
@PreAuthorize("hasAnyRole('MUNICIPAL_OPERATOR', 'TECHNICIAN', 'ADMINISTRATOR')")
class DepartmentController {

    private final DepartmentRepository departments;

    DepartmentController(DepartmentRepository departments) {
        this.departments = departments;
    }

    @GetMapping
    List<DepartmentResponse> list() {
        return departments.findByIsActiveTrueOrderByNameAsc().stream()
                .map(DepartmentResponse::of)
                .toList();
    }
}
