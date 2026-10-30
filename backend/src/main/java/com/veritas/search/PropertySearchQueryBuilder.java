package com.veritas.search;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the parameterized discovery SQL from {@link PropertySearchCriteria}.
 *
 * <p>This component is deliberately free of JDBC/JPA so the security-critical SQL can be unit
 * tested directly. Guarantees:</p>
 * <ul>
 *     <li>Discovery always begins from listings restricted to {@code PUBLISHED} listings.</li>
 *     <li>All user-supplied values are bound as named parameters; nothing is concatenated.</li>
 *     <li>{@code ORDER BY} columns are produced only from a fixed whitelist, never raw input.</li>
 *     <li>Only publicly safe columns are projected (exact address, coordinates and owner id are
 *         never selected).</li>
 *     <li>Image counts are produced by a single correlated subquery, not per-result queries.</li>
 *     <li>Spatial filtering uses PostGIS {@code ST_DWithin} over the geography column and KNN
 *         ordering uses the {@code <->} operator, both of which leverage the existing GiST index.</li>
 *     <li>Every parameter referenced by the count SQL (including the search-centre coordinates used
 *         by {@code ST_DWithin}) is bound in the count parameter map.</li>
 *     <li>Every {@code ORDER BY} ends with a constant {@code l.id} tie-breaker, so pagination is
 *         deterministic even when the primary sort column is tied.</li>
 * </ul>
 */
@Component
public class PropertySearchQueryBuilder {

    private static final String SELECT_COLUMNS = """
            SELECT p.id,
                   p.property_type,
                   p.locality,
                   p.city,
                   p.location_verification_status,
                   (SELECT COUNT(*) FROM property_images pi WHERE pi.property_id = p.id),
                   p.created_at""";

    private static final String FROM_AND_JOIN = """
            FROM listings l
            JOIN properties p ON p.id = l.property_id""";

    /** The reusable search-centre geography expression. Longitude is X, latitude is Y. */
    private static final String CENTRE_GEOMETRY =
            "ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography";

    /**
     * A constant, never user-controlled tie-breaker appended to every {@code ORDER BY}. The result
     * rows are {@code listings} joined to {@code properties}, so the listing primary key
     * {@code l.id} uniquely identifies each row (a single property may back several published
     * listings) and makes pagination deterministic across pages.
     */
    private static final String ORDER_TIE_BREAKER = ", l.id ASC";

    public BuiltQuery build(PropertySearchCriteria criteria) {
        StringBuilder where = new StringBuilder("WHERE l.status = :status");
        Map<String, Object> whereParams = new LinkedHashMap<>();
        whereParams.put("status", "PUBLISHED");

        if (criteria.minRent() != null) {
            where.append(" AND l.rent >= :minRent");
            whereParams.put("minRent", criteria.minRent());
        }
        if (criteria.maxRent() != null) {
            where.append(" AND l.rent <= :maxRent");
            whereParams.put("maxRent", criteria.maxRent());
        }
        if (criteria.bhk() != null) {
            where.append(" AND l.bhk = :bhk");
            whereParams.put("bhk", criteria.bhk());
        }
        if (hasText(criteria.city())) {
            where.append(" AND p.city = :city");
            whereParams.put("city", criteria.city().trim());
        }
        if (hasText(criteria.locality())) {
            where.append(" AND p.locality = :locality");
            whereParams.put("locality", criteria.locality().trim());
        }
        if (hasText(criteria.furnishingStatus())) {
            where.append(" AND l.furnishing_status = :furnishingStatus");
            whereParams.put("furnishingStatus", criteria.furnishingStatus());
        }
        if (criteria.availableFrom() != null) {
            where.append(" AND l.available_from >= :availableFrom");
            whereParams.put("availableFrom", criteria.availableFrom());
        }
        if (criteria.petsAllowed() != null) {
            where.append(" AND l.pets_allowed = :petsAllowed");
            whereParams.put("petsAllowed", criteria.petsAllowed());
        }
        if (criteria.parkingAvailable() != null) {
            where.append(" AND l.parking_available = :parkingAvailable");
            whereParams.put("parkingAvailable", criteria.parkingAvailable());
        }
        if (criteria.bachelorsAllowed() != null) {
            where.append(" AND l.bachelors_allowed = :bachelorsAllowed");
            whereParams.put("bachelorsAllowed", criteria.bachelorsAllowed());
        }
        if (criteria.hasRadiusFilter()) {
            where.append(" AND ST_DWithin(p.location, ").append(CENTRE_GEOMETRY).append(", :radiusInMeters)");
            whereParams.put("radiusInMeters", criteria.radiusInMeters());
            // The shared WHERE now references the search-centre coordinates, so BOTH the content
            // and the count query must bind them. hasRadiusFilter() implies a centre, so these
            // values are guaranteed non-null here.
            whereParams.put("longitude", criteria.longitude());
            whereParams.put("latitude", criteria.latitude());
        }

        String whereSql = where.toString();

        // ----- content query -----
        boolean hasCentre = criteria.hasCentre();
        StringBuilder contentSql = new StringBuilder(SELECT_COLUMNS);
        if (hasCentre) {
            // distance (metres) is projected as an additional, publicly safe column
            contentSql.append(", ST_Distance(p.location, ").append(CENTRE_GEOMETRY).append(")");
        }
        contentSql.append(' ').append(FROM_AND_JOIN).append(' ').append(whereSql);
        contentSql.append(' ').append(buildOrderBy(criteria));

        Map<String, Object> contentParams = new LinkedHashMap<>(whereParams);
        if (hasCentre) {
            contentParams.putIfAbsent("longitude", criteria.longitude());
            contentParams.putIfAbsent("latitude", criteria.latitude());
        }

        // ----- count query (no projection of distance, no ordering) -----
        String countSql = "SELECT COUNT(*) " + FROM_AND_JOIN + " " + whereSql;

        return new BuiltQuery(
                contentSql.toString(),
                countSql,
                contentParams,
                new LinkedHashMap<>(whereParams),
                hasCentre
        );
    }

    private String buildOrderBy(PropertySearchCriteria criteria) {
        String sortBy = criteria.sortBy();
        if (sortBy == null) {
            // Default: nearest-first when a centre is supplied, otherwise newest listings first.
            if (criteria.hasCentre()) {
                return "ORDER BY p.location <-> " + CENTRE_GEOMETRY + ORDER_TIE_BREAKER;
            }
            return "ORDER BY p.created_at DESC" + ORDER_TIE_BREAKER;
        }

        return switch (sortBy) {
            case "distance" -> {
                if (!criteria.hasCentre()) {
                    throw new IllegalArgumentException("Distance ordering requires a search centre");
                }
                // KNN GiST-accelerated nearest-first ordering, then the deterministic tie-breaker.
                yield "ORDER BY p.location <-> " + CENTRE_GEOMETRY + ORDER_TIE_BREAKER;
            }
            case "createdAt" -> "ORDER BY p.created_at " + direction(criteria.sortDirection(), "DESC") + ORDER_TIE_BREAKER;
            case "rent" -> "ORDER BY l.rent " + direction(criteria.sortDirection(), "ASC") + ORDER_TIE_BREAKER;
            case "availableFrom" -> "ORDER BY l.available_from " + direction(criteria.sortDirection(), "ASC") + ORDER_TIE_BREAKER;
            case "bhk" -> "ORDER BY l.bhk " + direction(criteria.sortDirection(), "ASC") + ORDER_TIE_BREAKER;
            // Unreachable: the request DTO restricts sortBy to the supported whitelist.
            default -> throw new IllegalArgumentException("Unsupported sortBy value: " + sortBy);
        };
    }

    private String direction(String rawDirection, String defaultDirection) {
        if (rawDirection == null) {
            return defaultDirection;
        }
        return "asc".equalsIgnoreCase(rawDirection) ? "ASC" : "DESC";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Immutable SQL blueprint produced by the builder.
     *
     * @param contentSql       projection query with the ordering clause applied
     * @param countSql         matching count query (same FROM/WHERE, no ordering)
     * @param contentParams    bound parameters for the content query
     * @param countParams      bound parameters for the count query (subset of content params)
     * @param hasDistanceColumn whether the content query projects distance as a trailing column
     */
    public record BuiltQuery(
            String contentSql,
            String countSql,
            Map<String, Object> contentParams,
            Map<String, Object> countParams,
            boolean hasDistanceColumn
    ) {
    }
}
