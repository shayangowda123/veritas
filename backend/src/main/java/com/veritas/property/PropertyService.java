package com.veritas.property;

import com.veritas.common.exception.ResourceNotFoundException;
import com.veritas.common.exception.UnauthorizedActionException;
import com.veritas.listing.ListingRepository;
import com.veritas.property.dto.*;
import com.veritas.user.User;
import com.veritas.user.UserRepository;
import com.veritas.user.UserRole;
import com.veritas.verification.LocationVerificationStatus;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class PropertyService {

    private final PropertyRepository propertyRepository;
    private final PropertyImageRepository propertyImageRepository;
    private final ListingRepository listingRepository;
    private final UserRepository userRepository;
    private final GeometryFactory geometryFactory;

    public PropertyService(
            PropertyRepository propertyRepository,
            PropertyImageRepository propertyImageRepository,
            ListingRepository listingRepository,
            UserRepository userRepository
    ) {
        this.propertyRepository = propertyRepository;
        this.propertyImageRepository = propertyImageRepository;
        this.listingRepository = listingRepository;
        this.userRepository = userRepository;
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
        property.setLocationVerificationStatus(LocationVerificationStatus.PENDING);

        OffsetDateTime now = OffsetDateTime.now();
        property.setCreatedAt(now);
        property.setUpdatedAt(now);

        Property saved = propertyRepository.save(property);
        return mapToResponse(saved, List.of());
    }

    @Transactional(readOnly = true)
    public PropertyResponse getPropertyDetails(UUID propertyId, UUID currentUserId, boolean isAdmin) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new ResourceNotFoundException("Property not found with id: " + propertyId));

        boolean isOwner = currentUserId != null && property.getOwner().getId().equals(currentUserId);

        if (!isOwner && !isAdmin) {
            boolean hasPublishedListing = listingRepository.hasPublishedListing(propertyId);
            if (!hasPublishedListing) {
                throw new UnauthorizedActionException("Access denied: You do not have permission to view this private property");
            }
        }

        List<PropertyImage> images = propertyImageRepository.findByPropertyIdOrderByDisplayOrderAsc(propertyId);
        return mapToResponse(property, images);
    }

    @Transactional
    public PropertyResponse updateProperty(UUID propertyId, UUID ownerId, UpdatePropertyRequest request) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new ResourceNotFoundException("Property not found with id: " + propertyId));

        if (!property.getOwner().getId().equals(ownerId)) {
            throw new UnauthorizedActionException("You are not authorized to modify this property");
        }

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
        return mapToResponse(updated, images);
    }

    @Transactional(readOnly = true)
    public Page<PropertySummaryResponse> getOwnerProperties(UUID ownerId, Pageable pageable) {
        return propertyRepository.findByOwnerId(ownerId, pageable)
                .map(this::mapToSummary);
    }

    public PropertyResponse mapToResponse(Property property, List<PropertyImage> images) {
        List<PropertyImageResponse> imageResponses = images.stream()
                .map(img -> new PropertyImageResponse(
                        img.getId(),
                        property.getId(),
                        img.getStorageKey(),
                        img.getOriginalFilename(),
                        img.getContentType(),
                        img.getFileSizeBytes(),
                        img.getDisplayOrder(),
                        img.getCreatedAt()
                ))
                .toList();

        return new PropertyResponse(
                property.getId(),
                property.getOwner().getId(),
                property.getOwner().getName(),
                property.getPropertyType(),
                property.getAddress(),
                property.getLocality(),
                property.getCity(),
                property.getLatitude(),
                property.getLongitude(),
                property.getLocationVerificationStatus(),
                property.getVerifiedAt(),
                property.getVerificationNotes(),
                property.getVerificationMethod(),
                imageResponses,
                property.getCreatedAt(),
                property.getUpdatedAt()
        );
    }

    public PropertySummaryResponse mapToSummary(Property property) {
        long imageCount = propertyImageRepository.countByPropertyId(property.getId());
        return new PropertySummaryResponse(
                property.getId(),
                property.getPropertyType(),
                property.getAddress(),
                property.getLocality(),
                property.getCity(),
                property.getLatitude(),
                property.getLongitude(),
                property.getLocationVerificationStatus(),
                (int) imageCount,
                property.getCreatedAt()
        );
    }
}
