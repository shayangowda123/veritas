package com.veritas.verification.geocoding.model;

/**
 * Resolved geographical location returned by a geocoder provider.
 */
public record GeocodedLocation(
        double latitude,
        double longitude,
        String displayName,
        Double confidence,
        String placeType,
        String attribution
) {
}
