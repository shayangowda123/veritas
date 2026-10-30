package com.veritas.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the security-critical SQL generation. These run without any database so the
 * published-only restriction, parameter binding (no concatenation), whitelist ordering, projection
 * privacy, spatial clause and matching count can all be asserted deterministically.
 */
class PropertySearchQueryBuilderTest {

    private final PropertySearchQueryBuilder builder = new PropertySearchQueryBuilder();

    /**
     * Extracts Hibernate/JPA named parameters ({@code :name}) from raw SQL. The negative lookbehind
     * {@code (?<!:)} deliberately ignores PostgreSQL cast operators such as {@code ::geography}, so
     * a cast is never mistaken for a parameter reference.
     */
    private static final Pattern NAMED_PARAM = Pattern.compile("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)");

    private static Set<String> namedParameters(String sql) {
        Set<String> names = new HashSet<>();
        Matcher matcher = NAMED_PARAM.matcher(sql);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static PropertySearchCriteria empty() {
        return new PropertySearchCriteria(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null
        );
    }

    private static PropertySearchCriteria withRent(BigDecimal min, BigDecimal max) {
        return new PropertySearchCriteria(
                min, max, null, null, null, null, null, null, null, null,
                null, null, null, null, null
        );
    }

    private static PropertySearchCriteria withCentre(Double lat, Double lon, Double radius) {
        return new PropertySearchCriteria(
                null, null, null, null, null, null, null, null, null, null,
                lat, lon, radius, null, null
        );
    }

    private static PropertySearchCriteria withSort(String sortBy, String direction) {
        return new PropertySearchCriteria(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, sortBy, direction
        );
    }

    @Nested
    @DisplayName("Published-only restriction")
    class PublishedOnly {

        @Test
        @DisplayName("content and count always bind listings status to PUBLISHED")
        void alwaysRestrictsToPublished() {
            var built = builder.build(empty());

            assertTrue(built.contentSql().contains("WHERE l.status = :status"));
            assertTrue(built.countSql().contains("WHERE l.status = :status"));
            assertEquals("PUBLISHED", built.contentParams().get("status"));
            assertEquals("PUBLISHED", built.countParams().get("status"));
        }

        @Test
        @DisplayName("search begins from listings joined to properties")
        void beginsFromListingJoin() {
            var built = builder.build(empty());
            assertTrue(built.contentSql().contains("FROM listings l"));
            assertTrue(built.contentSql().contains("JOIN properties p ON p.id = l.property_id"));
        }
    }

    @Nested
    @DisplayName("Optional business filters")
    class Filters {

        @Test
        @DisplayName("no optional filter clause is emitted when values are absent")
        void omitsAbsentFilters() {
            var built = builder.build(empty());
            String sql = built.contentSql();
            assertFalse(sql.contains(":minRent"));
            assertFalse(sql.contains(":bhk"));
            assertFalse(sql.contains(":city"));
            assertFalse(sql.contains("ST_DWithin"));
        }

        @Test
        @DisplayName("rent bounds are bound, never concatenated")
        void bindsRentBounds() {
            var built = builder.build(withRent(new BigDecimal("10000"), new BigDecimal("50000")));
            assertTrue(built.contentSql().contains("l.rent >= :minRent"));
            assertTrue(built.contentSql().contains("l.rent <= :maxRent"));
            assertEquals(new BigDecimal("10000"), built.contentParams().get("minRent"));
            assertEquals(new BigDecimal("50000"), built.contentParams().get("maxRent"));
            assertFalse(built.contentSql().contains("10000"));
        }

        @Test
        @DisplayName("bhk, city and locality map to their real columns")
        void bindsCoreColumns() {
            PropertySearchCriteria c = new PropertySearchCriteria(
                    null, null, 2, "Bengaluru", "Indiranagar", null, null, null, null, null,
                    null, null, null, null, null
            );
            var built = builder.build(c);
            assertTrue(built.contentSql().contains("l.bhk = :bhk"));
            assertTrue(built.contentSql().contains("p.city = :city"));
            assertTrue(built.contentSql().contains("p.locality = :locality"));
            assertEquals("Bengaluru", built.contentParams().get("city"));
            assertEquals("Indiranagar", built.contentParams().get("locality"));
        }

        @Test
        @DisplayName("furnishing, availability and boolean flags are appended when present")
        void bindsListingAttributes() {
            PropertySearchCriteria c = new PropertySearchCriteria(
                    null, null, null, null, null, "SEMI_FURNISHED", LocalDate.of(2026, 1, 1),
                    true, true, false,
                    null, null, null, null, null
            );
            var built = builder.build(c);
            assertTrue(built.contentSql().contains("l.furnishing_status = :furnishingStatus"));
            assertTrue(built.contentSql().contains("l.available_from >= :availableFrom"));
            assertTrue(built.contentSql().contains("l.pets_allowed = :petsAllowed"));
            assertTrue(built.contentSql().contains("l.parking_available = :parkingAvailable"));
            assertTrue(built.contentSql().contains("l.bachelors_allowed = :bachelorsAllowed"));
            assertEquals("SEMI_FURNISHED", built.contentParams().get("furnishingStatus"));
        }

        @Test
        @DisplayName("blank city/locality are treated as absent")
        void ignoresBlankText() {
            PropertySearchCriteria c = new PropertySearchCriteria(
                    null, null, null, "   ", "", null, null, null, null, null,
                    null, null, null, null, null
            );
            var built = builder.build(c);
            assertFalse(built.contentSql().contains(":city"));
            assertFalse(built.contentSql().contains(":locality"));
        }
    }

    @Nested
    @DisplayName("PostGIS spatial clause")
    class Spatial {

        @Test
        @DisplayName("ST_DWithin is applied only with a centre and radius")
        void radiusRequiresCentre() {
            var noCentre = builder.build(withCentre(null, null, 3000.0));
            assertFalse(noCentre.contentSql().contains("ST_DWithin"));

            var withCentre = builder.build(withCentre(12.97, 77.59, 3000.0));
            assertTrue(withCentre.contentSql().contains("ST_DWithin(p.location,"));
            assertTrue(withCentre.contentSql().contains("::geography"));
            assertEquals(3000.0, withCentre.contentParams().get("radiusInMeters"));
        }

        @Test
        @DisplayName("distance column is projected and centre bound when a centre is supplied")
        void projectsDistanceWithCentre() {
            var built = builder.build(withCentre(12.97, 77.59, null));
            assertTrue(built.contentSql().contains("ST_Distance(p.location,"));
            assertTrue(built.hasDistanceColumn());
            assertEquals(12.97, built.contentParams().get("latitude"));
            assertEquals(77.59, built.contentParams().get("longitude"));
        }

        @Test
        @DisplayName("count query never projects distance nor orders")
        void countQueryHasNoDistanceOrOrder() {
            var built = builder.build(withCentre(12.97, 77.59, 3000.0));
            assertFalse(built.countSql().contains("ST_Distance"));
            assertFalse(built.countSql().contains("ORDER BY"));
            // count params exclude the ordering-only centre reuse but keep where-bound centre params
            assertEquals("PUBLISHED", built.countParams().get("status"));
        }
    }

    @Nested
    @DisplayName("Ordering whitelist")
    class Ordering {

        @Test
        @DisplayName("default ordering is nearest-first when a centre exists")
        void defaultNearestFirst() {
            var built = builder.build(withCentre(12.97, 77.59, null));
            assertTrue(built.contentSql().contains("ORDER BY p.location <->"));
        }

        @Test
        @DisplayName("default ordering without a centre is newest first")
        void defaultNewestFirst() {
            var built = builder.build(empty());
            assertTrue(built.contentSql().contains("ORDER BY p.created_at DESC"));
        }

        @Test
        @DisplayName("whitelisted sorts map to concrete columns with direction")
        void whitelistSorts() {
            assertTrue(builder.build(withSort("rent", "desc")).contentSql().contains("ORDER BY l.rent DESC"));
            assertTrue(builder.build(withSort("createdAt", "asc")).contentSql().contains("ORDER BY p.created_at ASC"));
            assertTrue(builder.build(withSort("bhk", null)).contentSql().contains("ORDER BY l.bhk ASC"));
            assertTrue(builder.build(withSort("availableFrom", "desc")).contentSql().contains("ORDER BY l.available_from DESC"));
        }

        @Test
        @DisplayName("distance sort requires a centre and otherwise is rejected")
        void distanceSortRequiresCentre() {
            assertThrows(IllegalArgumentException.class,
                    () -> builder.build(withSort("distance", "asc")));
        }

        @Test
        @DisplayName("unsupported sort value is rejected rather than concatenated")
        void unsupportedSortRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> builder.build(withSort("dropTable", "asc")));
        }
    }

    @Nested
    @DisplayName("Count query parameter completeness")
    class CountParameters {

        @Test
        @DisplayName("radius count query binds every referenced named parameter (regression for unbound centre)")
        void radiusCountQueryBindsAllReferencedParams() {
            PropertySearchCriteria criteria = withCentre(12.97, 77.59, 3000.0);
            var built = builder.build(criteria);

            Set<String> referenced = namedParameters(built.countSql());
            // ST_DWithin embeds the centre coordinates into the shared WHERE, so the count SQL
            // legitimately references them and they MUST be bound (this failed before the fix).
            assertTrue(referenced.contains("longitude"));
            assertTrue(referenced.contains("latitude"));
            assertTrue(referenced.contains("radiusInMeters"));
            assertTrue(referenced.contains("status"));

            for (String param : referenced) {
                assertTrue(built.countParams().containsKey(param),
                        "count SQL references :" + param + " but it is not bound in countParams");
            }

            assertEquals(77.59, built.countParams().get("longitude"));
            assertEquals(12.97, built.countParams().get("latitude"));
            assertEquals(3000.0, built.countParams().get("radiusInMeters"));
            assertEquals("PUBLISHED", built.countParams().get("status"));

            // The ::geography cast must never be mistaken for a named parameter.
            assertFalse(referenced.contains("geography"));

            // Content bindings remain complete and correct as well.
            for (String param : namedParameters(built.contentSql())) {
                assertTrue(built.contentParams().containsKey(param),
                        "content SQL references :" + param + " but it is not bound in contentParams");
            }
            assertEquals(77.59, built.contentParams().get("longitude"));
            assertEquals(12.97, built.contentParams().get("latitude"));
        }

        @Test
        @DisplayName("combined radius + business filters bind every referenced count parameter")
        void combinedFiltersBindAllCountParams() {
            PropertySearchCriteria criteria = new PropertySearchCriteria(
                    new BigDecimal("10000"), new BigDecimal("50000"), 2, "Bengaluru", null, null, null,
                    null, null, null,
                    12.97, 77.59, 3000.0, null, null
            );
            var built = builder.build(criteria);

            Set<String> referenced = namedParameters(built.countSql());
            for (String param : referenced) {
                assertTrue(built.countParams().containsKey(param),
                        "count SQL references :" + param + " but it is not bound in countParams");
            }
            assertTrue(referenced.containsAll(Set.of(
                    "status", "minRent", "maxRent", "bhk", "city", "radiusInMeters", "longitude", "latitude")));
            assertEquals(77.59, built.countParams().get("longitude"));
            assertEquals(12.97, built.countParams().get("latitude"));
        }

        @Test
        @DisplayName("non-radius searches bind all referenced params and never bind unused coordinates")
        void nonRadiusSearchBindsAllAndOmitsUnusedCoordinates() {
            // Centre supplied but no radius: coordinates belong to the projection/ordering only, not
            // the WHERE, so the count must neither reference nor bind them (no unused bindings).
            var centreNoRadius = builder.build(withCentre(12.97, 77.59, null));
            Set<String> referenced = namedParameters(centreNoRadius.countSql());
            assertFalse(referenced.contains("longitude"));
            assertFalse(referenced.contains("latitude"));
            assertFalse(centreNoRadius.countParams().containsKey("longitude"));
            assertFalse(centreNoRadius.countParams().containsKey("latitude"));
            for (String param : referenced) {
                assertTrue(centreNoRadius.countParams().containsKey(param));
            }

            // A bare search binds exactly what its count SQL references.
            var plain = builder.build(empty());
            for (String param : namedParameters(plain.countSql())) {
                assertTrue(plain.countParams().containsKey(param));
            }
            assertEquals(Set.of("status"), namedParameters(plain.countSql()));
        }
    }

    @Nested
    @DisplayName("Deterministic ordering tie-breaker")
    class DeterministicOrdering {

        @Test
        @DisplayName("rent ordering appends the l.id tie-breaker in both directions")
        void rentTieBroken() {
            var asc = builder.build(withSort("rent", "asc")).contentSql();
            var desc = builder.build(withSort("rent", "desc")).contentSql();
            assertTrue(asc.contains("ORDER BY l.rent ASC, l.id ASC"));
            assertTrue(desc.contains("ORDER BY l.rent DESC, l.id ASC"));
            assertTrue(asc.endsWith(", l.id ASC"));
            assertTrue(desc.endsWith(", l.id ASC"));
        }

        @Test
        @DisplayName("createdAt ordering appends the l.id tie-breaker in both directions")
        void createdAtTieBroken() {
            assertTrue(builder.build(withSort("createdAt", "asc")).contentSql()
                    .contains("ORDER BY p.created_at ASC, l.id ASC"));
            assertTrue(builder.build(withSort("createdAt", "desc")).contentSql()
                    .contains("ORDER BY p.created_at DESC, l.id ASC"));
        }

        @Test
        @DisplayName("bhk ordering appends the l.id tie-breaker")
        void bhkTieBroken() {
            var sql = builder.build(withSort("bhk", "asc")).contentSql();
            assertTrue(sql.contains("ORDER BY l.bhk ASC, l.id ASC"));
            assertTrue(sql.endsWith(", l.id ASC"));
        }

        @Test
        @DisplayName("availableFrom ordering appends the l.id tie-breaker in both directions")
        void availableFromTieBroken() {
            assertTrue(builder.build(withSort("availableFrom", "asc")).contentSql()
                    .contains("ORDER BY l.available_from ASC, l.id ASC"));
            assertTrue(builder.build(withSort("availableFrom", "desc")).contentSql()
                    .contains("ORDER BY l.available_from DESC, l.id ASC"));
        }

        @Test
        @DisplayName("KNN distance ordering keeps nearest-first primary then appends tie-breaker")
        void distanceTieBrokenPreservesKnn() {
            PropertySearchCriteria criteria = new PropertySearchCriteria(
                    null, null, null, null, null, null, null, null, null, null,
                    12.97, 77.59, null, "distance", "asc"
            );
            var sql = builder.build(criteria).contentSql();
            assertTrue(sql.contains("ORDER BY p.location <-> "));
            // The tie-breaker is appended after the geography cast rather than replacing KNN.
            assertTrue(sql.contains(")::geography, l.id ASC"));
            assertTrue(sql.endsWith(", l.id ASC"));
        }

        @Test
        @DisplayName("default orderings (with and without a centre) are tie-broken")
        void defaultOrderingsTieBroken() {
            assertTrue(builder.build(empty()).contentSql()
                    .endsWith("ORDER BY p.created_at DESC, l.id ASC"));
            var withCentre = builder.build(withCentre(12.97, 77.59, null)).contentSql();
            assertTrue(withCentre.contains("ORDER BY p.location <-> "));
            assertTrue(withCentre.endsWith(", l.id ASC"));
        }

        @Test
        @DisplayName("the tie-breaker is not influenced by user-supplied sort input")
        void tieBreakerNotUserControlled() {
            // A hostile sortDirection cannot inject a second ordering column: only ASC/DESC is ever
            // emitted and the tie-breaker is always the constant ", l.id ASC".
            var sql = builder.build(withSort("rent", "DESC; DROP TABLE properties")).contentSql();
            assertTrue(sql.contains("ORDER BY l.rent DESC, l.id ASC"));
            assertFalse(sql.contains("DROP TABLE"));
        }
    }
}
