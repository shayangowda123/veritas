package com.veritas.property.dto;

import com.veritas.verification.LocationVerificationStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PropertyResponse {

    private UUID id;
    private UUID ownerId;
    private String ownerName;
    private String propertyType;
    private String address;
    private String locality;
    private String city;
    private Double latitude;
    private Double longitude;
    private LocationVerificationStatus locationVerificationStatus;
    private OffsetDateTime verifiedAt;
    private String verificationNotes;
    private String verificationMethod;
    private List<PropertyImageResponse> images;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
