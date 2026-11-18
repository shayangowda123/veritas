package com.veritas.property;

import com.veritas.common.exception.ResourceNotFoundException;
import com.veritas.common.exception.UnauthorizedActionException;
import com.veritas.listing.ListingRepository;
import com.veritas.property.dto.*;
import com.veritas.security.ResourceAccess;
import com.veritas.user.User;
import com.veritas.user.UserRepository;
import com.veritas.user.UserRole;
import com.veritas.verification.LocationVerificationStatus;
import com.veritas.verification.geocoding.service.LocationConsistencyResult;
import com.veritas.verification.geocoding.service.LocationConsistencyService;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PropertyService {

    private static final Logger log = LoggerFactory.getLogger(PropertyService.class);

    private final PropertyRepository propertyRepository;
    private final PropertyImageRepository propertyImageRepository;
    private final ListingRepository listingRepository;
    private final UserRepository userRepository;
    private final LocationConsistencyService locationConsistencyService;
    private final GeometryFactory geometryFactory;

    public PropertyService(
            PropertyRepository propertyRepository,
            PropertyImageRepository propertyImageRepository,
            ListingRepository listingRepository,
            UserRepository userRepository,
            LocationConsistencyService locationConsistencyService
    ) {
        this.propertyRepository = propertyRepository;
        this.propertyImageRepository = propertyImageRepository;
        this.listingRepository = listingRepository;
        this.userRepository = userRepository;
        this.locationConsistencyService = locationConsistencyService;
        this.geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
    }

    @Transactional
    public PropertyResponse createProperty(UUID ownerId, CreatePropertyRequest request) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (owner.getRole() != UserRole.OWNER && owner.getRole() != UserRole.ADMIN) {
            throw new UnauthorizedActionException("Only registered owners can create property records");
        }

        Point point = geometryFactory.createPoint(
                new Coordinate(request.getLongitude(), request.getLatitude())
        );

        Property property = new Property();
        property.setOwner(owner);
        property.setPropertyType(request.getPropertyType().trim());
        property.setAddress(request.getAddress().trim());
        property.setLocality(request.getLocality() != null ? request.getLocality().trim() : null);
        property.setCity(request.getCity() != null ? request.getCity().trim() : null);
        property.setLocation(point);

        // Safely evaluate location consistency without failing the property creation workflow
        try {
            LocationConsistencyResult consistency = locationConsistencyService.checkConsistency(
                    request.getAddress(),
                    request.getLocality(),
                    request.getCity(),
                    request.getLatitude(),
                    request.getLongitude()
            );
            property.setLocationVerificationStatus(consistency.recommendedVerificationStatus());
            property.setVerificationNotes(consistency.safeSummaryNotes());
            property.setVerificationMethod("AUTOMATED_GEOCODING");
        } catch (Exception ex) {
            log.warn("Failed to evaluate location consistency; defaulting safely to PENDING reviewable state", ex);
            property.setLocationVerificationStatus(LocationVerificationStatus.PENDING);
            property.setVerificationNotes("Automated geocoding check unavailable: unexpected internal error. Preserved for manual review.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        property.setCreatedAt(now);
        property.setUpdatedAt(now);

        Property saved = propertyRepository.save(property);
        // Creator is the authenticated owner, so an authorized (privileged) view is appropriate.
        return mapToResponse(saved, List.of(), true);
    }

    @Transactional(readOnly = true)
    public PropertyResponse getPropertyDetails(UUID propertyId, UUID currentUserId, boolean isAdmin) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new ResourceNotFoundException("Property not found with id: " + propertyId));

        boolean isOwner = currentUserId != null && property.getOwner().getId().equals(currentUserId);
        boolean isOwnerOrAdmin = isOwner || isAdmin;

        if (!isOwnerOrAdmin) {
            boolean hasPublishedListing = listingRepository.hasPublishedListing(propertyId);
            if (!hasPublishedListing) {
                // READ visibility rule: a private property is reported as not-found to unauthorized
                // viewers (consistent with unpublished listing reads) so its existence is not
                // disclosed. WRITE authorization for this resource still returns 403 via
                // UnauthorizedActionException in updateProperty.
                throw new ResourceNotFoundException("Property not found with id: " + propertyId);
            }
        }

        List<PropertyImage> images = propertyImageRepository.findByPropertyIdOrderByDisplayOrderAsc(propertyId);
        return mapToResponse(property, images, isOwnerOrAdmin);
    }

    @Transactional
    public PropertyResponse updateProperty(UUID propertyId, UUID actorId, boolean actorIsAdmin, UpdatePropertyRequest request) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new ResourceNotFoundException("Property not found with id: " + propertyId));

        // Phase 3B.6E: ADMIN may manage any owner's property; an OWNER remains restricted to their
        // own. The shared helper centralizes the rule; ownership is NEVER transferred by this call.
        ResourceAccess.requireOwnershipOrAdmin(
                actorIsAdmin,
                property.getOwner().getId(),
                actorId,
                "You are not authorized to modify this property");

        double oldLat = property.getLatitude();
        double oldLon = property.getLongitude();
        String oldAddress = property.getAddress();

        boolean locationChanged = Math.abs(oldLat - request.getLatitude()) > 0.00001
                || Math.abs(oldLon - request.getLongitude()) > 0.00001
                || !oldAddress.equalsIgnoreCase(request.getAddress().trim());

        // Location verification is reset if physical location or address changes
        if (locationChanged) {
            property.setLocationVerificationStatus(LocationVerificationStatus.PENDING);
            property.setVerifiedAt(null);
            property.setVerificationNotes(null);
            property.setVerificationMethod(null);
        }

        Point point = geometryFactory.createPoint(
                new Coordinate(request.getLongitude(), request.getLatitude())
        );

        property.setPropertyType(request.getPropertyType().trim());
        property.setAddress(request.getAddress().trim());
        property.setLocality(request.getLocality() != null ? request.getLocality().trim() : null);
        property.setCity(request.getCity() != null ? request.getCity().trim() : null);
        property.setLocation(point);
        property.setUpdatedAt(OffsetDateTime.now());

        Property updated = propertyRepository.save(property);
        List<PropertyImage> images = propertyImageRepository.findByPropertyIdOrderByDisplayOrderAsc(propertyId);
        return mapToResponse(updated, images, true);
    }

    @Transactional(readOnly = true)
    public Page<PropertySummaryResponse> getOwnerProperties(UUID ownerId, Pageable pageable) {
        Page<Property> page = propertyRepository.findByOwnerId(ownerId, pageable);
        // Batch the image counts for the entire page in ONE grouped query instead of a COUNT per row
        // (N+1). mapToSummary does not traverse owner/publishedListing, so no other lazy loads occur.
        Map<UUID, Long> imageCounts = loadImageCounts(
                page.getContent().stream().map(Property::getId).toList());
        // Caller is the authenticated owner, so summaries are returned with full authorized detail.
        return page.map(p -> mapToSummary(p, true, imageCounts.getOrDefault(p.getId(), 0L)));
    }

    /**
     * Resolves image counts for many properties with a single grouped query. Returns a map of
     * property id to count; a property with no images is simply absent, and callers must default it
     * to zero. Avoids the per-row {@code countByPropertyId} N+1 pattern in paged property/listing
     * flows. Executes no query for a null/empty id set (e.g. an empty page).
     */
    public Map<UUID, Long> loadImageCounts(Collection<UUID> propertyIds) {
        if (propertyIds == null || propertyIds.isEmpty()) {
            return Map.of();
        }
        // Distinct ids: the same property can back several listings; avoid redundant IN values.
        Set<UUID> distinct = new HashSet<>(propertyIds);
        Map<UUID, Long> counts = new HashMap<>();
        for (PropertyImageCountProjection row : propertyImageRepository.countByPropertyIds(distinct)) {
            counts.put(row.getPropertyId(), row.getImageCount());
        }
        return counts;
    }

    /**
     * Maps a property to its API response, redacting sensitive fields for non-authorized viewers.
     *
     * @param isOwnerOrAdmin must reflect the ACTUAL server-side authorization result. There is no
     *                       privileged default: callers must resolve ownership/adminship explicitly.
     */
    public PropertyResponse mapToResponse(Property property, List<PropertyImage> images, boolean isOwnerOrAdmin) {
        List<PropertyImageResponse> imageResponses = images.stream()
                .map(img -> new PropertyImageResponse(
                        img.getId(),
                        property.getId(),
                        isOwnerOrAdmin ? img.getStorageKey() : null,
                        img.getOriginalFilename(),
                        img.getContentType(),
                        img.getFileSizeBytes(),
                        img.getDisplayOrder(),
                        img.getCreatedAt()
                ))
                .toList();

        return new PropertyResponse(
                property.getId(),
                // Internal owner identifier is not part of the public contract; only authorized viewers receive it.
                isOwnerOrAdmin ? property.getOwner().getId() : null,
                // Owner personal name is PII: only authorized (owner/admin) viewers receive it.
                isOwnerOrAdmin ? property.getOwner().getName() : null,
                property.getPropertyType(),
                isOwnerOrAdmin ? property.getAddress() : null,
                property.getLocality(),
                property.getCity(),
                isOwnerOrAdmin ? property.getLatitude() : null,
                isOwnerOrAdmin ? property.getLongitude() : null,
                property.getLocationVerificationStatus(),
                isOwnerOrAdmin ? property.getVerifiedAt() : null,
                isOwnerOrAdmin ? property.getVerificationNotes() : null,
                isOwnerOrAdmin ? property.getVerificationMethod() : null,
                imageResponses,
                property.getCreatedAt(),
                property.getUpdatedAt()
        );
    }

    /**
     * Maps a property to its summary response, redacting sensitive fields for non-authorized viewers.
     * The image count is resolved with its own single COUNT; paged flows should instead preload counts
     * once via {@link #loadImageCounts(java.util.Collection)} and call
     * {@link #mapToSummary(Property, boolean, long)} to avoid N+1 queries.
     *
     * @param isOwnerOrAdmin must reflect the ACTUAL server-side authorization result.
     */
    public PropertySummaryResponse mapToSummary(Property property, boolean isOwnerOrAdmin) {
        long imageCount = propertyImageRepository.countByPropertyId(property.getId());
        return mapToSummary(property, isOwnerOrAdmin, imageCount);
    }

    /**
     * Maps a property to its summary response using a pre-resolved image count (no query issued).
     *
     * @param isOwnerOrAdmin must reflect the ACTUAL server-side authorization result.
     * @param imageCount     image count resolved by the caller (batched for the page); properties
     *                       absent from the batch must be passed as 0.
     */
    public PropertySummaryResponse mapToSummary(Property property, boolean isOwnerOrAdmin, long imageCount) {
        return new PropertySummaryResponse(
                property.getId(),
                property.getPropertyType(),
                isOwnerOrAdmin ? property.getAddress() : null,
                property.getLocality(),
                property.getCity(),
                isOwnerOrAdmin ? property.getLatitude() : null,
                isOwnerOrAdmin ? property.getLongitude() : null,
                property.getLocationVerificationStatus(),
                (int) imageCount,
                property.getCreatedAt()
        );
    }
}
