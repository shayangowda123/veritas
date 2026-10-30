package com.veritas.poi.discovery;

import com.veritas.poi.PoiType;

import java.util.UUID;

/**
 * Internal read model produced by a single nearby-discovery query row. It carries only publicly
 * safe POI attributes plus the distance (in metres) from the property centre. The POI geometry,
 * address and ingestion metadata (source, external id, source url, licence, timestamps) are never
 * selected and therefore never represented here.
 */
public record PoiNearbyRow(
        UUID id,
        String name,
        PoiType type,
        String city,
        String locality,
        Double distanceMeters
) {
}
