package com.veritas.search;

import com.veritas.verification.LocationVerificationStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Internal read model produced by a single discovery query row. It intentionally carries only
 * publicly safe attributes plus an optional distance from the search centre. Exact address,
 * coordinates and internal owner identifiers are never selected and therefore never represented.
 */
public class PropertySearchRow {

    private final UUID propertyId;
    private final String propertyType;
    private final String locality;
    private final String city;
    private final LocationVerificationStatus locationVerificationStatus;
    private final int imageCount;
    private final OffsetDateTime createdAt;
    private final Double distanceMeters;

    public PropertySearchRow(
            UUID propertyId,
            String propertyType,
            String locality,
            String city,
            LocationVerificationStatus locationVerificationStatus,
            int imageCount,
            OffsetDateTime createdAt,
            Double distanceMeters
    ) {
        this.propertyId = propertyId;
        this.propertyType = propertyType;
        this.locality = locality;
        this.city = city;
        this.locationVerificationStatus = locationVerificationStatus;
        this.imageCount = imageCount;
        this.createdAt = createdAt;
        this.distanceMeters = distanceMeters;
    }

    public UUID getPropertyId() {
        return propertyId;
    }

    public String getPropertyType() {
        return propertyType;
    }

    public String getLocality() {
        return locality;
    }

    public String getCity() {
        return city;
    }

    public LocationVerificationStatus getLocationVerificationStatus() {
        return locationVerificationStatus;
    }

    public int getImageCount() {
        return imageCount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public Double getDistanceMeters() {
        return distanceMeters;
    }
}
