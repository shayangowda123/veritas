package com.veritas.verification.geocoding.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for geocoding services and spatial consistency thresholds.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "veritas.geocoding")
public class GeocodingProperties {

    /**
     * Whether geocoding-based location consistency checks are enabled.
     */
    private boolean enabled = true;

    /**
     * The provider implementation to use ("nominatim" or "mock").
     */
    private String provider = "nominatim";

    /**
     * Base URL for the geocoding provider API.
     */
    private String baseUrl = "https://nominatim.openstreetmap.org";

    /**
     * Custom User-Agent header (required by Nominatim usage policy).
     */
    private String userAgent = "Veritas-Backend/1.0 (veritas-verification@veritas.local)";

    /**
     * Connection timeout in milliseconds.
     */
    private int connectTimeoutMs = 3000;

    /**
     * Read / response timeout in milliseconds.
     */
    private int readTimeoutMs = 5000;

    /**
     * Minimum interval between requests in milliseconds (enforces Nominatim 1 req/sec limit).
     */
    private long minIntervalMs = 1000;

    /**
     * Distance in meters within which submitted coordinates and geocoded address
     * are considered a MATCH.
     */
    private double matchDistanceMeters = 200.0;

    /**
     * Distance in meters beyond which submitted coordinates and geocoded address
     * are considered a MISMATCH.
     */
    private double mismatchDistanceMeters = 1000.0;

    /**
     * Minimum importance/confidence threshold for provider results (0.0 to 1.0).
     */
    private double minConfidence = 0.3;
}
