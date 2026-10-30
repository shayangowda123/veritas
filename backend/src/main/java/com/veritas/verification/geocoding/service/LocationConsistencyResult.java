package com.veritas.verification.geocoding.service;

import com.veritas.verification.LocationVerificationStatus;
import com.veritas.verification.geocoding.model.GeocodedLocation;

/**
 * Result of an automated location consistency check between submitted coordinates and
 * geocoded address points.
 *
 * <p><strong>CRITICAL SECURITY INVARIANT:</strong> An automated match between submitted coordinates
 * and geocoded address points NEVER grants {@link LocationVerificationStatus#VERIFIED}. It merely
 * validates spatial plausibility, maintaining {@link LocationVerificationStatus#PENDING} until an
 * authorized administrator or verification agent reviews physical/documentary proof.</p>
 */
public record LocationConsistencyResult(
        LocationConsistencyOutcome outcome,
        Double distanceMeters,
        LocationVerificationStatus recommendedVerificationStatus,
        String safeSummaryNotes,
        GeocodedLocation resolvedLocation
) {

    public static LocationConsistencyResult match(double distanceMeters, GeocodedLocation location) {
        return new LocationConsistencyResult(
                LocationConsistencyOutcome.MATCH,
                distanceMeters,
                LocationVerificationStatus.PENDING,
                String.format("Location consistency check: MATCH (distance: %.1fm). Pending physical/document verification.", distanceMeters),
                location
        );
    }

    public static LocationConsistencyResult reviewRequired(String reason, Double distanceMeters, GeocodedLocation location) {
        String notes = distanceMeters != null
                ? String.format("Location consistency check: REVIEW_REQUIRED (distance: %.1fm). %s", distanceMeters, reason)
                : String.format("Location consistency check: REVIEW_REQUIRED. %s", reason);

        return new LocationConsistencyResult(
                LocationConsistencyOutcome.REVIEW_REQUIRED,
                distanceMeters,
                LocationVerificationStatus.REVIEW_REQUIRED,
                notes,
                location
        );
    }

    public static LocationConsistencyResult mismatch(double distanceMeters, GeocodedLocation location) {
        return new LocationConsistencyResult(
                LocationConsistencyOutcome.MISMATCH,
                distanceMeters,
                LocationVerificationStatus.REVIEW_REQUIRED,
                String.format("Location consistency check: MISMATCH (distance: %.1fm exceeds threshold). Flagged for reviewer inspection.", distanceMeters),
                location
        );
    }

    public static LocationConsistencyResult geocodingUnavailable(String reason, LocationVerificationStatus fallbackStatus) {
        return new LocationConsistencyResult(
                LocationConsistencyOutcome.GEOCODING_UNAVAILABLE,
                null,
                fallbackStatus != null ? fallbackStatus : LocationVerificationStatus.PENDING,
                String.format("Location consistency check: GEOCODING_UNAVAILABLE (%s). Preserved for manual review.", reason),
                null
        );
    }
}
