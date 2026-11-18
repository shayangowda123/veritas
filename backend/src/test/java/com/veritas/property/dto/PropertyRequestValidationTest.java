package com.veritas.property.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jakarta Bean Validation unit tests proving the schema-aligned {@code @Size} bounds added to the
 * property request DTOs (propertyType<=50, locality<=255, city<=100), matching the database VARCHAR
 * limits. Boundary cases are checked directly against the validator (no Spring/web context needed).
 */
class PropertyRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    private CreatePropertyRequest validCreate() {
        CreatePropertyRequest r = new CreatePropertyRequest();
        r.setPropertyType("APARTMENT");
        r.setAddress("123 Main St");
        r.setLocality("Indiranagar");
        r.setCity("Bengaluru");
        r.setLatitude(12.97);
        r.setLongitude(77.59);
        return r;
    }

    private UpdatePropertyRequest validUpdate() {
        UpdatePropertyRequest r = new UpdatePropertyRequest();
        r.setPropertyType("APARTMENT");
        r.setAddress("123 Main St");
        r.setLocality("Indiranagar");
        r.setCity("Bengaluru");
        r.setLatitude(12.97);
        r.setLongitude(77.59);
        return r;
    }

    private boolean flagsField(Set<ConstraintViolation<Object>> violations, String field) {
        return violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals(field));
    }

    @Test
    @DisplayName("propertyType: 50 chars accepted, 51 rejected (create)")
    void propertyTypeBoundaryCreate() {
        CreatePropertyRequest ok = validCreate();
        ok.setPropertyType("a".repeat(50));
        assertFalse(flagsField(validator.validate(ok), "propertyType"), "50-char propertyType must be valid");

        CreatePropertyRequest bad = validCreate();
        bad.setPropertyType("a".repeat(51));
        assertTrue(flagsField(validator.validate(bad), "propertyType"), "51-char propertyType must be rejected");
    }

    @Test
    @DisplayName("locality: 255 chars accepted, 256 rejected (create); null still allowed")
    void localityBoundaryCreate() {
        CreatePropertyRequest ok = validCreate();
        ok.setLocality("a".repeat(255));
        assertFalse(flagsField(validator.validate(ok), "locality"), "255-char locality must be valid");

        CreatePropertyRequest bad = validCreate();
        bad.setLocality("a".repeat(256));
        assertTrue(flagsField(validator.validate(bad), "locality"), "256-char locality must be rejected");

        CreatePropertyRequest nullLocality = validCreate();
        nullLocality.setLocality(null);
        assertFalse(flagsField(validator.validate(nullLocality), "locality"), "locality must remain optional");
    }

    @Test
    @DisplayName("city: 100 chars accepted, 101 rejected (create)")
    void cityBoundaryCreate() {
        CreatePropertyRequest ok = validCreate();
        ok.setCity("a".repeat(100));
        assertFalse(flagsField(validator.validate(ok), "city"), "100-char city must be valid");

        CreatePropertyRequest bad = validCreate();
        bad.setCity("a".repeat(101));
        assertTrue(flagsField(validator.validate(bad), "city"), "101-char city must be rejected");
    }

    @Test
    @DisplayName("update DTO enforces the same propertyType/locality/city limits")
    void updateDtoSameStringLimits() {
        UpdatePropertyRequest ok = validUpdate();
        ok.setPropertyType("a".repeat(50));
        ok.setLocality("a".repeat(255));
        ok.setCity("a".repeat(100));
        assertTrue(validator.validate(ok).isEmpty(), "max-length valid update request must pass all constraints");

        UpdatePropertyRequest badType = validUpdate();
        badType.setPropertyType("a".repeat(51));
        assertTrue(flagsField(validator.validate(badType), "propertyType"));

        UpdatePropertyRequest badCity = validUpdate();
        badCity.setCity("a".repeat(101));
        assertTrue(flagsField(validator.validate(badCity), "city"));
    }

    @Test
    @DisplayName("existing coordinate-bound validation remains intact")
    void coordinateBoundsStillEnforced() {
        CreatePropertyRequest badLat = validCreate();
        badLat.setLatitude(95.0);
        assertTrue(flagsField(validator.validate(badLat), "latitude"), "out-of-range latitude must still be rejected");

        CreatePropertyRequest badLon = validCreate();
        badLon.setLongitude(-181.0);
        assertTrue(flagsField(validator.validate(badLon), "longitude"), "out-of-range longitude must still be rejected");
    }
}
