package com.veritas.verification.geocoding.client;

import com.veritas.verification.geocoding.model.AddressQuery;
import com.veritas.verification.geocoding.model.GeocodingResponse;

/**
 * Abstraction for geocoding address resolution providers.
 */
public interface GeocodingClient {

    /**
     * Resolves a structured address query into geographical coordinates and metadata.
     *
     * @param query the address query containing address, locality, and city
     * @return bounded GeocodingResponse with status and resolved points
     */
    GeocodingResponse geocode(AddressQuery query);
}
