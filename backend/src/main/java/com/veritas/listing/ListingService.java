package com.veritas.listing;

import com.veritas.common.exception.*;
import com.veritas.listing.dto.CreateListingRequest;
import com.veritas.listing.dto.ListingResponse;
import com.veritas.listing.dto.UpdateListingRequest;
import com.veritas.property.Property;
import com.veritas.property.PropertyRepository;
import com.veritas.property.PropertyService;
import com.veritas.property.dto.PropertySummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class ListingService {

    private final ListingRepository listingRepository;
    private final PropertyRepository propertyRepository;
    private final PropertyService propertyService;

    public ListingService(
            ListingRepository listingRepository,
            PropertyRepository propertyRepository,
            PropertyService propertyService
    ) {
        this.listingRepository = listingRepository;
        this.propertyRepository = propertyRepository;
        this.propertyService = propertyService;
    }

    @Transactional
    public ListingResponse createDraftListing(UUID ownerId, CreateListingRequest request) {
        Property property = propertyRepository.findById(request.getPropertyId())
                .orElseThrow(() -> new ResourceNotFoundException("Property not found with id: " + request.getPropertyId()));

        if (!property.getOwner().getId().equals(ownerId)) {
            throw new UnauthorizedActionException("You are not authorized to create a listing for this property");
        }

        Listing listing = new Listing();
        listing.setProperty(property);
        listing.setRent(request.getRent());
        listing.setDeposit(request.getDeposit());
        listing.setBhk(request.getBhk());
        listing.setBathrooms(request.getBathrooms() != null ? request.getBathrooms() : 1);
        listing.setBuiltUpArea(request.getBuiltUpArea());
        listing.setFloor(request.getFloor());
        listing.setFurnishingStatus(request.getFurnishingStatus() != null ? request.getFurnishingStatus() : com.veritas.property.FurnishingStatus.UNFURNISHED);
        listing.setBachelorsAllowed(request.isBachelorsAllowed());
        listing.setParkingAvailable(request.isParkingAvailable());
        listing.setPetsAllowed(request.isPetsAllowed());
        listing.setAvailableFrom(request.getAvailableFrom());
        listing.setDescription(request.getDescription());
        listing.setStatus(ListingStatus.DRAFT);

        OffsetDateTime now = OffsetDateTime.now();
        listing.setCreatedAt(now);
        listing.setUpdatedAt(now);

        Listing saved = listingRepository.save(listing);
        // Owner-only action: return the authorized (privileged) view explicitly.
        return mapToResponse(saved, true);
    }

    @Transactional(readOnly = true)
    public ListingResponse getListingDetails(UUID listingId, UUID currentUserId, boolean isAdmin) {
        Listing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("Listing not found with id: " + listingId));

        boolean isOwner = currentUserId != null && listing.getProperty().getOwner().getId().equals(currentUserId);
        boolean isOwnerOrAdmin = isOwner || isAdmin;

        // Draft listings are strictly not publicly visible
        if (listing.getStatus() == ListingStatus.DRAFT && !isOwnerOrAdmin) {
            throw new ResourceNotFoundException("Listing not found with id: " + listingId);
        }

        // Other non-published listings (e.g. archived) are only visible to the owner or admin
        if (listing.getStatus() != ListingStatus.PUBLISHED && !isOwnerOrAdmin) {
            throw new UnauthorizedActionException("Access denied: Listing is not published");
        }

        return mapToResponse(listing, isOwnerOrAdmin);
    }

    @Transactional
    public ListingResponse updateListing(UUID listingId, UUID ownerId, UpdateListingRequest request) {
        Listing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("Listing not found with id: " + listingId));

        if (!listing.getProperty().getOwner().getId().equals(ownerId)) {
            throw new UnauthorizedActionException("You are not authorized to update this listing");
        }

        if (listing.getStatus() == ListingStatus.ARCHIVED) {
            throw new InvalidStateTransitionException("Cannot update an archived listing");
        }

        listing.setRent(request.getRent());
        listing.setDeposit(request.getDeposit());
        listing.setBhk(request.getBhk());
        listing.setBathrooms(request.getBathrooms());
        listing.setBuiltUpArea(request.getBuiltUpArea());
        listing.setFloor(request.getFloor());
        if (request.getFurnishingStatus() != null) {
            listing.setFurnishingStatus(request.getFurnishingStatus());
        }
        listing.setBachelorsAllowed(request.isBachelorsAllowed());
        listing.setParkingAvailable(request.isParkingAvailable());
        listing.setPetsAllowed(request.isPetsAllowed());
        listing.setAvailableFrom(request.getAvailableFrom());
        listing.setDescription(request.getDescription());
        listing.setUpdatedAt(OffsetDateTime.now());

        Listing saved = listingRepository.save(listing);
        // Owner-only action: return the authorized (privileged) view explicitly.
        return mapToResponse(saved, true);
    }

    @Transactional
    public ListingResponse publishListing(UUID listingId, UUID ownerId) {
        Listing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("Listing not found with id: " + listingId));

        if (!listing.getProperty().getOwner().getId().equals(ownerId)) {
            throw new UnauthorizedActionException("You are not authorized to publish this listing");
        }

        // Permitted states: DRAFT, UNAVAILABLE, RENTED
        if (listing.getStatus() == ListingStatus.PUBLISHED) {
            return mapToResponse(listing, true);
        }

        if (listing.getStatus() == ListingStatus.ARCHIVED) {
            throw new InvalidStateTransitionException("Cannot publish an archived listing");
        }

        // Validate completeness
        validateListingCompleteness(listing);

        // Prevent duplicate published listings for the same property
        boolean hasConflict = listingRepository.existsByPropertyIdAndStatusAndIdNot(
                listing.getProperty().getId(),
                ListingStatus.PUBLISHED,
                listing.getId()
        );

        if (hasConflict) {
            throw new ListingConflictException("Another active published listing already exists for this property");
        }

        listing.setStatus(ListingStatus.PUBLISHED);
        listing.setUpdatedAt(OffsetDateTime.now());

        Listing saved = listingRepository.save(listing);
        // Owner-only action: return the authorized (privileged) view explicitly.
        return mapToResponse(saved, true);
    }

    @Transactional
    public ListingResponse updateListingStatus(UUID listingId, UUID ownerId, ListingStatus targetStatus) {
        Listing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("Listing not found with id: " + listingId));

        if (!listing.getProperty().getOwner().getId().equals(ownerId)) {
            throw new UnauthorizedActionException("You are not authorized to change the status of this listing");
        }

        ListingStatus currentStatus = listing.getStatus();

        if (currentStatus == targetStatus) {
            return mapToResponse(listing, true);
        }

        // Validate permitted transitions
        switch (targetStatus) {
            case PUBLISHED -> {
                return publishListing(listingId, ownerId);
            }
            case UNAVAILABLE -> {
                if (currentStatus != ListingStatus.PUBLISHED) {
                    throw new InvalidStateTransitionException("Only a published listing can be marked unavailable");
                }
            }
            case RENTED -> {
                if (currentStatus != ListingStatus.PUBLISHED && currentStatus != ListingStatus.UNAVAILABLE) {
                    throw new InvalidStateTransitionException("Only published or unavailable listings can be marked rented");
                }
            }
            case ARCHIVED -> {
                // Any listing can be archived
            }
            case DRAFT -> {
                throw new InvalidStateTransitionException("Cannot revert an existing listing back to draft status");
            }
        }

        listing.setStatus(targetStatus);
        listing.setUpdatedAt(OffsetDateTime.now());

        Listing saved = listingRepository.save(listing);
        // Owner-only action: return the authorized (privileged) view explicitly.
        return mapToResponse(saved, true);
    }

    @Transactional(readOnly = true)
    public Page<ListingResponse> getOwnerListings(UUID ownerId, ListingStatus status, Pageable pageable) {
        Page<Listing> page;
        if (status != null) {
            page = listingRepository.findByOwnerIdAndStatus(ownerId, status, pageable);
        } else {
            page = listingRepository.findByOwnerId(ownerId, pageable);
        }
        // Caller is the authenticated owner, so listings include full authorized property detail.
        return page.map(listing -> mapToResponse(listing, true));
    }

    private void validateListingCompleteness(Listing listing) {
        if (listing.getRent() == null || listing.getRent().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IncompleteListingException("Listing rent must be strictly greater than zero to publish");
        }
        if (listing.getDeposit() == null || listing.getDeposit().compareTo(BigDecimal.ZERO) < 0) {
            throw new IncompleteListingException("Listing deposit cannot be negative to publish");
        }
        if (listing.getBhk() == null || listing.getBhk() <= 0) {
            throw new IncompleteListingException("Listing must specify a positive BHK count to publish");
        }
        if (listing.getBathrooms() == null || listing.getBathrooms() <= 0) {
            throw new IncompleteListingException("Listing must specify a positive bathroom count to publish");
        }
        if (listing.getBuiltUpArea() == null || listing.getBuiltUpArea().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IncompleteListingException("Listing built-up area must be strictly greater than zero to publish");
        }
        if (listing.getFloor() == null) {
            throw new IncompleteListingException("Listing floor must be specified to publish");
        }
        if (listing.getAvailableFrom() == null) {
            throw new IncompleteListingException("Listing availability date must be specified to publish");
        }
        if (listing.getProperty() == null || listing.getProperty().getAddress() == null || listing.getProperty().getAddress().isBlank()) {
            throw new IncompleteListingException("Associated property must have a valid address to publish");
        }
    }

    public ListingResponse mapToResponse(Listing listing, boolean isOwnerOrAdmin) {
        PropertySummaryResponse propertySummary = propertyService.mapToSummary(listing.getProperty(), isOwnerOrAdmin);

        return new ListingResponse(
                listing.getId(),
                listing.getProperty().getId(),
                propertySummary,
                listing.getRent(),
                listing.getDeposit(),
                listing.getBhk(),
                listing.getBathrooms(),
                listing.getBuiltUpArea(),
                listing.getFloor(),
                listing.getFurnishingStatus(),
                listing.isBachelorsAllowed(),
                listing.isParkingAvailable(),
                listing.isPetsAllowed(),
                listing.getAvailableFrom(),
                listing.getDescription(),
                listing.getStatus(),
                listing.getCreatedAt(),
                listing.getUpdatedAt()
        );
    }
}
