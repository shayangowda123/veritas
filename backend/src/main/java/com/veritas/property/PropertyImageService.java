package com.veritas.property;

import com.veritas.common.exception.ResourceNotFoundException;
import com.veritas.common.exception.UnauthorizedActionException;
import com.veritas.listing.ListingRepository;
import com.veritas.property.dto.PropertyImageResponse;
import com.veritas.storage.StorageService;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class PropertyImageService {

    private final PropertyRepository propertyRepository;
    private final PropertyImageRepository propertyImageRepository;
    private final ListingRepository listingRepository;
    private final StorageService storageService;

    public PropertyImageService(
            PropertyRepository propertyRepository,
            PropertyImageRepository propertyImageRepository,
            ListingRepository listingRepository,
            StorageService storageService
    ) {
        this.propertyRepository = propertyRepository;
        this.propertyImageRepository = propertyImageRepository;
        this.listingRepository = listingRepository;
        this.storageService = storageService;
    }

    @Transactional
    public PropertyImageResponse uploadImage(UUID propertyId, UUID ownerId, MultipartFile file) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new ResourceNotFoundException("Property not found with id: " + propertyId));

        if (!property.getOwner().getId().equals(ownerId)) {
            throw new UnauthorizedActionException("You are not authorized to upload images for this property");
        }

        // Store file safely in storage service (includes magic bytes, size, and traversal validation)
        String storageKey = storageService.store(file, propertyId.toString());

        long currentCount = propertyImageRepository.countByPropertyId(propertyId);

        PropertyImage image = new PropertyImage();
        image.setProperty(property);
        image.setStorageKey(storageKey);
        image.setOriginalFilename(sanitizeOriginalFilename(file.getOriginalFilename()));
        image.setContentType(file.getContentType());
        image.setFileSizeBytes(file.getSize());
        image.setDisplayOrder((int) currentCount);
        image.setCreatedAt(OffsetDateTime.now());

        PropertyImage saved = propertyImageRepository.save(image);

        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<PropertyImageResponse> getImagesForProperty(UUID propertyId, UUID currentUserId, boolean isAdmin) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new ResourceNotFoundException("Property not found with id: " + propertyId));

        boolean isOwner = currentUserId != null && property.getOwner().getId().equals(currentUserId);
        if (!isOwner && !isAdmin) {
            boolean hasPublishedListing = listingRepository.hasPublishedListing(propertyId);
            if (!hasPublishedListing) {
                throw new UnauthorizedActionException("Access denied: Property images are private");
            }
        }

        return propertyImageRepository.findByPropertyIdOrderByDisplayOrderAsc(propertyId).stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional
    public void deleteImage(UUID propertyId, UUID imageId, UUID ownerId) {
        PropertyImage image = propertyImageRepository.findByIdAndPropertyId(imageId, propertyId)
                .orElseThrow(() -> new ResourceNotFoundException("Property image not found"));

        if (!image.getProperty().getOwner().getId().equals(ownerId)) {
            throw new UnauthorizedActionException("You are not authorized to delete images from this property");
        }

        // Delete from disk/storage
        storageService.delete(image.getStorageKey());

        // Delete metadata from database
        propertyImageRepository.delete(image);
    }

    @Transactional(readOnly = true)
    public LoadedImage loadImageResource(UUID propertyId, UUID imageId, UUID currentUserId, boolean isAdmin) {
        PropertyImage image = propertyImageRepository.findByIdAndPropertyId(imageId, propertyId)
                .orElseThrow(() -> new ResourceNotFoundException("Property image not found"));

        boolean isOwner = currentUserId != null && image.getProperty().getOwner().getId().equals(currentUserId);
        if (!isOwner && !isAdmin) {
            boolean hasPublishedListing = listingRepository.hasPublishedListing(propertyId);
            if (!hasPublishedListing) {
                throw new UnauthorizedActionException("Access denied: Image is private");
            }
        }

        Resource resource = storageService.loadAsResource(image.getStorageKey());
        return new LoadedImage(resource, image.getContentType(), image.getOriginalFilename());
    }

    private String sanitizeOriginalFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "unnamed-image";
        }
        // Extract base name, strip directory paths, keep only safe characters
        String clean = originalFilename.replaceAll("^.*[\\\\/]", "");
        return clean.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private PropertyImageResponse mapToResponse(PropertyImage image) {
        return new PropertyImageResponse(
                image.getId(),
                image.getProperty().getId(),
                image.getStorageKey(),
                image.getOriginalFilename(),
                image.getContentType(),
                image.getFileSizeBytes(),
                image.getDisplayOrder(),
                image.getCreatedAt()
        );
    }

    public record LoadedImage(Resource resource, String contentType, String originalFilename) {}
}
