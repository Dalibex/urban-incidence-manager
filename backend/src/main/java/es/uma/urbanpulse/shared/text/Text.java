package es.uma.urbanpulse.shared.text;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A text field of the contract: no null character, between {@code min} and {@code max} characters
 * counted in code points, and, when {@code visible}, at least one character that the {@code
 * pattern: "\\S"} of the contract accepts. A missing value is left to {@code @NotNull}. It replaces
 * {@code @NotBlank} and {@code @Size}, which accept a text of no-break spaces and count UTF-16
 * units.
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = TextValidator.class)
public @interface Text {

    /** The {@code minLength} of the contract, in characters. */
    int min() default 1;

    /** The {@code maxLength} of the contract, in characters. */
    int max() default Integer.MAX_VALUE;

    /** Whether the field has the {@code pattern: "\\S"} of the contract. */
    boolean visible() default true;

    /** Unused: the validator names the rule that failed. */
    String message() default "must be a valid text";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
