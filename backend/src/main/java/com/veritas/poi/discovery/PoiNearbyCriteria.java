package com.veritas.poi.discovery;

import java.util.List;

/**
 * Normalized, internal nearby-discovery criteria detached from the web layer. Built by
 * {@link PoiNearbyService} from a validated request plus the server-resolved property centre and
 * consumed by {@link PoiNearbyQueryBuilder}.
 *
 * <p>The latitude/longitude here are always resolved on the server from a published property;
 * they are never taken from renter input. Categories are stored as already-validated enum names.</p>
 */
public record PoiNearbyCriteria(
        Double latitude,
        Double longitude,
        Double radiusInMeters,
        List<String> categories,
        String sortBy
) {

    public boolean hasCentre() {
        return latitude != null && longitude != null;
    }

    public boolean hasCategoryFilter() {
        return categories != null && !categories.isEmpty();
    }
}
