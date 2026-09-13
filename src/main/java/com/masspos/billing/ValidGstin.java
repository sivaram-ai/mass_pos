package com.masspos.billing;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** Format and check character of a GSTIN. Null or blank passes; combine with @NotBlank where required. */
@Documented
@Constraint(validatedBy = ValidGstin.Validator.class)
@Target({FIELD, METHOD, PARAMETER})
@Retention(RUNTIME)
public @interface ValidGstin {

    String message() default "is not a valid GSTIN (wrong format or check character)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidGstin, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.isBlank() || Gstin.isValid(value);
        }
    }
}
