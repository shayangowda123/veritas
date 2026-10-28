package com.veritas.verification.dto;

import com.veritas.verification.LocationVerificationStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LocationVerificationResponse {

    private UUID propertyId;
    private LocationVerificationStatus status;
    private OffsetDateTime verifiedAt;
    private String verificationMethod;
    private String decisionNotes;
}
