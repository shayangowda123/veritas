package com.veritas.verification.geocoding.model;

/**
 * Outcome status of a geocoding lookup operation.
 */
public enum GeocodingStatus {
    SUCCESS,
    NO_RESULTS,
    AMBIGUOUS,
    RATE_LIMITED,
    TIMEOUT,
    ERROR
}
