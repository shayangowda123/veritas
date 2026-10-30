package com.veritas.search.validation;

import com.veritas.search.dto.PropertySearchRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real Bean Validation provider against {@link PropertySearchRequest} so both the
 * field-level rules and the class-level cross-field rules are validated exactly as the web layer
 * will apply them. Violations are asserted to be attached to the offending property path so they
 * surface through the shared field-to-message {@code errors} contract.
 */
class PropertySearchFiltersValidatorTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    private Set<String> fields(PropertySearchRequest request) {
        return validator.validate(request).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("a fully defaulted request with no filters is valid")
    void bareRequestValid() {
        assertTrue(validator.validate(new PropertySearchRequest()).isEmpty());
    }

    @Test
    @DisplayName("latitude without longitude is rejected on the missing field")
    void latitudeRequiresLongitude() {
        PropertySearchRequest request = new PropertySearchRequest();
        request.setLatitude(12.97);
        assertTrue(fields(request).contains("longitude"));
    }

    @Test
    @DisplayName("longitude without latitude is rejected on the missing field")
    void longitudeRequiresLatitude() {
        PropertySearchRequest request = new PropertySearchRequest();
        request.setLongitude(77.59);
        assertTrue(fields(request).contains("latitude"));
    }

    @Test
    @DisplayName("out-of-range latitude and longitude are rejected")
    void coordinateRanges() {
        PropertySearchRequest request = new PropertySearchRequest();
        request.setLatitude(95.0);
        request.setLongitude(-200.0);
        Set<String> violated = fields(request);
        assertTrue(violated.contains("latitude"));
        assertTrue(violated.contains("longitude"));
    }

    @Test
    @DisplayName("radius requires a search centre")
    void radiusRequiresCentre() {
        PropertySearchRequest request = new PropertySearchRequest();
        request.setRadiusInMeters(3000.0);
        assertTrue(fields(request).contains("radiusInMeters"));
    }

    @Test
    @DisplayName("radius above the 50km cap is rejected")
    void radiusCapped() {
        PropertySearchRequest request = new PropertySearchRequest();
        request.setLatitude(12.97);
        request.setLongitude(77.59);
        request.setRadiusInMeters(50001.0);
        assertTrue(fields(request).contains("radiusInMeters"));
    }

    @Test
    @DisplayName("non-positive radius is rejected")
    void radiusMustBePositive() {
        PropertySearchRequest request = new PropertySearchRequest();
        request.setLatitude(12.97);
        request.setLongitude(77.59);
        request.setRadiusInMeters(0.0);
        assertTrue(fields(request).contains("radiusInMeters"));
    }

    @Test
    @DisplayName("minimum rent greater than maximum rent is rejected on maxRent")
    void minRentExceedsMaxRent() {
        PropertySearchRequest request = new PropertySearchRequest();
        request.setMinRent(new BigDecimal("50000"));
        request.setMaxRent(new BigDecimal("10000"));
        assertTrue(fields(request).contains("maxRent"));
    }

    @Test
    @DisplayName("negative rent bounds are rejected")
    void negativeRentRejected() {
        PropertySearchRequest request = new PropertySearchRequest();
        request.setMinRent(new BigDecimal("-1"));
        assertTrue(fields(request).contains("minRent"));
    }

    @Test
    @DisplayName("distance sorting without a centre is rejected on sortBy")
    void distanceSortRequiresCentre() {
        PropertySearchRequest request = new PropertySearchRequest();
        request.setSortBy("distance");
        assertTrue(fields(request).contains("sortBy"));
    }

    @Test
    @DisplayName("page size must be within 1..50")
    void pageSizeBounds() {
        PropertySearchRequest tooLarge = new PropertySearchRequest();
        tooLarge.setSize(100);
        assertTrue(fields(tooLarge).contains("size"));

        PropertySearchRequest zero = new PropertySearchRequest();
        zero.setSize(0);
        assertTrue(fields(zero).contains("size"));
    }

    @Test
    @DisplayName("unsupported sort field and direction are rejected")
    void sortWhitelistEnforced() {
        PropertySearchRequest badField = new PropertySearchRequest();
        badField.setSortBy("ownerId");
        assertTrue(fields(badField).contains("sortBy"));

        PropertySearchRequest badDirection = new PropertySearchRequest();
        badDirection.setSortDirection("sideways");
        assertTrue(fields(badDirection).contains("sortDirection"));
    }

    @Test
    @DisplayName("a valid coordinate + radius + distance-sort combination reports no violations")
    void validSpatialCombination() {
        PropertySearchRequest request = new PropertySearchRequest();
        request.setLatitude(12.97);
        request.setLongitude(77.59);
        request.setRadiusInMeters(3000.0);
        request.setSortBy("distance");
        request.setSortDirection("asc");
        assertEquals(0, validator.validate(request).size());
    }
}
