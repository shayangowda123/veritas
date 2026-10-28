package com.veritas.property;

import com.veritas.property.dto.CreatePropertyRequest;
import com.veritas.property.dto.PropertyResponse;
import com.veritas.property.dto.PropertySummaryResponse;
import com.veritas.property.dto.UpdatePropertyRequest;
import com.veritas.security.UserPrincipal;
import com.veritas.user.UserRole;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/properties")
public class PropertyController {

    private final PropertyService propertyService;

    public PropertyController(PropertyService propertyService) {
        this.propertyService = propertyService;
    }

    @PostMapping
    @PreAuthorize("hasRole('OWNER') or hasRole('ADMIN')")
    public ResponseEntity<PropertyResponse> createProperty(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreatePropertyRequest request
    ) {
        PropertyResponse response = propertyService.createProperty(principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PropertyResponse> getProperty(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        UUID currentUserId = principal != null ? principal.getId() : null;
        boolean isAdmin = principal != null && principal.getRole() == UserRole.ADMIN;

        PropertyResponse response = propertyService.getPropertyDetails(id, currentUserId, isAdmin);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('OWNER') or hasRole('ADMIN')")
    public ResponseEntity<PropertyResponse> updateProperty(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpdatePropertyRequest request
    ) {
        PropertyResponse response = propertyService.updateProperty(id, principal.getId(), request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('OWNER') or hasRole('ADMIN')")
    public ResponseEntity<Page<PropertySummaryResponse>> getMyProperties(
            @AuthenticationPrincipal UserPrincipal principal,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Page<PropertySummaryResponse> page = propertyService.getOwnerProperties(principal.getId(), pageable);
        return ResponseEntity.ok(page);
    }
}
