package com.veritas.property.dto;

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
public class PropertySummaryResponse {

    private UUID id;
    private String propertyType;
    private String address;
    private String locality;
    private String city;
    private Double latitude;
    private Double longitude;
    private LocationVerificationStatus locationVerificationStatus;
    private int imageCount;
    private OffsetDateTime createdAt;
}
