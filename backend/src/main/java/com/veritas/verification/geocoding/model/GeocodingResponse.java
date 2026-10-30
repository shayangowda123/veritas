package com.veritas.verification.geocoding.model;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Encapsulates the response from a geocoding provider.
 */
public record GeocodingResponse(
        GeocodingStatus status,
        List<GeocodedLocation> results,
        String errorMessage
) {

    public static GeocodingResponse success(List<GeocodedLocation> results) {
        return new GeocodingResponse(GeocodingStatus.SUCCESS, results != null ? results : List.of(), null);
    }

    public static GeocodingResponse noResults() {
        return new GeocodingResponse(GeocodingStatus.NO_RESULTS, Collections.emptyList(), null);
    }

    public static GeocodingResponse ambiguous(List<GeocodedLocation> results) {
        return new GeocodingResponse(GeocodingStatus.AMBIGUOUS, results != null ? results : List.of(), "Ambiguous location match");
    }

    public static GeocodingResponse rateLimited(String message) {
        return new GeocodingResponse(GeocodingStatus.RATE_LIMITED, Collections.emptyList(), message);
    }

    public static GeocodingResponse timeout(String message) {
        return new GeocodingResponse(GeocodingStatus.TIMEOUT, Collections.emptyList(), message);
    }

    public static GeocodingResponse error(String message) {
        return new GeocodingResponse(GeocodingStatus.ERROR, Collections.emptyList(), message);
    }

    public boolean isSuccess() {
        return status == GeocodingStatus.SUCCESS && results != null && !results.isEmpty();
    }

    public Optional<GeocodedLocation> getPrimaryLocation() {
        if (isSuccess()) {
            return Optional.of(results.get(0));
        }
        return Optional.empty();
    }
}
