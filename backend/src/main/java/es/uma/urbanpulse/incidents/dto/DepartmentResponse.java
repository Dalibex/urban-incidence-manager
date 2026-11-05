package es.uma.urbanpulse.incidents.dto;

import es.uma.urbanpulse.incidents.entity.Department;
import java.util.UUID;

/** {@code DepartmentResponse}: a department an incident can be assigned to. */
public record DepartmentResponse(UUID id, String name) {

    public static DepartmentResponse of(Department department) {
        return new DepartmentResponse(department.getId(), department.getName());
    }
}
