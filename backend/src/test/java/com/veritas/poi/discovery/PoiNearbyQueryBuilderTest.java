package com.veritas.poi.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the security-critical nearby-places SQL generation. These run without a
 * database so radius handling, category expansion, whitelist ordering, the deterministic
 * tie-breaker, projection privacy and count-parameter completeness can be asserted deterministically.
 */
class PoiNearbyQueryBuilderTest {

    private final PoiNearbyQueryBuilder builder = new PoiNearbyQueryBuilder();

    /**
     * Extracts named parameters ({@code :name}) while the negative lookbehind {@code (?<!:)}
     * ignores PostgreSQL casts such as {@code ::geography}.
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

    private static PoiNearbyCriteria criteria(Double radius, List<String> categories, String sortBy) {
        return new PoiNearbyCriteria(12.97, 77.59, radius, categories, sortBy);
    }

    @Nested
    @DisplayName("Radius")
    class Radius {

        @Test
        @DisplayName("default radius is applied when none is supplied")
        void defaultRadius() {
            var built = builder.build(criteria(null, List.of(), "distance"));
            assertEquals(2000.0, built.contentParams().get("radiusInMeters"));
            assertEquals(2000.0, built.countParams().get("radiusInMeters"));
        }

        @Test
        @DisplayName("custom radius is bound, never concatenated")
        void customRadiusBound() {
            var built = builder.build(criteria(3500.0, List.of(), "distance"));
            assertTrue(built.contentSql().contains("ST_DWithin(p.location,"));
            assertTrue(built.contentSql().contains(":radiusInMeters"));
            assertEquals(3500.0, built.contentParams().get("radiusInMeters"));
            assertFalse(built.contentSql().contains("3500"));
        }

        @Test
        @DisplayName("ST_DWithin is always present because a centre always exists")
        void spatialFilterAlwaysPresent() {
            var built = builder.build(criteria(2000.0, List.of(), "distance"));
            assertTrue(built.contentSql().contains("ST_DWithin("));
            assertTrue(built.countSql().contains("ST_DWithin("));
            assertTrue(built.contentSql().contains("::geography"));
        }
    }

    @Nested
    @DisplayName("Category filtering")
    class Categories {

        @Test
        @DisplayName("no category clause is emitted when categories are absent")
        void omitsAbsentCategories() {
            var built = builder.build(criteria(2000.0, List.of(), "distance"));
            assertFalse(built.contentSql().contains("p.type IN"));
            assertFalse(built.contentSql().contains(":category0"));
            assertFalse(built.contentParams().containsKey("category0"));

            var nullBuilt = builder.build(criteria(2000.0, null, "distance"));
            assertFalse(nullBuilt.contentSql().contains("p.type IN"));
        }

        @Test
        @DisplayName("a single category becomes one indexed placeholder")
        void singleCategory() {
            var built = builder.build(criteria(2000.0, List.of("SCHOOL"), "distance"));
            assertTrue(built.contentSql().contains("p.type IN (:category0)"));
            assertEquals("SCHOOL", built.contentParams().get("category0"));
        }

        @Test
        @DisplayName("multiple categories expand to distinct placeholders, all bound")
        void multipleCategoriesExpand() {
            var built = builder.build(criteria(2000.0, List.of("SCHOOL", "COLLEGE", "PARK"), "distance"));
            // Explicit placeholders rather than a single collection parameter.
            assertTrue(built.contentSql().contains("p.type IN (:category0, :category1, :category2)"));
            assertEquals("SCHOOL", built.contentParams().get("category0"));
            assertEquals("COLLEGE", built.contentParams().get("category1"));
            assertEquals("PARK", built.contentParams().get("category2"));
            // Exactly three category placeholders — none missing, none extra.
            assertEquals(3, built.contentParams().keySet().stream()
                    .filter(k -> k.startsWith("category")).count());
        }

        @Test
        @DisplayName("category values are never concatenated into SQL")
        void categoriesAreBoundNotConcatenated() {
            var built = builder.build(criteria(2000.0, List.of("SCHOOL"), "distance"));
            assertFalse(built.contentSql().contains("'SCHOOL'"));
            assertTrue(built.contentSql().contains(":category0"));
        }
    }

    @Nested
    @DisplayName("Ordering whitelist")
    class Ordering {

        @Test
        @DisplayName("distance uses KNN as the primary key with the tie-breaker appended")
        void distanceKnnOrdering() {
            var sql = builder.build(criteria(2000.0, List.of(), "distance")).contentSql();
            assertTrue(sql.contains("ORDER BY p.location <-> "));
            // The tie-breaker is appended after the geography cast, never replacing KNN.
            assertTrue(sql.contains(")::geography, p.id ASC"));
            assertTrue(sql.endsWith(", p.id ASC"));
        }

        @Test
        @DisplayName("name sorts alphabetically ascending then tie-breaks on id")
        void nameOrdering() {
            var sql = builder.build(criteria(2000.0, List.of(), "name")).contentSql();
            assertTrue(sql.contains("ORDER BY p.name ASC, p.id ASC"));
            assertFalse(sql.contains("<->"));
        }

        @Test
        @DisplayName("default (null) sort falls back to distance KNN")
        void defaultIsDistance() {
            var sql = builder.build(criteria(2000.0, List.of(), null)).contentSql();
            assertTrue(sql.contains("ORDER BY p.location <-> "));
        }

        @Test
        @DisplayName("unsupported sort is rejected rather than concatenated")
        void unsupportedSortRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> builder.build(criteria(2000.0, List.of(), "DROP TABLE")));
        }
    }

    @Nested
    @DisplayName("Projection and count shape")
    class Projection {

        @Test
        @DisplayName("content projects distance and only safe columns")
        void contentProjection() {
            var sql = builder.build(criteria(2000.0, List.of(), "distance")).contentSql();
            // The projection list is exactly the safe columns; the geometry only ever appears inside
            // spatial functions (ST_Distance/ST_DWithin), never as a selected output column.
            assertTrue(sql.startsWith(
                    "SELECT p.id, p.name, p.type, p.city, p.locality, ST_Distance(p.location,"));
            assertFalse(sql.contains("p.address"));
            assertFalse(sql.contains("p.source"));
            assertFalse(sql.contains("p.external_id"));
            assertFalse(sql.contains("p.licence"));
            assertFalse(sql.contains("p.created_at"));
        }

        @Test
        @DisplayName("count query has no ordering and no distance projection")
        void countShape() {
            var built = builder.build(criteria(2000.0, List.of("SCHOOL"), "distance"));
            assertFalse(built.countSql().contains("ORDER BY"));
            assertFalse(built.countSql().contains("ST_Distance"));
            assertTrue(built.countSql().startsWith("SELECT COUNT(*) FROM pois p"));
        }
    }

    @Nested
    @DisplayName("Count query parameter completeness")
    class CountParameters {

        @Test
        @DisplayName("count binds every referenced parameter including centre, radius and categories")
        void countBindsAllReferencedParams() {
            var built = builder.build(criteria(2500.0, List.of("SCHOOL", "COLLEGE"), "distance"));

            Set<String> referenced = namedParameters(built.countSql());
            assertTrue(referenced.containsAll(Set.of(
                    "longitude", "latitude", "radiusInMeters", "category0", "category1")));
            // The ::geography cast must never be mistaken for a named parameter.
            assertFalse(referenced.contains("geography"));

            for (String param : referenced) {
                assertTrue(built.countParams().containsKey(param),
                        "count SQL references :" + param + " but it is not bound in countParams");
            }
            assertEquals(77.59, built.countParams().get("longitude"));
            assertEquals(12.97, built.countParams().get("latitude"));
            assertEquals(2500.0, built.countParams().get("radiusInMeters"));
            assertEquals("SCHOOL", built.countParams().get("category0"));
            assertEquals("COLLEGE", built.countParams().get("category1"));
        }

        @Test
        @DisplayName("content query also binds every referenced parameter")
        void contentBindsAllReferencedParams() {
            var built = builder.build(criteria(1000.0, List.of("HOSPITAL", "PARK", "METRO"), "name"));
            for (String param : namedParameters(built.contentSql())) {
                assertTrue(built.contentParams().containsKey(param),
                        "content SQL references :" + param + " but it is not bound in contentParams");
            }
        }

        @Test
        @DisplayName("no-centre scenario is represented by params that the count still references")
        void noUnusedSpatialParamsWhenCategoriesOmitted() {
            var built = builder.build(criteria(2000.0, List.of(), "name"));
            Set<String> referenced = namedParameters(built.countSql());
            assertEquals(Set.of("longitude", "latitude", "radiusInMeters"), referenced);
            for (String param : referenced) {
                assertTrue(built.countParams().containsKey(param));
            }
        }
    }
}
