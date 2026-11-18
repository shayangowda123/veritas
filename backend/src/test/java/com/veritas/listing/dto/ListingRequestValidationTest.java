package com.veritas.listing.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jakarta Bean Validation unit tests proving the schema-aligned {@code @Digits} bounds added to the
 * listing request DTOs, matching the database NUMERIC precision/scale:
 * rent & deposit -> NUMERIC(12,2) (<=10 integer digits, <=2 fraction digits);
 * builtUpArea  -> NUMERIC(10,2) (<=8 integer digits, <=2 fraction digits).
 * Also covers the Phase 3B.6C mandatory expected-version ({@code @NotNull}) on every listing
 * mutation body: update, publish and status change.
 */
class ListingRequestValidationTest {

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

    private CreateListingRequest validCreate() {
        CreateListingRequest r = new CreateListingRequest();
        r.setPropertyId(UUID.randomUUID());
        r.setRent(new BigDecimal("25000.00"));
        r.setDeposit(new BigDecimal("100000.00"));
        r.setBhk(2);
        r.setBathrooms(2);
        r.setBuiltUpArea(new BigDecimal("1100.00"));
        r.setFloor(3);
        r.setAvailableFrom(LocalDate.now().plusDays(7));
        return r;
    }

    private UpdateListingRequest validUpdate() {
        UpdateListingRequest r = new UpdateListingRequest();
        r.setRent(new BigDecimal("25000.00"));
        r.setDeposit(new BigDecimal("100000.00"));
        r.setBhk(2);
        r.setBathrooms(2);
        r.setBuiltUpArea(new BigDecimal("1100.00"));
        r.setFloor(3);
        r.setAvailableFrom(LocalDate.now().plusDays(7));
        // Phase 3B.6C: version is mandatory on every mutation body; valid fixtures must carry it.
        r.setVersion(0);
        return r;
    }

    private boolean flags(Set<ConstraintViolation<Object>> vs, String field) {
        return vs.stream().anyMatch(v -> v.getPropertyPath().toString().equals(field));
    }

    @Test
    @DisplayName("rent and deposit accept the maximum valid precision and scale (10 int digits, 2 decimals)")
    void numericMaxAccepted() {
        CreateListingRequest r = validCreate();
        r.setRent(new BigDecimal("9999999999.99"));
        r.setDeposit(new BigDecimal("9999999999.99"));
        assertFalse(flags(validator.validate(r), "rent"), "10-digit, 2-decimal rent must be valid");
        assertFalse(flags(validator.validate(r), "deposit"), "10-digit, 2-decimal deposit must be valid");
    }

    @Test
    @DisplayName("rent rejects more than 10 integer digits and more than 2 fractional digits")
    void rentRejectsOutOfRange() {
        CreateListingRequest tooManyInt = validCreate();
        tooManyInt.setRent(new BigDecimal("99999999999.00")); // 11 integer digits
        assertTrue(flags(validator.validate(tooManyInt), "rent"), "11 integer digits must be rejected");

        CreateListingRequest tooManyFrac = validCreate();
        tooManyFrac.setRent(new BigDecimal("1000.999")); // 3 fractional digits
        assertTrue(flags(validator.validate(tooManyFrac), "rent"), "3 fractional digits must be rejected");
    }

    @Test
    @DisplayName("deposit rejects more than 10 integer digits and more than 2 fractional digits")
    void depositRejectsOutOfRange() {
        CreateListingRequest tooManyInt = validCreate();
        tooManyInt.setDeposit(new BigDecimal("99999999999.00"));
        assertTrue(flags(validator.validate(tooManyInt), "deposit"), "11 integer digits must be rejected");

        CreateListingRequest tooManyFrac = validCreate();
        tooManyFrac.setDeposit(new BigDecimal("0.999"));
        assertTrue(flags(validator.validate(tooManyFrac), "deposit"), "3 fractional digits must be rejected");
    }

    @Test
    @DisplayName("builtUpArea accepts 8 int digits + 2 decimals; rejects beyond either limit")
    void builtUpAreaBounds() {
        CreateListingRequest ok = validCreate();
        ok.setBuiltUpArea(new BigDecimal("99999999.99")); // 8 int, 2 frac
        assertFalse(flags(validator.validate(ok), "builtUpArea"), "8 integer digits, 2 decimals must be valid");

        CreateListingRequest tooManyInt = validCreate();
        tooManyInt.setBuiltUpArea(new BigDecimal("999999999.00")); // 9 int
        assertTrue(flags(validator.validate(tooManyInt), "builtUpArea"), "9 integer digits must be rejected");

        CreateListingRequest tooManyFrac = validCreate();
        tooManyFrac.setBuiltUpArea(new BigDecimal("1000.999")); // 3 frac
        assertTrue(flags(validator.validate(tooManyFrac), "builtUpArea"), "3 fractional digits must be rejected");
    }

    @Test
    @DisplayName("update DTO enforces the same numeric precision rules")
    void updateDtoSameNumericRules() {
        UpdateListingRequest ok = validUpdate();
        ok.setRent(new BigDecimal("9999999999.99"));
        ok.setBuiltUpArea(new BigDecimal("99999999.99"));
        assertFalse(flags(validator.validate(ok), "rent"));
        assertFalse(flags(validator.validate(ok), "builtUpArea"));

        UpdateListingRequest badRent = validUpdate();
        badRent.setRent(new BigDecimal("99999999999.00"));
        assertTrue(flags(validator.validate(badRent), "rent"));

        UpdateListingRequest badArea = validUpdate();
        badArea.setBuiltUpArea(new BigDecimal("999999999.99"));
        assertTrue(flags(validator.validate(badArea), "builtUpArea"));
    }

    @Test
    @DisplayName("update DTO requires the expected optimistic-lock version")
    void updateRequiresVersion() {
        UpdateListingRequest missingVersion = validUpdate();
        missingVersion.setVersion(null);
        assertTrue(flags(validator.validate(missingVersion), "version"),
                "missing version on update must be rejected (HTTP 400)");

        UpdateListingRequest withVersion = validUpdate();
        assertFalse(flags(validator.validate(withVersion), "version"),
                "supplied version must satisfy the update contract");
    }

    @Test
    @DisplayName("publish DTO accepts a version and rejects its absence")
    void publishRequiresVersion() {
        PublishListingRequest missingVersion = new PublishListingRequest();
        assertTrue(flags(validator.validate(missingVersion), "version"),
                "missing version on publish must be rejected (HTTP 400)");

        PublishListingRequest withVersion = new PublishListingRequest(0);
        assertTrue(validator.validate(withVersion).isEmpty(),
                "publish body carrying the version must be fully valid");
    }

    @Test
    @DisplayName("status DTO requires both status and expected version")
    void statusRequiresVersion() {
        UpdateListingStatusRequest missingVersion = new UpdateListingStatusRequest();
        missingVersion.setStatus(com.veritas.listing.ListingStatus.RENTED);
        assertTrue(flags(validator.validate(missingVersion), "version"),
                "missing version on status change must be rejected (HTTP 400)");

        UpdateListingStatusRequest missingStatus = new UpdateListingStatusRequest();
        missingStatus.setVersion(0);
        assertTrue(flags(validator.validate(missingStatus), "status"),
                "missing status must remain rejected alongside the new version contract");

        UpdateListingStatusRequest valid = new UpdateListingStatusRequest(
                com.veritas.listing.ListingStatus.RENTED, 0);
        assertTrue(validator.validate(valid).isEmpty(),
                "status + version body must be fully valid");
    }
}
