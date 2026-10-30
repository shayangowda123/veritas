package com.veritas.search;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Normalized, internal search criteria detached from the web layer. Built by
 * {@link com.veritas.search.PropertySearchService} from a validated request and consumed by
 * {@link PropertySearchQueryBuilder}. Only fields that map to a real domain column are represented.
 */
public record PropertySearchCriteria(
        BigDecimal minRent,
        BigDecimal maxRent,
        Integer bhk,
        String city,
        String locality,
        String furnishingStatus,
        LocalDate availableFrom,
        Boolean petsAllowed,
        Boolean parkingAvailable,
        Boolean bachelorsAllowed,
        Double latitude,
        Double longitude,
        Double radiusInMeters,
        String sortBy,
        String sortDirection
) {

    public boolean hasCentre() {
        return latitude != null && longitude != null;
    }

    /** A radius filter is only applied when a search centre is also present. */
    public boolean hasRadiusFilter() {
        return hasCentre() && radiusInMeters != null;
    }
}
