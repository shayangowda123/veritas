package com.veritas.verification.geocoding.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.veritas.verification.geocoding.config.GeocodingProperties;
import com.veritas.verification.geocoding.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenStreetMap Nominatim adapter adhering strictly to OSM usage policy:
 * - Mandatory identifiable User-Agent
 * - Strict bounded connection and read timeouts
 * - Polite request throttling (enforcing maximum 1 request per second)
 * - Controlled error handling for 429 rate limiting, timeouts, and network outages
 * - Privacy protection (never logging private address details or coordinates)
 */
@Component
@ConditionalOnProperty(name = "veritas.geocoding.provider", havingValue = "nominatim", matchIfMissing = true)
public class NominatimGeocodingClient implements GeocodingClient {

    private static final Logger log = LoggerFactory.getLogger(NominatimGeocodingClient.class);

    private final GeocodingProperties properties;
    private final RestClient restClient;
    private final AtomicLong lastRequestTimeMs = new AtomicLong(0);

    @Autowired
    public NominatimGeocodingClient(GeocodingProperties properties) {
        this(properties, buildRestClient(properties));
    }

    public NominatimGeocodingClient(GeocodingProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    private static RestClient buildRestClient(GeocodingProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(properties.getConnectTimeoutMs(), 1000)))
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(Math.max(properties.getReadTimeoutMs(), 1000)));

        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.USER_AGENT, properties.getUserAgent())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public GeocodingResponse geocode(AddressQuery query) {
        if (query == null || !query.isValid()) {
            return GeocodingResponse.noResults();
        }

        throttleIfNecessary();

        String formattedQuery = query.toFormattedQuery();
        URI requestUri = UriComponentsBuilder.fromUriString(properties.getBaseUrl())
                .path("/search")
                .queryParam("format", "jsonv2")
                .queryParam("q", formattedQuery)
                .queryParam("addressdetails", "1")
                .queryParam("limit", "5")
                .build()
                .encode()
                .toUri();

        log.debug("Dispatching Nominatim geocoding lookup (locality: {}, city: {})", query.locality(), query.city());

        try {
            List<NominatimPlaceDto> responseList = restClient.get()
                    .uri(requestUri)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<NominatimPlaceDto>>() {});

            if (responseList == null || responseList.isEmpty()) {
                log.debug("Nominatim returned 0 results for query");
                return GeocodingResponse.noResults();
            }

            List<GeocodedLocation> parsedLocations = new ArrayList<>();
            for (NominatimPlaceDto dto : responseList) {
                try {
                    double lat = Double.parseDouble(dto.lat());
                    double lon = Double.parseDouble(dto.lon());
                    Double importance = dto.importance() != null ? dto.importance() : 0.0;
                    parsedLocations.add(new GeocodedLocation(
                            lat,
                            lon,
                            dto.displayName(),
                            importance,
                            dto.type(),
                            dto.licence() != null ? dto.licence() : "Data © OpenStreetMap contributors"
                    ));
                } catch (NumberFormatException | NullPointerException e) {
                    log.warn("Skipping invalid coordinate pair in Nominatim response");
                }
            }

            if (parsedLocations.isEmpty()) {
                return GeocodingResponse.noResults();
            }

            // Ambiguity check: If top 2 results have similar importance but are located far apart
            if (parsedLocations.size() > 1) {
                GeocodedLocation first = parsedLocations.get(0);
                GeocodedLocation second = parsedLocations.get(1);
                double dLat = Math.abs(first.latitude() - second.latitude());
                double dLon = Math.abs(first.longitude() - second.longitude());
                // Disparity check: if rough degrees delta > ~0.05 (~5km) and both have similar high importance
                if ((dLat > 0.05 || dLon > 0.05) && second.confidence() != null && second.confidence() > (first.confidence() * 0.8)) {
                    log.info("Nominatim returned multiple disparate locations for address; marking AMBIGUOUS");
                    return GeocodingResponse.ambiguous(parsedLocations);
                }
            }

            return GeocodingResponse.success(parsedLocations);

        } catch (HttpClientErrorException.TooManyRequests ex) {
            log.warn("Nominatim rate limit exceeded (HTTP 429). Preserving reviewable state without failing.");
            return GeocodingResponse.rateLimited("Nominatim geocoder rate limited (HTTP 429)");
        } catch (ResourceAccessException ex) {
            log.warn("Nominatim connection or read timed out. Preserving reviewable state without failing.");
            return GeocodingResponse.timeout("Nominatim request timed out or network unreachable");
        } catch (RestClientException ex) {
            log.warn("Nominatim request failed: {}. Preserving reviewable state without failing.", ex.getClass().getSimpleName());
            return GeocodingResponse.error("Nominatim geocoding error: " + ex.getMessage());
        } catch (Exception ex) {
            log.error("Unexpected error during Nominatim geocoding resolution", ex);
            return GeocodingResponse.error("Unexpected geocoding failure: " + ex.getMessage());
        }
    }

    private void throttleIfNecessary() {
        long minInterval = properties.getMinIntervalMs();
        if (minInterval <= 0) {
            return;
        }

        synchronized (lastRequestTimeMs) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRequestTimeMs.get();
            if (elapsed < minInterval) {
                long waitTime = minInterval - elapsed;
                try {
                    Thread.sleep(Math.min(waitTime, 2000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastRequestTimeMs.set(System.currentTimeMillis());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NominatimPlaceDto(
            String lat,
            String lon,
            @JsonProperty("display_name") String displayName,
            Double importance,
            String type,
            @JsonProperty("class") String category,
            String licence
    ) {}
}
