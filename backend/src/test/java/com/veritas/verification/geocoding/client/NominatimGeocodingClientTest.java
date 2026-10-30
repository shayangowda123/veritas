package com.veritas.verification.geocoding.client;

import com.veritas.verification.geocoding.config.GeocodingProperties;
import com.veritas.verification.geocoding.model.AddressQuery;
import com.veritas.verification.geocoding.model.GeocodedLocation;
import com.veritas.verification.geocoding.model.GeocodingResponse;
import com.veritas.verification.geocoding.model.GeocodingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class NominatimGeocodingClientTest {

    private GeocodingProperties properties;
    private MockRestServiceServer mockServer;
    private NominatimGeocodingClient client;

    @BeforeEach
    void setUp() {
        properties = new GeocodingProperties();
        properties.setBaseUrl("https://nominatim.test.local");
        properties.setUserAgent("Veritas-Test/1.0 (contact@veritas.local)");
        properties.setConnectTimeoutMs(2000);
        properties.setReadTimeoutMs(3000);
        properties.setMinIntervalMs(0); // Set to 0 in unit tests to avoid sleep delays

        RestClient.Builder restClientBuilder = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, properties.getUserAgent());

        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        RestClient restClient = restClientBuilder.build();

        client = new NominatimGeocodingClient(properties, restClient);
    }

    @Test
    @DisplayName("Successfully parses valid Nominatim JSON response with coordinates and attribution")
    void shouldGeocodeAddressSuccessfully() {
        String jsonResponse = """
                [
                  {
                    "place_id": 12345,
                    "licence": "Data © OpenStreetMap contributors",
                    "lat": "12.9783692",
                    "lon": "77.6411544",
                    "display_name": "Indiranagar, Bangalore, Karnataka, 560038, India",
                    "importance": 0.75,
                    "type": "suburb",
                    "class": "place"
                  }
                ]
                """;

        mockServer.expect(requestTo("https://nominatim.test.local/search?format=jsonv2&q=123%20100ft%20Road,%20Indiranagar,%20Bengaluru&addressdetails=1&limit=5"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.USER_AGENT, properties.getUserAgent()))
                .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        AddressQuery query = new AddressQuery("123 100ft Road", "Indiranagar", "Bengaluru");
        GeocodingResponse response = client.geocode(query);

        mockServer.verify();
        assertNotNull(response);
        assertEquals(GeocodingStatus.SUCCESS, response.status());
        assertTrue(response.isSuccess());

        Optional<GeocodedLocation> locOpt = response.getPrimaryLocation();
        assertTrue(locOpt.isPresent());
        GeocodedLocation loc = locOpt.get();
        assertEquals(12.9783692, loc.latitude(), 0.000001);
        assertEquals(77.6411544, loc.longitude(), 0.000001);
        assertEquals(0.75, loc.confidence(), 0.001);
        assertEquals("Data © OpenStreetMap contributors", loc.attribution());
    }

    @Test
    @DisplayName("Empty JSON array returns NO_RESULTS")
    void shouldReturnNoResultsForEmptyArray() {
        mockServer.expect(requestTo("https://nominatim.test.local/search?format=jsonv2&q=Unknown%20Nowhere&addressdetails=1&limit=5"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        AddressQuery query = new AddressQuery("Unknown Nowhere", null, null);
        GeocodingResponse response = client.geocode(query);

        mockServer.verify();
        assertNotNull(response);
        assertEquals(GeocodingStatus.NO_RESULTS, response.status());
        assertFalse(response.isSuccess());
        assertTrue(response.results().isEmpty());
    }

    @Test
    @DisplayName("Detects disparate results with high confidence and marks AMBIGUOUS")
    void shouldDetectAmbiguousResults() {
        // Two places named "Indiranagar" in Bangalore with coordinates ~15km apart
        String jsonResponse = """
                [
                  {
                    "lat": "12.9783",
                    "lon": "77.6411",
                    "display_name": "Indiranagar, Bangalore East, Karnataka",
                    "importance": 0.82,
                    "type": "suburb"
                  },
                  {
                    "lat": "13.1000",
                    "lon": "77.5800",
                    "display_name": "Indiranagar, Yelahanka, Bangalore North, Karnataka",
                    "importance": 0.80,
                    "type": "suburb"
                  }
                ]
                """;

        mockServer.expect(requestTo("https://nominatim.test.local/search?format=jsonv2&q=Indiranagar&addressdetails=1&limit=5"))
                .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        AddressQuery query = new AddressQuery("Indiranagar", null, null);
        GeocodingResponse response = client.geocode(query);

        mockServer.verify();
        assertNotNull(response);
        assertEquals(GeocodingStatus.AMBIGUOUS, response.status());
        assertEquals(2, response.results().size());
    }

    @Test
    @DisplayName("HTTP 429 Too Many Requests returns RATE_LIMITED without throwing")
    void shouldHandleRateLimitingGracefully() {
        mockServer.expect(requestTo("https://nominatim.test.local/search?format=jsonv2&q=Main%20Street&addressdetails=1&limit=5"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        AddressQuery query = new AddressQuery("Main Street", null, null);
        GeocodingResponse response = client.geocode(query);

        mockServer.verify();
        assertNotNull(response);
        assertEquals(GeocodingStatus.RATE_LIMITED, response.status());
        assertTrue(response.errorMessage().contains("rate limit"));
    }

    @Test
    @DisplayName("Network timeout returns TIMEOUT without throwing")
    void shouldHandleNetworkTimeoutGracefully() {
        mockServer.expect(requestTo("https://nominatim.test.local/search?format=jsonv2&q=Main%20Street&addressdetails=1&limit=5"))
                .andRespond(withException(new java.net.SocketTimeoutException("Read timed out")));

        AddressQuery query = new AddressQuery("Main Street", null, null);
        GeocodingResponse response = client.geocode(query);

        mockServer.verify();
        assertNotNull(response);
        assertEquals(GeocodingStatus.TIMEOUT, response.status());
        assertTrue(response.errorMessage().contains("timed out"));
    }

    @Test
    @DisplayName("HTTP 500 Server Error returns ERROR without throwing")
    void shouldHandleServerErrorGracefully() {
        mockServer.expect(requestTo("https://nominatim.test.local/search?format=jsonv2&q=Main%20Street&addressdetails=1&limit=5"))
                .andRespond(withServerError());

        AddressQuery query = new AddressQuery("Main Street", null, null);
        GeocodingResponse response = client.geocode(query);

        mockServer.verify();
        assertNotNull(response);
        assertEquals(GeocodingStatus.ERROR, response.status());
    }

    @Test
    @DisplayName("Blank or null address query returns NO_RESULTS immediately without network call")
    void shouldRejectInvalidQueryWithoutNetworkCall() {
        AddressQuery nullQuery = null;
        AddressQuery emptyQuery = new AddressQuery("", "Locality", "City");
        AddressQuery blankQuery = new AddressQuery("   ", null, null);

        assertEquals(GeocodingStatus.NO_RESULTS, client.geocode(nullQuery).status());
        assertEquals(GeocodingStatus.NO_RESULTS, client.geocode(emptyQuery).status());
        assertEquals(GeocodingStatus.NO_RESULTS, client.geocode(blankQuery).status());

        mockServer.verify(); // Verifies 0 HTTP requests were dispatched
    }
}
