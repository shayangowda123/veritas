package com.veritas.verification.geocoding.model;

/**
 * Structured address query encapsulation for geocoding requests.
 */
public record AddressQuery(
        String address,
        String locality,
        String city
) {

    public boolean isValid() {
        return address != null && !address.trim().isBlank();
    }

    /**
     * Formats structured address fields into a single sanitized query string.
     */
    public String toFormattedQuery() {
        StringBuilder sb = new StringBuilder();
        if (address != null && !address.trim().isBlank()) {
            sb.append(address.trim());
        }
        if (locality != null && !locality.trim().isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(locality.trim());
        }
        if (city != null && !city.trim().isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(city.trim());
        }
        return sb.toString();
    }
}
