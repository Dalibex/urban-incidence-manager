package es.uma.urbanpulse.identity.dto;

import static java.nio.charset.StandardCharsets.UTF_8;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Checks {@link MaxUtf8Bytes}. A missing value is left to {@code @NotNull}. */
public class MaxUtf8BytesValidator implements ConstraintValidator<MaxUtf8Bytes, String> {

    private int maxBytes;

    @Override
    public void initialize(MaxUtf8Bytes constraint) {
        maxBytes = constraint.value();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.getBytes(UTF_8).length <= maxBytes;
    }
}
