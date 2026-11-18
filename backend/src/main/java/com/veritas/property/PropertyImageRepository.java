package com.veritas.property;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PropertyImageRepository extends JpaRepository<PropertyImage, UUID> {

    List<PropertyImage> findByPropertyIdOrderByDisplayOrderAsc(UUID propertyId);

    Optional<PropertyImage> findByIdAndPropertyId(UUID id, UUID propertyId);

    long countByPropertyId(UUID propertyId);

    /**
     * Batched image-count for a set of properties in a single grouped query, replacing the per-row
     * {@link #countByPropertyId(UUID)} N+1 pattern. Only properties that actually have images appear
     * in the result; callers must treat a missing id as a count of zero. The projection exposes just
     * the id and the count -- never storage keys or other private image columns.
     */
    @Query("SELECT pi.property.id AS propertyId, COUNT(pi) AS imageCount "
            + "FROM PropertyImage pi WHERE pi.property.id IN :propertyIds GROUP BY pi.property.id")
    List<PropertyImageCountProjection> countByPropertyIds(@Param("propertyIds") Collection<UUID> propertyIds);
}