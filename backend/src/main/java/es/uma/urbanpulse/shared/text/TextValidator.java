package es.uma.urbanpulse.shared.text;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Checks {@link Text} and names the first rule that fails: the null character, a blank text, then
 * the length. A missing value is left to {@code @NotNull}.
 */
public class TextValidator implements ConstraintValidator<Text, String> {

    private int min;
    private int max;
    private boolean visible;

    @Override
    public void initialize(Text constraint) {
        min = constraint.min();
        max = constraint.max();
        visible = constraint.visible();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        var problem = problem(value);
        if (problem == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(problem).addConstraintViolation();
        return false;
    }

    private String problem(String value) {
        if (Texts.hasNullCharacter(value)) {
            return "must not contain the null character";
        }
        if (visible && Texts.onlyWhiteSpace(value)) {
            return "must not be blank";
        }
        var length = Texts.length(value);
        if (length < min) {
            return "must be at least " + min + " characters";
        }
        if (length > max) {
            return "must be at most " + max + " characters";
        }
        return null;
    }
}
