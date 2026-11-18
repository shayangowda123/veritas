package com.veritas.property;

import com.veritas.common.web.OwnerPaginationGuard;
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

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/properties")
public class PropertyController {

    // Direct Property attributes that may be sorted on by the owner "my" endpoint. Any other value
    // (unknown field, nested path such as owner.name, or an internal/expensive column) is rejected.
    private static final Set<String> SORTABLE_FIELDS = Set.of(
            "createdAt", "city", "locality", "propertyType", "locationVerificationStatus", "id");

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
        // ADMIN flag derived server-side from the database-loaded principal (never client-supplied),
        // matching the existing read-path pattern (Phase 3B.6E).
        boolean isAdmin = principal.getRole() == UserRole.ADMIN;
        PropertyResponse response = propertyService.updateProperty(id, principal.getId(), isAdmin, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('OWNER') or hasRole('ADMIN')")
    public ResponseEntity<Page<PropertySummaryResponse>> getMyProperties(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(name = "page", required = false) Integer requestedPage,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Pageable safePageable = OwnerPaginationGuard.validateAndNormalize(pageable, requestedPage, SORTABLE_FIELDS);
        Page<PropertySummaryResponse> page = propertyService.getOwnerProperties(principal.getId(), safePageable);
        return ResponseEntity.ok(page);
    }
}
