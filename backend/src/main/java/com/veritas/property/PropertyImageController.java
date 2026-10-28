package com.veritas.property;

import com.veritas.property.dto.PropertyImageResponse;
import com.veritas.security.UserPrincipal;
import com.veritas.user.UserRole;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/properties/{propertyId}/images")
public class PropertyImageController {

    private final PropertyImageService propertyImageService;

    public PropertyImageController(PropertyImageService propertyImageService) {
        this.propertyImageService = propertyImageService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('OWNER') or hasRole('ADMIN')")
    public ResponseEntity<PropertyImageResponse> uploadImage(
            @PathVariable UUID propertyId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        PropertyImageResponse response = propertyImageService.uploadImage(propertyId, principal.getId(), file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<PropertyImageResponse>> getImages(
            @PathVariable UUID propertyId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        UUID currentUserId = principal != null ? principal.getId() : null;
        boolean isAdmin = principal != null && principal.getRole() == UserRole.ADMIN;

        List<PropertyImageResponse> images = propertyImageService.getImagesForProperty(propertyId, currentUserId, isAdmin);
        return ResponseEntity.ok(images);
    }

    @DeleteMapping("/{imageId}")
    @PreAuthorize("hasRole('OWNER') or hasRole('ADMIN')")
    public ResponseEntity<Void> deleteImage(
            @PathVariable UUID propertyId,
            @PathVariable UUID imageId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        propertyImageService.deleteImage(propertyId, imageId, principal.getId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{imageId}/content")
    public ResponseEntity<Resource> getImageContent(
            @PathVariable UUID propertyId,
            @PathVariable UUID imageId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        UUID currentUserId = principal != null ? principal.getId() : null;
        boolean isAdmin = principal != null && principal.getRole() == UserRole.ADMIN;

        PropertyImageService.LoadedImage loaded = propertyImageService.loadImageResource(
                propertyId,
                imageId,
                currentUserId,
                isAdmin
        );

        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        if (loaded.contentType() != null) {
            try {
                mediaType = MediaType.parseMediaType(loaded.contentType());
            } catch (Exception ignored) {}
        }

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + loaded.originalFilename() + "\"")
                .body(loaded.resource());
    }
}
