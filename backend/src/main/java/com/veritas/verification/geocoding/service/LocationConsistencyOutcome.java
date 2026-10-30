package com.veritas.verification.geocoding.service;

/**
 * High-level outcomes for automated location consistency evaluation.
 */
public enum LocationConsistencyOutcome {
    MATCH,
    REVIEW_REQUIRED,
    MISMATCH,
    GEOCODING_UNAVAILABLE
}
