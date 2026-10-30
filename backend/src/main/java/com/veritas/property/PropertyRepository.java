package com.veritas.property;

import org.locationtech.jts.geom.Point;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PropertyRepository extends JpaRepository<Property, UUID> {

    Page<Property> findByOwnerId(UUID ownerId, Pageable pageable);

    Optional<Property> findByIdAndOwnerId(UUID id, UUID ownerId);

    boolean existsByIdAndOwnerId(UUID id, UUID ownerId);

    @Query(value = """
            SELECT *
            FROM properties
            WHERE ST_DWithin(
                location,
                CAST(:location AS geography),
                :radiusInMeters
            )
            """, nativeQuery = true)
    List<Property> findNearbyProperties(
            @Param("location") Point location,
            @Param("radiusInMeters") double radiusInMeters
    );

    @Query(value = """
            SELECT ST_Distance(
                ST_SetSRID(ST_MakePoint(:lon1, :lat1), 4326)::geography,
                ST_SetSRID(ST_MakePoint(:lon2, :lat2), 4326)::geography
            )
            """, nativeQuery = true)
    Double calculateGeographyDistance(
            @Param("lon1") double lon1,
            @Param("lat1") double lat1,
            @Param("lon2") double lon2,
            @Param("lat2") double lat2
    );
}