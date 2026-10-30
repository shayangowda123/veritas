package com.veritas.poi.discovery;

import com.veritas.poi.PoiType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Executes nearby-places data access against PostgreSQL/PostGIS, following the Phase 1 conventions.
 *
 * <p>Two responsibilities only:</p>
 * <ul>
 *     <li>{@link #findPublishedPropertyCentre(UUID)} resolves the search centre from a
 *         <em>published</em> property in a single join query. The published-status literal is a fixed
 *         constant, and the property id is bound as a parameter, so nothing user-controlled is
 *         concatenated. Absent published listing yields {@link Optional#empty()}.</li>
 *     <li>{@link #search(PoiNearbyCriteria, Pageable)} runs the content query and a matching count,
 *         binding every content and count parameter, applying bounded pagination via
 *         limit/offset (never string concatenation), and mapping only projected safe columns.</li>
 * </ul>
 * POI geometry, address and ingestion metadata are never selected.
 */
@Repository
public class PoiNearbyRepository {

    private static final String PUBLISHED_CENTRE_SQL = """
            SELECT ST_Y(p.location::geometry), ST_X(p.location::geometry)
            FROM listings l
            JOIN properties p ON p.id = l.property_id
            WHERE p.id = :propertyId AND l.status = 'PUBLISHED'
            LIMIT 1
            """;

    private final EntityManager entityManager;
    private final PoiNearbyQueryBuilder queryBuilder;

    public PoiNearbyRepository(EntityManager entityManager, PoiNearbyQueryBuilder queryBuilder) {
        this.entityManager = entityManager;
        this.queryBuilder = queryBuilder;
    }

    public Optional<PoiNearbyCentre> findPublishedPropertyCentre(UUID propertyId) {
        Query query = entityManager.createNativeQuery(PUBLISHED_CENTRE_SQL);
        query.setParameter("propertyId", propertyId);

        List<?> rows = query.getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = (Object[]) rows.get(0);
        Double latitude = toDouble(row[0]);
        Double longitude = toDouble(row[1]);
        if (latitude == null || longitude == null) {
            return Optional.empty();
        }
        return Optional.of(new PoiNearbyCentre(latitude, longitude));
    }

    public Page<PoiNearbyRow> search(PoiNearbyCriteria criteria, Pageable pageable) {
        if (!criteria.hasCentre()) {
            // Defensive: the service only calls this after a published centre has been resolved.
            throw new IllegalStateException("Nearby search requires a resolved property centre");
        }
        PoiNearbyQueryBuilder.BuiltQuery built = queryBuilder.build(criteria);

        Query contentQuery = entityManager.createNativeQuery(built.contentSql());
        built.contentParams().forEach(contentQuery::setParameter);
        contentQuery.setMaxResults(pageable.getPageSize());
        contentQuery.setFirstResult((int) pageable.getOffset());

        @SuppressWarnings("unchecked")
        List<Object[]> rows = contentQuery.getResultList();
        List<PoiNearbyRow> results = rows.stream().map(this::mapRow).toList();

        Query countQuery = entityManager.createNativeQuery(built.countSql());
        built.countParams().forEach(countQuery::setParameter);
        long total = ((Number) countQuery.getSingleResult()).longValue();

        Pageable requestPage = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return new PageImpl<>(results, requestPage, total);
    }

    private PoiNearbyRow mapRow(Object[] row) {
        return new PoiNearbyRow(
                toUuid(row[0]),
                (String) row[1],
                toType((String) row[2]),
                (String) row[3],
                (String) row[4],
                toDouble(row[5])
        );
    }

    private UUID toUuid(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(value.toString());
    }

    private PoiType toType(String value) {
        return value == null ? null : PoiType.valueOf(value);
    }

    private Double toDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }
}
