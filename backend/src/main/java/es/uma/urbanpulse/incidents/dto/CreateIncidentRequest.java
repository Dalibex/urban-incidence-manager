package es.uma.urbanpulse.incidents.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
            @NotNull @Min(-90) @Max(90) Double latitude,
            @NotNull @Min(-180) @Max(180) Double longitude,
            @Min(0) Double accuracy) {}
}
