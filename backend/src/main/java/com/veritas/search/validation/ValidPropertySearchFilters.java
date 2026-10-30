package com.veritas.search.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level constraint enforcing cross-field rules for {@code PropertySearchRequest} that cannot
 * be expressed with a single-field annotation: coordinate pairing, radius dependency, rent ordering
 * and the requirement that distance sorting needs a search centre.
 *
 * <p>Violations are reported against the offending field so they flow through the existing
 * field-to-message {@code errors} response contract.</p>
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PropertySearchFiltersValidator.class)
public @interface ValidPropertySearchFilters {

    String message() default "Invalid property search filter combination";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
