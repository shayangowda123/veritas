package com.veritas.listing;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ListingRepository extends JpaRepository<Listing, UUID> {

    // JOIN FETCH l.property loads the associated property in the same query. Previously the WHERE
    // clause only navigated l.property.owner.id (an implicit join), leaving listing.property a lazy
    // proxy that triggered one SELECT per row when building the owner's listing page (N+1). property
    // is a ToOne association, so the fetch join does not multiply rows and pagination stays correct.
    @Query("SELECT l FROM Listing l JOIN FETCH l.property p WHERE p.owner.id = :ownerId")
    Page<Listing> findByOwnerId(@Param("ownerId") UUID ownerId, Pageable pageable);

    @Query("SELECT l FROM Listing l JOIN FETCH l.property p WHERE p.owner.id = :ownerId AND l.status = :status")
    Page<Listing> findByOwnerIdAndStatus(
            @Param("ownerId") UUID ownerId,
            @Param("status") ListingStatus status,
            Pageable pageable
    );

    @Query("SELECT l FROM Listing l WHERE l.id = :id AND l.property.owner.id = :ownerId")
    Optional<Listing> findByIdAndOwnerId(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    boolean existsByPropertyIdAndStatus(UUID propertyId, ListingStatus status);

    boolean existsByPropertyIdAndStatusAndIdNot(UUID propertyId, ListingStatus status, UUID id);

    List<Listing> findByPropertyId(UUID propertyId);

    @Query("SELECT count(l) > 0 FROM Listing l WHERE l.property.id = :propertyId AND l.status = 'PUBLISHED'")
    boolean hasPublishedListing(@Param("propertyId") UUID propertyId);
}