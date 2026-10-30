package com.veritas.poi.discovery;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the parameterized nearby-places SQL from {@link PoiNearbyCriteria}.
 *
 * <p>Deliberately free of JDBC/JPA so the security-critical SQL can be unit tested directly.
 * Guarantees mirror the Phase 1 discovery builder conventions:</p>
 * <ul>
 *     <li>All user-influenced values (radius, categories) and the server-resolved centre are bound
 *         as named parameters; nothing is concatenated.</li>
 *     <li>Radius filtering uses PostGIS {@code ST_DWithin} over the geography column; distance is
 *         projected with {@code ST_Distance} so values are metres; distance ordering uses the KNN
 *         {@code <->} operator, leveraging the existing {@code idx_pois_location} GiST index.</li>
 *     <li>Category filtering is expanded into an explicit indexed placeholder list
 *         ({@code :category0, :category1, ...}) rather than relying on a native {@code IN} collection
 *         parameter, because Hibernate native-query collection expansion is not guaranteed across
 *         configurations. Placeholder names derive from the list index, never from input.</li>
 *     <li>{@code ORDER BY} is produced only from a fixed whitelist; every branch ends with the
 *         constant {@code p.id} tie-breaker for deterministic pagination.</li>
 *     <li>The count query reuses the identical WHERE clause, so every parameter it references
 *         (centre, radius and categories) is present in the count parameter map.</li>
 * </ul>
 */
@Component
public class PoiNearbyQueryBuilder {

    /** Applied when no explicit radius is supplied. Matches the request DTO default. */
    private static final double DEFAULT_RADIUS_METERS = 2000.0;

    private static final String FROM = "FROM pois p";

    /** The reusable search-centre geography expression. Longitude is X, latitude is Y. */
    private static final String CENTRE_GEOMETRY =
            "ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography";

    /** A constant, never user-controlled tie-breaker appended to every {@code ORDER BY}. */
    private static final String ORDER_TIE_BREAKER = ", p.id ASC";

    public BuiltQuery build(PoiNearbyCriteria criteria) {
        // Radius is always applied (a centre always exists for this endpoint), so the WHERE clause
        // always references the centre coordinates. Content and count therefore share one parameter
        // set; both maps are populated identically to guarantee complete count binding.
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("longitude", criteria.longitude());
        params.put("latitude", criteria.latitude());
        params.put("radiusInMeters",
                criteria.radiusInMeters() != null ? criteria.radiusInMeters() : DEFAULT_RADIUS_METERS);

        StringBuilder where = new StringBuilder("WHERE ST_DWithin(p.location, ")
                .append(CENTRE_GEOMETRY)
                .append(", :radiusInMeters)");

        if (criteria.hasCategoryFilter()) {
            List<String> categories = criteria.categories();
            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < categories.size(); i++) {
                if (i > 0) {
                    placeholders.append(", ");
                }
                String parameterName = "category" + i;
                placeholders.append(':').append(parameterName);
                params.put(parameterName, categories.get(i));
            }
            where.append(" AND p.type IN (").append(placeholders).append(")");
        }

        String whereSql = where.toString();

        String contentSql = "SELECT p.id, p.name, p.type, p.city, p.locality, "
                + "ST_Distance(p.location, " + CENTRE_GEOMETRY + ") "
                + FROM + ' ' + whereSql + ' ' + buildOrderBy(criteria);

        String countSql = "SELECT COUNT(*) " + FROM + ' ' + whereSql;

        return new BuiltQuery(
                contentSql,
                countSql,
                new LinkedHashMap<>(params),
                new LinkedHashMap<>(params)
        );
    }

    private String buildOrderBy(PoiNearbyCriteria criteria) {
        String sortBy = criteria.sortBy() != null ? criteria.sortBy() : "distance";
        return switch (sortBy) {
            // KNN GiST-accelerated nearest-first ordering, then the deterministic tie-breaker.
            case "distance" -> "ORDER BY p.location <-> " + CENTRE_GEOMETRY + ORDER_TIE_BREAKER;
            case "name" -> "ORDER BY p.name ASC" + ORDER_TIE_BREAKER;
            // Unreachable via the web layer: the request DTO restricts sortBy to the whitelist.
            default -> throw new IllegalArgumentException("Unsupported sortBy value: " + sortBy);
        };
    }

    /**
     * Immutable SQL blueprint produced by the builder. The distance column is always projected
     * (a centre always exists), so no separate distance flag is required.
     *
     * @param contentSql    projection query with the ordering clause applied
     * @param countSql      matching count query (same FROM/WHERE, no ordering)
     * @param contentParams bound parameters for the content query
     * @param countParams   bound parameters for the count query (identical to content params)
     */
    public record BuiltQuery(
            String contentSql,
            String countSql,
            Map<String, Object> contentParams,
            Map<String, Object> countParams
    ) {
    }
}
