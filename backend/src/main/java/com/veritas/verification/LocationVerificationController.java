package com.veritas.verification;

import com.veritas.security.UserPrincipal;
import com.veritas.user.UserRole;
import com.veritas.verification.dto.LocationVerificationResponse;
import com.veritas.verification.dto.VerifyLocationRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class LocationVerificationController {

    private final LocationVerificationService verificationService;

    public LocationVerificationController(LocationVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    /**
     * Dedicated authorized workflow for recording location verification decisions.
     * Accessible exclusively to authorized reviewers/admins.
     * Property owners cannot verify their own properties.
     */
    @PostMapping("/api/v1/admin/properties/{propertyId}/verify-location")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<LocationVerificationResponse> verifyLocation(
            @PathVariable UUID propertyId,
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody VerifyLocationRequest request
    ) {
        LocationVerificationResponse response = verificationService.recordVerificationDecision(
                propertyId,
                principal.getId(),
                request
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Read verification audit details for a property.
     * Accessible to the property owner and admins.
     */
    @GetMapping("/api/v1/properties/{propertyId}/verification")
    public ResponseEntity<LocationVerificationResponse> getVerification(
            @PathVariable UUID propertyId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        UUID currentUserId = principal != null ? principal.getId() : null;
        boolean isAdmin = principal != null && principal.getRole() == UserRole.ADMIN;

        LocationVerificationResponse response = verificationService.getVerificationDetails(
                propertyId,
                currentUserId,
                isAdmin
        );
        return ResponseEntity.ok(response);
    }
}
