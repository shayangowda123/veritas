package com.veritas.verification;

/**
 * Evidence categories an administrator may report reviewing.
 *
 * These values describe the claimed evidence category only.
 * They do not independently prove the property's location.
 */
public enum VerificationMethod {
    UTILITY_BILL,
    GOVERNMENT_PROPERTY_RECORD,
    SITE_VISIT,
    NOTARIZED_DOCUMENT,
    OTHER
}