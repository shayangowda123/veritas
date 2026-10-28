package com.veritas.verification;

import com.veritas.common.exception.ResourceNotFoundException;
import com.veritas.common.exception.UnauthorizedActionException;
import com.veritas.property.Property;
import com.veritas.property.PropertyRepository;
import com.veritas.verification.dto.LocationVerificationResponse;
import com.veritas.verification.dto.VerifyLocationRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class LocationVerificationService {

    private final PropertyRepository propertyRepository;

    public LocationVerificationService(PropertyRepository propertyRepository) {
        this.propertyRepository = propertyRepository;
    }

    /**
     * Authorized workflow for recording location verification decisions.
     * Prevents self-verification even if an admin owns the property.
     *
     * <p><strong>Evidence-verification limitation:</strong> This method records the admin's
     * verification decision and the type of evidence they claim to have reviewed, but the
     * current architecture does not independently validate or fetch that evidence. The
     * decision is an admin attestation stored for audit purposes. A future integration
     * with external verification services (e.g., government registries, notarized document
     * APIs) would be required to cryptographically or independently confirm location
     * authenticity.</p>
     */
    @Transactional
    public LocationVerificationResponse recordVerificationDecision(
            UUID propertyId,
            UUID verifierUserId,
            VerifyLocationRequest request
    ) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new ResourceNotFoundException("Property not found with id: " + propertyId));

        if (property.getOwner().getId().equals(verifierUserId)) {
            throw new UnauthorizedActionException("Conflict of interest: A property owner cannot verify their own property");
        }

        property.setLocationVerificationStatus(request.getStatus());

        // verifiedAt is set only when the decision genuinely reaches VERIFIED.
        // For all other statuses (PENDING, REVIEW_REQUIRED, REJECTED), verifiedAt is cleared.
        if (request.getStatus() == LocationVerificationStatus.VERIFIED) {
            property.setVerifiedAt(OffsetDateTime.now());
        } else {
            property.setVerifiedAt(null);
        }

        // Store the enum constant name; fits the VARCHAR(50) column constraint.
        property.setVerificationMethod(request.getVerificationMethod().name());
        property.setVerificationNotes(request.getDecisionNotes() != null ? request.getDecisionNotes().trim() : null);

        Property saved = propertyRepository.save(property);

        return new LocationVerificationResponse(
                saved.getId(),
                saved.getLocationVerificationStatus(),
                saved.getVerifiedAt(),
                saved.getVerificationMethod(),
                saved.getVerificationNotes()
        );
    }

    @Transactional(readOnly = true)
    public LocationVerificationResponse getVerificationDetails(
            UUID propertyId,
            UUID currentUserId,
            boolean isAdmin
    ) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new ResourceNotFoundException("Property not found with id: " + propertyId));

        boolean isOwner = currentUserId != null && property.getOwner().getId().equals(currentUserId);
        if (!isOwner && !isAdmin) {
            throw new UnauthorizedActionException("Access denied: Verification audit details are restricted to owner or authorized reviewers");
        }

        return new LocationVerificationResponse(
                property.getId(),
                property.getLocationVerificationStatus(),
                property.getVerifiedAt(),
                property.getVerificationMethod(),
                property.getVerificationNotes()
        );
    }
}
