package com.veritas.poi.discovery.dto;

import com.veritas.poi.PoiType;

import java.util.UUID;

/**
 * Privacy-safe public POI projection. Carries no raw coordinates, address, source, external id,
 * source url, licence or internal timestamps — those columns are never queried for discovery.
 */
public record PoiNearbySummary(
        UUID id,
        String name,
        PoiType category,
        String city,
        String locality
) {
}
