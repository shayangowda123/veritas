package com.veritas.search;

import com.veritas.verification.LocationVerificationStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Executes the discovery query against PostgreSQL/PostGIS.
 *
 * <p>Keeps repository responsibility limited to data access: it delegates SQL construction to
 * {@link PropertySearchQueryBuilder}, applies pagination via bound limit/offset (never string
 * concatenation), runs a matching count query, and maps only the projected public columns into
 * {@link PropertySearchRow}. The exact address, coordinates and owner identifier are never read.</p>
 */
@Repository
public class PropertySearchRepository {

    private final EntityManager entityManager;
    private final PropertySearchQueryBuilder queryBuilder;

    public PropertySearchRepository(EntityManager entityManager, PropertySearchQueryBuilder queryBuilder) {
        this.entityManager = entityManager;
        this.queryBuilder = queryBuilder;
    }

    public Page<PropertySearchRow> search(PropertySearchCriteria criteria, Pageable pageable) {
        PropertySearchQueryBuilder.BuiltQuery built = queryBuilder.build(criteria);

        Query contentQuery = entityManager.createNativeQuery(built.contentSql());
        built.contentParams().forEach(contentQuery::setParameter);
        contentQuery.setMaxResults(pageable.getPageSize());
        contentQuery.setFirstResult((int) pageable.getOffset());

        @SuppressWarnings("unchecked")
        List<Object[]> rows = contentQuery.getResultList();

        List<PropertySearchRow> results = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            results.add(mapRow(row, built.hasDistanceColumn()));
        }

        Query countQuery = entityManager.createNativeQuery(built.countSql());
        built.countParams().forEach(countQuery::setParameter);
        long total = ((Number) countQuery.getSingleResult()).longValue();

        Pageable requestPage = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return new PageImpl<>(results, requestPage, total);
    }

    private PropertySearchRow mapRow(Object[] row, boolean hasDistanceColumn) {
        UUID propertyId = toUuid(row[0]);
        String propertyType = (String) row[1];
        String locality = (String) row[2];
        String city = (String) row[3];
        LocationVerificationStatus status = toStatus((String) row[4]);
        int imageCount = ((Number) row[5]).intValue();
        OffsetDateTime createdAt = toOffsetDateTime(row[6]);
        Double distanceMeters = hasDistanceColumn ? toDouble(row[7]) : null;

        return new PropertySearchRow(
                propertyId, propertyType, locality, city, status, imageCount, createdAt, distanceMeters
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

    private LocationVerificationStatus toStatus(String value) {
        return value == null ? null : LocationVerificationStatus.valueOf(value);
    }

    private Double toDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    private OffsetDateTime toOffsetDateTime(Object value) {
        if (value instanceof OffsetDateTime odt) {
            return odt;
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant().atOffset(ZoneOffset.UTC);
        }
        return null;
    }
}
