package com.veritas.listing.dto;

import com.veritas.listing.ListingStatus;
import com.veritas.property.FurnishingStatus;
import com.veritas.property.dto.PropertySummaryResponse;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ListingResponse {

    private UUID id;
    private UUID propertyId;
    private PropertySummaryResponse property;
    private BigDecimal rent;
    private BigDecimal deposit;
    private Integer bhk;
    private Integer bathrooms;
    private BigDecimal builtUpArea;
    private Integer floor;
    private FurnishingStatus furnishingStatus;
    private boolean bachelorsAllowed;
    private boolean parkingAvailable;
    private boolean petsAllowed;
    private LocalDate availableFrom;
    private String description;
    private ListingStatus status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
