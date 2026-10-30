package com.veritas.verification.geocoding.client;

import com.veritas.verification.geocoding.model.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock geocoding client for testing and offline environments.
 */
@Component
@ConditionalOnProperty(name = "veritas.geocoding.provider", havingValue = "mock")
public class MockGeocodingClient implements GeocodingClient {

    private final Map<String, GeocodingResponse> responses = new ConcurrentHashMap<>();
    private GeocodingResponse defaultResponse = GeocodingResponse.success(List.of(
            new GeocodedLocation(12.978369, 77.641154, "123 Indiranagar, Bengaluru, India", 0.85, "suburb", "Mock Data")
    ));

    public void registerResponse(String queryKey, GeocodingResponse response) {
        responses.put(queryKey.toLowerCase(), response);
    }

    public void setDefaultResponse(GeocodingResponse response) {
        this.defaultResponse = response;
    }

    @Override
    public GeocodingResponse geocode(AddressQuery query) {
        if (query == null || !query.isValid()) {
            return GeocodingResponse.noResults();
        }

        String key = query.toFormattedQuery().toLowerCase();
        for (Map.Entry<String, GeocodingResponse> entry : responses.entrySet()) {
            if (key.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        return defaultResponse;
    }
}
