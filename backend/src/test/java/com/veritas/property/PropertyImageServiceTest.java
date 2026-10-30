package com.veritas.property;

import com.veritas.common.exception.ResourceNotFoundException;
import com.veritas.common.exception.UnauthorizedActionException;
import com.veritas.listing.ListingRepository;
import com.veritas.property.dto.PropertyImageResponse;
import com.veritas.storage.StorageService;
import com.veritas.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PropertyImageServiceTest {

    @Mock
    private PropertyRepository propertyRepository;

    @Mock
    private PropertyImageRepository propertyImageRepository;

    @Mock
    private ListingRepository listingRepository;

    @Mock
    private StorageService storageService;

    @InjectMocks
    private PropertyImageService propertyImageService;

    private User owner;
    private Property property;
    private PropertyImage image;
    private UUID ownerId;
    private UUID propertyId;
    private UUID imageId;

    @BeforeEach
    void setUp() {
        ownerId = UUID.randomUUID();
        propertyId = UUID.randomUUID();
        imageId = UUID.randomUUID();

        owner = new User();
        owner.setId(ownerId);

        property = new Property();
        property.setId(propertyId);
        property.setOwner(owner);

        image = new PropertyImage();
        image.setId(imageId);
        image.setProperty(property);
        image.setStorageKey("prop-123/image-abc.jpg");
        image.setOriginalFilename("bedroom.jpg");
        image.setContentType("image/jpeg");
        image.setFileSizeBytes(102400L);
        image.setDisplayOrder(0);
        image.setCreatedAt(OffsetDateTime.now());
    }

    @Test
    @DisplayName("Should successfully upload image for owned property")
    void shouldUploadImageSuccessfully() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "bedroom.jpg",
                "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}
        );

        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(storageService.store(eq(file), any())).thenReturn("prop-123/image-abc.jpg");
        when(propertyImageRepository.countByPropertyId(propertyId)).thenReturn(0L);
        when(propertyImageRepository.save(any(PropertyImage.class))).thenAnswer(invocation -> {
            PropertyImage img = invocation.getArgument(0);
            img.setId(imageId);
            return img;
        });

        PropertyImageResponse response = propertyImageService.uploadImage(propertyId, ownerId, file);

        assertNotNull(response);
        assertEquals("bedroom.jpg", response.getOriginalFilename());
        assertEquals("prop-123/image-abc.jpg", response.getStorageKey());
        assertNotNull(response.getContentUrl(), "Authorized upload response must expose safe content URL");
        verify(storageService).store(eq(file), eq(propertyId.toString()));
    }

    @Test
    @DisplayName("Public renter image list omits storage key but keeps content URL")
    void shouldSanitizeImagesForPublicRenter() {
        UUID renterId = UUID.randomUUID();
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(listingRepository.hasPublishedListing(propertyId)).thenReturn(true);
        when(propertyImageRepository.findByPropertyIdOrderByDisplayOrderAsc(propertyId)).thenReturn(List.of(image));

        List<PropertyImageResponse> images = propertyImageService.getImagesForProperty(propertyId, renterId, false);

        assertEquals(1, images.size());
        PropertyImageResponse img = images.get(0);
        assertNull(img.getStorageKey(), "Internal storage key must be omitted for public viewers");
        assertEquals(imageId, img.getId());
        assertEquals(propertyId, img.getPropertyId());
        assertNotNull(img.getContentUrl(), "Safe content URL must be present for public viewers");
    }

    @Test
    @DisplayName("Owner image list retains authorized storage key")
    void shouldProvideFullImageMetadataToOwner() {
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(propertyImageRepository.findByPropertyIdOrderByDisplayOrderAsc(propertyId)).thenReturn(List.of(image));

        List<PropertyImageResponse> images = propertyImageService.getImagesForProperty(propertyId, ownerId, false);

        assertEquals(1, images.size());
        assertEquals("prop-123/image-abc.jpg", images.get(0).getStorageKey());
        assertNotNull(images.get(0).getContentUrl());
    }

    @Test
    @DisplayName("Non-owner cannot list images for a property without a published listing")
    void shouldRejectImageListForNonOwnerWithoutPublishedListing() {
        UUID renterId = UUID.randomUUID();
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(listingRepository.hasPublishedListing(propertyId)).thenReturn(false);

        assertThrows(UnauthorizedActionException.class, () ->
                propertyImageService.getImagesForProperty(propertyId, renterId, false)
        );
    }

    @Test
    @DisplayName("Reject image upload for property owned by another user")
    void shouldRejectImageUploadForNonOwner() {
        UUID otherOwnerId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", new byte[]{1, 2});

        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));

        assertThrows(UnauthorizedActionException.class, () ->
                propertyImageService.uploadImage(propertyId, otherOwnerId, file)
        );
        verifyNoInteractions(storageService);
    }

    @Test
    @DisplayName("Owner can delete image belonging to their property")
    void shouldAllowOwnerToDeleteImage() {
        when(propertyImageRepository.findByIdAndPropertyId(imageId, propertyId))
                .thenReturn(Optional.of(image));

        propertyImageService.deleteImage(propertyId, imageId, ownerId);

        verify(storageService).delete("prop-123/image-abc.jpg");
        verify(propertyImageRepository).delete(image);
    }

    @Test
    @DisplayName("Reject deleting image if user is not the property owner")
    void shouldRejectDeletingImageByNonOwner() {
        UUID intruderId = UUID.randomUUID();
        when(propertyImageRepository.findByIdAndPropertyId(imageId, propertyId))
                .thenReturn(Optional.of(image));

        assertThrows(UnauthorizedActionException.class, () ->
                propertyImageService.deleteImage(propertyId, imageId, intruderId)
        );
        verify(storageService, never()).delete(any());
        verify(propertyImageRepository, never()).delete(any());
    }
}
