package com.veritas.poi.discovery.dto;

/**
 * A single nearby-places result: a privacy-safe POI summary plus the distance (in metres) from the
 * published property. The distance is relative to the server-resolved property centre, which is
 * itself never returned, so no absolute coordinates (property or POI) are exposed.
 *
 * @param poi            privacy-safe POI projection
 * @param distanceMeters metres from the published property to this POI
 */
public record PoiNearbyResult(
        PoiNearbySummary poi,
        Double distanceMeters
) {
}
