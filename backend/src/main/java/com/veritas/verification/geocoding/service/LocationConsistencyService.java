package com.veritas.verification.geocoding.service;

import com.veritas.property.PropertyRepository;
import com.veritas.verification.LocationVerificationStatus;
import com.veritas.verification.geocoding.client.GeocodingClient;
import com.veritas.verification.geocoding.config.GeocodingProperties;
import com.veritas.verification.geocoding.model.AddressQuery;
import com.veritas.verification.geocoding.model.GeocodedLocation;
import com.veritas.verification.geocoding.model.GeocodingResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Service evaluating spatial consistency between submitted property coordinates and
 * geocoded address points using PostGIS geography-based distance calculations.
 *
 * <p><strong>CRITICAL SECURITY INVARIANTS:</strong></p>
 * <ul>
 *     <li>An automated MATCH NEVER sets status to VERIFIED. Only authorized administrators/reviewers
 *         can grant VERIFIED status following physical or documentary verification.</li>
 *     <li>Geocoding failures, rate limits, or timeouts fail safely, preserving a reviewable PENDING state.</li>
 *     <li>Distances are calculated using PostGIS ST_Distance over WGS84 geography (meters), avoiding
 *         inaccurate Euclidean planar distance calculations.</li>
 * </ul>
 */
@Service
public class LocationConsistencyService {

    private static final Logger log = LoggerFactory.getLogger(LocationConsistencyService.class);

    private final GeocodingClient geocodingClient;
    private final PropertyRepository propertyRepository;
    private final GeocodingProperties properties;

    public LocationConsistencyService(
            GeocodingClient geocodingClient,
            PropertyRepository propertyRepository,
            GeocodingProperties properties
    ) {
        this.geocodingClient = geocodingClient;
        this.propertyRepository = propertyRepository;
        this.properties = properties;
    }

    /**
     * Evaluates location consistency for a submitted property address and coordinate pair.
     *
     * @param address raw address text
     * @param locality property locality/neighborhood
     * @param city property city
     * @param latitude submitted GPS latitude
     * @param longitude submitted GPS longitude
     * @return LocationConsistencyResult containing outcome, distance, safe notes, and recommended status
     */
    public LocationConsistencyResult checkConsistency(
            String address,
            String locality,
            String city,
            Double latitude,
            Double longitude
    ) {
        // 1. Validate inputs
        if (latitude == null || longitude == null || latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) {
            log.warn("Rejected invalid coordinate bounds during consistency evaluation");
            return LocationConsistencyResult.reviewRequired("Invalid coordinate values out of valid geographic range", null, null);
        }

        if (address == null || address.trim().isBlank()) {
            log.warn("Rejected empty address during consistency evaluation");
            return LocationConsistencyResult.reviewRequired("Property address cannot be blank", null, null);
        }

        // 2. Check if geocoding feature is enabled
        if (!properties.isEnabled()) {
            log.info("Geocoding consistency check is disabled in configuration");
            return LocationConsistencyResult.geocodingUnavailable("Feature disabled in configuration", LocationVerificationStatus.PENDING);
        }

        // 3. Resolve address via geocoder
        AddressQuery query = new AddressQuery(address, locality, city);
        GeocodingResponse response = geocodingClient.geocode(query);

        switch (response.status()) {
            case TIMEOUT -> {
                log.warn("Geocoding lookup timed out for locality: {}, city: {}", locality, city);
                return LocationConsistencyResult.geocodingUnavailable("Provider timeout", LocationVerificationStatus.PENDING);
            }
            case RATE_LIMITED -> {
                log.warn("Geocoding lookup rate-limited for locality: {}, city: {}", locality, city);
                return LocationConsistencyResult.geocodingUnavailable("Provider rate limit reached", LocationVerificationStatus.PENDING);
            }
            case NO_RESULTS -> {
                log.info("Geocoding lookup returned 0 results for locality: {}, city: {}", locality, city);
                return LocationConsistencyResult.geocodingUnavailable("Address not found by geocoder", LocationVerificationStatus.REVIEW_REQUIRED);
            }
            case AMBIGUOUS -> {
                log.info("Geocoding lookup returned ambiguous results for locality: {}, city: {}", locality, city);
                return LocationConsistencyResult.reviewRequired("Ambiguous geocoding results across disparate locations", null, null);
            }
            case ERROR -> {
                log.warn("Geocoding lookup error: {}", response.errorMessage());
                return LocationConsistencyResult.geocodingUnavailable("Geocoder error", LocationVerificationStatus.PENDING);
            }
            case SUCCESS -> {
                // Proceed to distance calculation
            }
        }

        Optional<GeocodedLocation> primaryLocOpt = response.getPrimaryLocation();
        if (primaryLocOpt.isEmpty()) {
            return LocationConsistencyResult.geocodingUnavailable("No valid location parsed from geocoder", LocationVerificationStatus.REVIEW_REQUIRED);
        }

        GeocodedLocation geocodedLoc = primaryLocOpt.get();

        // 4. Calculate meter-based geography distance using PostGIS ST_Distance
        Double distanceMeters;
        try {
            distanceMeters = propertyRepository.calculateGeographyDistance(
                    longitude,
                    latitude,
                    geocodedLoc.longitude(),
                    geocodedLoc.latitude()
            );
        } catch (Exception ex) {
            log.error("Failed executing PostGIS geography ST_Distance query", ex);
            return LocationConsistencyResult.geocodingUnavailable("Database geography distance calculation error", LocationVerificationStatus.PENDING);
        }

        if (distanceMeters == null) {
            return LocationConsistencyResult.geocodingUnavailable("Spatial distance calculation returned null", LocationVerificationStatus.PENDING);
        }

        // 5. Evaluate distance against configurable thresholds
        double matchThreshold = properties.getMatchDistanceMeters();
        double mismatchThreshold = properties.getMismatchDistanceMeters();

        if (distanceMeters <= matchThreshold) {
            log.info("Location consistency check: MATCH (distance: {}m <= threshold: {}m)", Math.round(distanceMeters), matchThreshold);
            return LocationConsistencyResult.match(distanceMeters, geocodedLoc);
        } else if (distanceMeters <= mismatchThreshold) {
            log.info("Location consistency check: REVIEW_REQUIRED (distance: {}m between {}m and {}m)", Math.round(distanceMeters), matchThreshold, mismatchThreshold);
            return LocationConsistencyResult.reviewRequired(
                    String.format("Submitted coordinates deviate by %.1fm from geocoded address (threshold: %.0fm)", distanceMeters, matchThreshold),
                    distanceMeters,
                    geocodedLoc
            );
        } else {
            log.warn("Location consistency check: MISMATCH (distance: {}m exceeds mismatch threshold: {}m)", Math.round(distanceMeters), mismatchThreshold);
            return LocationConsistencyResult.mismatch(distanceMeters, geocodedLoc);
        }
    }
}
