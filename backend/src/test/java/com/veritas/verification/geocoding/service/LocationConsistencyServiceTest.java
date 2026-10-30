package com.veritas.verification.geocoding.service;

import com.veritas.property.PropertyRepository;
import com.veritas.verification.LocationVerificationStatus;
import com.veritas.verification.geocoding.client.GeocodingClient;
import com.veritas.verification.geocoding.config.GeocodingProperties;
import com.veritas.verification.geocoding.model.AddressQuery;
import com.veritas.verification.geocoding.model.GeocodedLocation;
import com.veritas.verification.geocoding.model.GeocodingResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LocationConsistencyServiceTest {

    @Mock
    private GeocodingClient geocodingClient;

    @Mock
    private PropertyRepository propertyRepository;

    private GeocodingProperties properties;
    private LocationConsistencyService consistencyService;

    private static final String ADDRESS = "123 100ft Road, HAL 2nd Stage";
    private static final String LOCALITY = "Indiranagar";
    private static final String CITY = "Bengaluru";
    private static final double SUBMITTED_LAT = 12.978369;
    private static final double SUBMITTED_LON = 77.641154;

    private GeocodedLocation sampleLocation;

    @BeforeEach
    void setUp() {
        properties = new GeocodingProperties();
        properties.setEnabled(true);
        properties.setMatchDistanceMeters(200.0);
        properties.setMismatchDistanceMeters(1000.0);
        properties.setMinConfidence(0.3);

        consistencyService = new LocationConsistencyService(geocodingClient, propertyRepository, properties);

        sampleLocation = new GeocodedLocation(
                12.978400,
                77.641200,
                "Indiranagar, Bengaluru, Karnataka, 560038, India",
                0.85,
                "suburb",
                "Data © OpenStreetMap contributors"
        );
    }

    // ─── 1. Close Geocoded Match ──────────────────────────────────────────────

    @Test
    @DisplayName("Close match within threshold returns MATCH and preserves PENDING status (NEVER VERIFIED)")
    void shouldReturnMatchForCloseDistance() {
        when(geocodingClient.geocode(any(AddressQuery.class)))
                .thenReturn(GeocodingResponse.success(List.of(sampleLocation)));
        // Simulated PostGIS ST_Distance returning 35.5 meters
        when(propertyRepository.calculateGeographyDistance(SUBMITTED_LON, SUBMITTED_LAT, sampleLocation.longitude(), sampleLocation.latitude()))
                .thenReturn(35.5);

        LocationConsistencyResult result = consistencyService.checkConsistency(
                ADDRESS, LOCALITY, CITY, SUBMITTED_LAT, SUBMITTED_LON
        );

        assertNotNull(result);
        assertEquals(LocationConsistencyOutcome.MATCH, result.outcome());
        assertEquals(35.5, result.distanceMeters(), 0.001);
        // CRITICAL: An automated match MUST NEVER set status to VERIFIED
        assertEquals(LocationVerificationStatus.PENDING, result.recommendedVerificationStatus(),
                "Automated consistency match must NEVER grant VERIFIED status");
        assertNotEquals(LocationVerificationStatus.VERIFIED, result.recommendedVerificationStatus());
        assertTrue(result.safeSummaryNotes().contains("MATCH"));
        assertTrue(result.safeSummaryNotes().contains("35.5m"));
    }

    // ─── 2. Boundary distance (REVIEW_REQUIRED) ───────────────────────────────

    @Test
    @DisplayName("Distance between match and mismatch thresholds returns REVIEW_REQUIRED")
    void shouldReturnReviewRequiredForIntermediateDistance() {
        when(geocodingClient.geocode(any(AddressQuery.class)))
                .thenReturn(GeocodingResponse.success(List.of(sampleLocation)));
        // 450 meters: exceeds 200m match threshold, but within 1000m mismatch threshold
        when(propertyRepository.calculateGeographyDistance(SUBMITTED_LON, SUBMITTED_LAT, sampleLocation.longitude(), sampleLocation.latitude()))
                .thenReturn(450.0);

        LocationConsistencyResult result = consistencyService.checkConsistency(
                ADDRESS, LOCALITY, CITY, SUBMITTED_LAT, SUBMITTED_LON
        );

        assertNotNull(result);
        assertEquals(LocationConsistencyOutcome.REVIEW_REQUIRED, result.outcome());
        assertEquals(450.0, result.distanceMeters(), 0.001);
        assertEquals(LocationVerificationStatus.REVIEW_REQUIRED, result.recommendedVerificationStatus());
        assertTrue(result.safeSummaryNotes().contains("REVIEW_REQUIRED"));
        assertTrue(result.safeSummaryNotes().contains("450.0m"));
    }

    // ─── 3. Location Mismatch ─────────────────────────────────────────────────

    @Test
    @DisplayName("Distance exceeding mismatch threshold returns MISMATCH and sets REVIEW_REQUIRED")
    void shouldReturnMismatchForLargeDistance() {
        when(geocodingClient.geocode(any(AddressQuery.class)))
                .thenReturn(GeocodingResponse.success(List.of(sampleLocation)));
        // 3500 meters: coordinates far from geocoded address
        when(propertyRepository.calculateGeographyDistance(SUBMITTED_LON, SUBMITTED_LAT, sampleLocation.longitude(), sampleLocation.latitude()))
                .thenReturn(3500.0);

        LocationConsistencyResult result = consistencyService.checkConsistency(
                ADDRESS, LOCALITY, CITY, SUBMITTED_LAT, SUBMITTED_LON
        );

        assertNotNull(result);
        assertEquals(LocationConsistencyOutcome.MISMATCH, result.outcome());
        assertEquals(3500.0, result.distanceMeters(), 0.001);
        assertEquals(LocationVerificationStatus.REVIEW_REQUIRED, result.recommendedVerificationStatus());
        assertTrue(result.safeSummaryNotes().contains("MISMATCH"));
        assertTrue(result.safeSummaryNotes().contains("3500.0m"));
    }

    // ─── 4. Ambiguous Geocoder Results ────────────────────────────────────────

    @Test
    @DisplayName("Ambiguous geocoder results flag REVIEW_REQUIRED without distance calculation")
    void shouldFlagReviewRequiredForAmbiguousResults() {
        GeocodedLocation loc1 = new GeocodedLocation(12.978, 77.641, "Indiranagar, Bangalore", 0.7, "suburb", "OSM");
        GeocodedLocation loc2 = new GeocodedLocation(13.100, 77.580, "Indiranagar, Yelahanka", 0.68, "suburb", "OSM");

        when(geocodingClient.geocode(any(AddressQuery.class)))
                .thenReturn(GeocodingResponse.ambiguous(List.of(loc1, loc2)));

        LocationConsistencyResult result = consistencyService.checkConsistency(
                ADDRESS, LOCALITY, CITY, SUBMITTED_LAT, SUBMITTED_LON
        );

        assertNotNull(result);
        assertEquals(LocationConsistencyOutcome.REVIEW_REQUIRED, result.outcome());
        assertEquals(LocationVerificationStatus.REVIEW_REQUIRED, result.recommendedVerificationStatus());
        assertNull(result.distanceMeters());
        assertTrue(result.safeSummaryNotes().contains("Ambiguous"));
        verify(propertyRepository, never()).calculateGeographyDistance(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    // ─── 5. Empty / No Results from Geocoder ──────────────────────────────────

    @Test
    @DisplayName("No results found returns GEOCODING_UNAVAILABLE and sets REVIEW_REQUIRED")
    void shouldHandleNoResultsSafely() {
        when(geocodingClient.geocode(any(AddressQuery.class)))
                .thenReturn(GeocodingResponse.noResults());

        LocationConsistencyResult result = consistencyService.checkConsistency(
                ADDRESS, LOCALITY, CITY, SUBMITTED_LAT, SUBMITTED_LON
        );

        assertNotNull(result);
        assertEquals(LocationConsistencyOutcome.GEOCODING_UNAVAILABLE, result.outcome());
        assertEquals(LocationVerificationStatus.REVIEW_REQUIRED, result.recommendedVerificationStatus());
        assertNull(result.distanceMeters());
        assertTrue(result.safeSummaryNotes().contains("GEOCODING_UNAVAILABLE"));
        verify(propertyRepository, never()).calculateGeographyDistance(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    // ─── 6. Provider Timeout, Rate Limiting, HTTP Failure ─────────────────────

    @Test
    @DisplayName("Provider timeout fails safely to GEOCODING_UNAVAILABLE and preserves PENDING status")
    void shouldHandleTimeoutSafely() {
        when(geocodingClient.geocode(any(AddressQuery.class)))
                .thenReturn(GeocodingResponse.timeout("Connection timed out after 3000ms"));

        LocationConsistencyResult result = consistencyService.checkConsistency(
                ADDRESS, LOCALITY, CITY, SUBMITTED_LAT, SUBMITTED_LON
        );

        assertNotNull(result);
        assertEquals(LocationConsistencyOutcome.GEOCODING_UNAVAILABLE, result.outcome());
        assertEquals(LocationVerificationStatus.PENDING, result.recommendedVerificationStatus(),
                "Timeouts must fail safely preserving PENDING state for manual review");
        assertNull(result.distanceMeters());
        assertTrue(result.safeSummaryNotes().contains("Provider timeout"));
    }

    @Test
    @DisplayName("Provider rate limiting (429) fails safely to GEOCODING_UNAVAILABLE and preserves PENDING status")
    void shouldHandleRateLimitingSafely() {
        when(geocodingClient.geocode(any(AddressQuery.class)))
                .thenReturn(GeocodingResponse.rateLimited("Nominatim HTTP 429 Too Many Requests"));

        LocationConsistencyResult result = consistencyService.checkConsistency(
                ADDRESS, LOCALITY, CITY, SUBMITTED_LAT, SUBMITTED_LON
        );

        assertNotNull(result);
        assertEquals(LocationConsistencyOutcome.GEOCODING_UNAVAILABLE, result.outcome());
        assertEquals(LocationVerificationStatus.PENDING, result.recommendedVerificationStatus());
        assertNull(result.distanceMeters());
        assertTrue(result.safeSummaryNotes().contains("rate limit"));
    }

    @Test
    @DisplayName("Provider HTTP error fails safely to GEOCODING_UNAVAILABLE and preserves PENDING status")
    void shouldHandleProviderErrorSafely() {
        when(geocodingClient.geocode(any(AddressQuery.class)))
                .thenReturn(GeocodingResponse.error("500 Internal Server Error"));

        LocationConsistencyResult result = consistencyService.checkConsistency(
                ADDRESS, LOCALITY, CITY, SUBMITTED_LAT, SUBMITTED_LON
        );

        assertNotNull(result);
        assertEquals(LocationConsistencyOutcome.GEOCODING_UNAVAILABLE, result.outcome());
        assertEquals(LocationVerificationStatus.PENDING, result.recommendedVerificationStatus());
        assertNull(result.distanceMeters());
        assertTrue(result.safeSummaryNotes().contains("Geocoder error"));
    }

    // ─── 7. Invalid Coordinates and Addresses ─────────────────────────────────

    @ParameterizedTest
    @CsvSource({
            "91.0, 77.0",
            "-90.1, 77.0",
            "12.0, 180.1",
            "12.0, -180.5"
    })
    @DisplayName("Coordinates exceeding geographic bounds reject with REVIEW_REQUIRED")
    void shouldRejectOutOfBoundsCoordinates(double lat, double lon) {
        LocationConsistencyResult result = consistencyService.checkConsistency(
                ADDRESS, LOCALITY, CITY, lat, lon
        );

        assertNotNull(result);
        assertEquals(LocationConsistencyOutcome.REVIEW_REQUIRED, result.outcome());
        assertEquals(LocationVerificationStatus.REVIEW_REQUIRED, result.recommendedVerificationStatus());
        verifyNoInteractions(geocodingClient);
        verifyNoInteractions(propertyRepository);
    }

    @Test
    @DisplayName("Null coordinate values reject with REVIEW_REQUIRED")
    void shouldRejectNullCoordinates() {
        LocationConsistencyResult result = consistencyService.checkConsistency(
                ADDRESS, LOCALITY, CITY, null, 77.641154
        );

        assertNotNull(result);
        assertEquals(LocationConsistencyOutcome.REVIEW_REQUIRED, result.outcome());
        assertEquals(LocationVerificationStatus.REVIEW_REQUIRED, result.recommendedVerificationStatus());
        verifyNoInteractions(geocodingClient);
    }

    @ParameterizedTest
    @CsvSource({
            "''",
            "'   '"
    })
    @DisplayName("Blank address rejects with REVIEW_REQUIRED")
    void shouldRejectBlankAddress(String blankAddress) {
        LocationConsistencyResult result = consistencyService.checkConsistency(
                blankAddress, LOCALITY, CITY, SUBMITTED_LAT, SUBMITTED_LON
        );

        assertNotNull(result);
        assertEquals(LocationConsistencyOutcome.REVIEW_REQUIRED, result.outcome());
        assertEquals(LocationVerificationStatus.REVIEW_REQUIRED, result.recommendedVerificationStatus());
        verifyNoInteractions(geocodingClient);
    }

    // ─── 8. Threshold Configuration ───────────────────────────────────────────

    @Test
    @DisplayName("Respects dynamic configurable thresholds")
    void shouldRespectCustomConfiguredThresholds() {
        // Configure strict 50m match and 300m mismatch thresholds
        properties.setMatchDistanceMeters(50.0);
        properties.setMismatchDistanceMeters(300.0);

        when(geocodingClient.geocode(any(AddressQuery.class)))
                .thenReturn(GeocodingResponse.success(List.of(sampleLocation)));

        // 75m: under standard 200m, but exceeds custom 50m threshold -> REVIEW_REQUIRED
        when(propertyRepository.calculateGeographyDistance(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(75.0);

        LocationConsistencyResult result = consistencyService.checkConsistency(
                ADDRESS, LOCALITY, CITY, SUBMITTED_LAT, SUBMITTED_LON
        );

        assertEquals(LocationConsistencyOutcome.REVIEW_REQUIRED, result.outcome());
        assertEquals(LocationVerificationStatus.REVIEW_REQUIRED, result.recommendedVerificationStatus());

        // 350m: exceeds custom 300m threshold -> MISMATCH
        when(propertyRepository.calculateGeographyDistance(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(350.0);

        LocationConsistencyResult mismatchResult = consistencyService.checkConsistency(
                ADDRESS, LOCALITY, CITY, SUBMITTED_LAT, SUBMITTED_LON
        );

        assertEquals(LocationConsistencyOutcome.MISMATCH, mismatchResult.outcome());
        assertEquals(LocationVerificationStatus.REVIEW_REQUIRED, mismatchResult.recommendedVerificationStatus());
    }

    // ─── 9. Feature Disabled Toggle ───────────────────────────────────────────

    @Test
    @DisplayName("Disabled geocoding feature safely returns GEOCODING_UNAVAILABLE with PENDING status")
    void shouldReturnUnavailableWhenDisabled() {
        properties.setEnabled(false);

        LocationConsistencyResult result = consistencyService.checkConsistency(
                ADDRESS, LOCALITY, CITY, SUBMITTED_LAT, SUBMITTED_LON
        );

        assertNotNull(result);
        assertEquals(LocationConsistencyOutcome.GEOCODING_UNAVAILABLE, result.outcome());
        assertEquals(LocationVerificationStatus.PENDING, result.recommendedVerificationStatus());
        verifyNoInteractions(geocodingClient);
        verifyNoInteractions(propertyRepository);
    }
}
