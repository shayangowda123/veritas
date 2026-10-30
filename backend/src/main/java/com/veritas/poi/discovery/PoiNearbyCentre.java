package com.veritas.poi.discovery;

/**
 * Server-resolved search centre for a published property. Latitude and longitude are read from the
 * property's {@code geography(Point,4326)} column and are used only to build the spatial query;
 * they are never returned in any public response.
 */
public record PoiNearbyCentre(Double latitude, Double longitude) {
}
