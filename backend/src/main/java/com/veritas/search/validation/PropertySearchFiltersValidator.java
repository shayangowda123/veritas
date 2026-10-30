package com.veritas.search.validation;

import com.veritas.search.dto.PropertySearchRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.math.BigDecimal;

/**
 * Validates the cross-field rules for {@link PropertySearchRequest}. Each violation is attached to
 * the specific property involved so it is reported through the standard field-error channel.
 */
public class PropertySearchFiltersValidator
        implements ConstraintValidator<ValidPropertySearchFilters, PropertySearchRequest> {

    @Override
    public boolean isValid(PropertySearchRequest request, ConstraintValidatorContext context) {
        if (request == null) {
            return true;
        }

        boolean hasLatitude = request.getLatitude() != null;
        boolean hasLongitude = request.getLongitude() != null;
        boolean hasCentre = hasLatitude && hasLongitude;
        boolean valid = true;

        // Latitude and longitude must be provided together (or omitted together).
        if (hasLatitude != hasLongitude) {
            valid = false;
            String missingField = hasLatitude ? "longitude" : "latitude";
            addViolation(context, missingField,
                    "latitude and longitude must both be provided or both be omitted");
        }

        // Radius is only meaningful when a search centre is present.
        if (request.getRadiusInMeters() != null && !hasCentre) {
            valid = false;
            addViolation(context, "radiusInMeters",
                    "radiusInMeters requires both latitude and longitude");
        }

        // Distance ordering requires a search centre.
        if ("distance".equals(request.getSortBy()) && !hasCentre) {
            valid = false;
            addViolation(context, "sortBy",
                    "sorting by distance requires latitude and longitude");
        }

        // Minimum rent must not exceed maximum rent.
        BigDecimal minRent = request.getMinRent();
        BigDecimal maxRent = request.getMaxRent();
        if (minRent != null && maxRent != null && minRent.compareTo(maxRent) > 0) {
            valid = false;
            addViolation(context, "maxRent", "minRent must not exceed maxRent");
        }

        return valid;
    }

    private void addViolation(ConstraintValidatorContext context, String field, String message) {
        // Disable the default (class-level) violation and report against the concrete field instead.
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message)
                .addPropertyNode(field)
                .addConstraintViolation();
    }
}
