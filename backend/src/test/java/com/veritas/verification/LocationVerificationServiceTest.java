package com.veritas.verification;

import com.veritas.common.exception.UnauthorizedActionException;
import com.veritas.property.Property;
import com.veritas.property.PropertyRepository;
import com.veritas.user.User;
import com.veritas.verification.dto.LocationVerificationResponse;
import com.veritas.verification.dto.VerifyLocationRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LocationVerificationServiceTest {

    @Mock
    private PropertyRepository propertyRepository;

    @InjectMocks
    private LocationVerificationService verificationService;

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private User owner;
    private Property property;
    private UUID ownerId;
    private UUID propertyId;
    private UUID adminVerifierId;

    @BeforeEach
    void setUp() {
        ownerId = UUID.randomUUID();
        propertyId = UUID.randomUUID();
        adminVerifierId = UUID.randomUUID();

        owner = new User();
        owner.setId(ownerId);
        owner.setName("Property Owner");

        property = new Property();
        property.setId(propertyId);
        property.setOwner(owner);
        property.setLocationVerificationStatus(LocationVerificationStatus.PENDING);
    }

    // â”€â”€ Timestamp semantics â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @DisplayName("Authorized admin reviewer can record VERIFIED location decision")
    void shouldRecordVerificationDecisionSuccessfully() {
        VerifyLocationRequest request = new VerifyLocationRequest(
                LocationVerificationStatus.VERIFIED,
                VerificationMethod.UTILITY_BILL,
                "Utility bill matched registered address"
        );

        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(propertyRepository.save(any(Property.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LocationVerificationResponse response = verificationService.recordVerificationDecision(
                propertyId,
                adminVerifierId,
                request
        );

        assertNotNull(response);
        assertEquals(LocationVerificationStatus.VERIFIED, response.getStatus());
        assertEquals("UTILITY_BILL", response.getVerificationMethod());
        assertEquals("Utility bill matched registered address", response.getDecisionNotes());
        assertNotNull(response.getVerifiedAt(), "verifiedAt must be set for VERIFIED status");
    }

    @Test
    @DisplayName("REJECTED decision sets verifiedAt to null")
    void shouldClearVerifiedAtForRejectedDecision() {
        VerifyLocationRequest request = new VerifyLocationRequest(
                LocationVerificationStatus.REJECTED,
                VerificationMethod.OTHER,
                "Documents appear fraudulent"
        );

        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(propertyRepository.save(any(Property.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LocationVerificationResponse response = verificationService.recordVerificationDecision(
                propertyId,
                adminVerifierId,
                request
        );

        assertEquals(LocationVerificationStatus.REJECTED, response.getStatus());
        assertNull(response.getVerifiedAt(), "verifiedAt must be null for REJECTED status");
    }

    @Test
    @DisplayName("PENDING decision sets verifiedAt to null")
    void shouldClearVerifiedAtForPendingDecision() {
        VerifyLocationRequest request = new VerifyLocationRequest(
                LocationVerificationStatus.PENDING,
                VerificationMethod.OTHER,
                null
        );

        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(propertyRepository.save(any(Property.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LocationVerificationResponse response = verificationService.recordVerificationDecision(
                propertyId,
                adminVerifierId,
                request
        );

        assertEquals(LocationVerificationStatus.PENDING, response.getStatus());
        assertNull(response.getVerifiedAt(), "verifiedAt must be null for PENDING status");
    }

    @Test
    @DisplayName("REVIEW_REQUIRED decision sets verifiedAt to null")
    void shouldClearVerifiedAtForReviewRequiredDecision() {
        VerifyLocationRequest request = new VerifyLocationRequest(
                LocationVerificationStatus.REVIEW_REQUIRED,
                VerificationMethod.OTHER,
                "Awaiting site visit confirmation"
        );

        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(propertyRepository.save(any(Property.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LocationVerificationResponse response = verificationService.recordVerificationDecision(
                propertyId,
                adminVerifierId,
                request
        );

        assertEquals(LocationVerificationStatus.REVIEW_REQUIRED, response.getStatus());
        assertNull(response.getVerifiedAt(), "verifiedAt must be null for REVIEW_REQUIRED status");
    }

    // â”€â”€ Authorization â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @DisplayName("Owner cannot verify their own property (anti-self-verification check)")
    void shouldPreventOwnerSelfVerification() {
        VerifyLocationRequest request = new VerifyLocationRequest(
                LocationVerificationStatus.VERIFIED,
                VerificationMethod.UTILITY_BILL,
                "I own this place"
        );

        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));

        assertThrows(UnauthorizedActionException.class, () ->
                verificationService.recordVerificationDecision(propertyId, ownerId, request)
        );
        verify(propertyRepository, never()).save(any());
    }

    @Test
    @DisplayName("Verification audit details are restricted to owner or admin")
    void shouldDenyAuditDetailsToUnauthorizedRenter() {
        UUID strangerId = UUID.randomUUID();
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));

        assertThrows(UnauthorizedActionException.class, () ->
                verificationService.getVerificationDetails(propertyId, strangerId, false)
        );
    }

    @Test
    @DisplayName("Property owner can retrieve their own verification details")
    void shouldAllowOwnerToRetrieveVerificationDetails() {
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));

        LocationVerificationResponse response = verificationService.getVerificationDetails(
                propertyId, ownerId, false
        );

        assertNotNull(response);
        assertEquals(propertyId, response.getPropertyId());
    }

    @Test
    @DisplayName("Admin can retrieve verification details for any property")
    void shouldAllowAdminToRetrieveVerificationDetails() {
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));

        LocationVerificationResponse response = verificationService.getVerificationDetails(
                propertyId, adminVerifierId, true
        );

        assertNotNull(response);
        assertEquals(propertyId, response.getPropertyId());
    }

    // â”€â”€ Bean Validation (VerifyLocationRequest) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @DisplayName("Request with null verification status fails validation")
    void shouldRejectNullStatus() {
        VerifyLocationRequest request = new VerifyLocationRequest(
                null,
                VerificationMethod.UTILITY_BILL,
                "Some notes"
        );

        Set<ConstraintViolation<VerifyLocationRequest>> violations = validator.validate(request);

        assertFalse(violations.isEmpty(), "Null status should produce validation errors");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("status")));
    }

    @Test
    @DisplayName("Request with null verification method fails validation")
    void shouldRejectNullVerificationMethod() {
        VerifyLocationRequest request = new VerifyLocationRequest(
                LocationVerificationStatus.VERIFIED,
                null,
                "Some notes"
        );

        Set<ConstraintViolation<VerifyLocationRequest>> violations = validator.validate(request);

        assertFalse(violations.isEmpty(), "Null verification method should produce validation errors");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("verificationMethod")));
    }

    @Test
    @DisplayName("Request with excessively long decision notes fails validation")
    void shouldRejectExcessiveDecisionNotes() {
        String longNotes = "x".repeat(1001);
        VerifyLocationRequest request = new VerifyLocationRequest(
                LocationVerificationStatus.VERIFIED,
                VerificationMethod.UTILITY_BILL,
                longNotes
        );

        Set<ConstraintViolation<VerifyLocationRequest>> violations = validator.validate(request);

        assertFalse(violations.isEmpty(), "Excessively long notes should produce validation errors");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("decisionNotes")));
    }

    @Test
    @DisplayName("Valid request passes all validation constraints")
    void shouldPassValidationForValidRequest() {
        VerifyLocationRequest request = new VerifyLocationRequest(
                LocationVerificationStatus.VERIFIED,
                VerificationMethod.SITE_VISIT,
                "Confirmed via site visit"
        );

        Set<ConstraintViolation<VerifyLocationRequest>> violations = validator.validate(request);

        assertTrue(violations.isEmpty(), "Valid request should have no constraint violations");
    }
}
