package es.uma.urbanpulse.incidents.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateIncidentRequest(
        @NotBlank @Size(min = 1, max = 120) String title,
        @NotBlank @Size(min = 1, max = 2000) String description,
        UUID categoryId,
        @NotNull @Valid ReportedLocation location) {

    public record ReportedLocation(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @DecimalMin("0") Double accuracy) {}
}
