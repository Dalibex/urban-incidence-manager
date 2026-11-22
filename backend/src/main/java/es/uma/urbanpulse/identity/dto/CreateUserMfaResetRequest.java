package es.uma.urbanpulse.identity.dto;

import es.uma.urbanpulse.shared.text.Text;
import jakarta.validation.constraints.NotNull;

/** Body of {@code createUserMfaReset}: why the second factor is reset, for the audit trail. */
public record CreateUserMfaResetRequest(@NotNull @Text(max = 500) String reason) {}
