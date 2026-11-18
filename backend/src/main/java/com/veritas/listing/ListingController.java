package com.veritas.listing;

import com.veritas.common.web.OwnerPaginationGuard;
import com.veritas.listing.dto.CreateListingRequest;
import com.veritas.listing.dto.ListingResponse;
import com.veritas.listing.dto.PublishListingRequest;
import com.veritas.listing.dto.UpdateListingRequest;
import com.veritas.listing.dto.UpdateListingStatusRequest;
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
@RequestMapping("/api/v1/listings")
public class ListingController {

    // Direct Listing attributes that may be sorted on by the owner "my" endpoint. Nested paths such
    // as property.city and any non-listed field are rejected before the query is built.
    private static final Set<String> SORTABLE_FIELDS = Set.of(
            "createdAt", "rent", "bhk", "status", "availableFrom", "id");

    private final ListingService listingService;

    public ListingController(ListingService listingService) {
        this.listingService = listingService;
    }

    @PostMapping
    @PreAuthorize("hasRole('OWNER') or hasRole('ADMIN')")
    public ResponseEntity<ListingResponse> createDraftListing(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateListingRequest request
    ) {
        boolean isAdmin = principal.getRole() == UserRole.ADMIN;
        ListingResponse response = listingService.createDraftListing(principal.getId(), isAdmin, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ListingResponse> getListing(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        UUID currentUserId = principal != null ? principal.getId() : null;
        boolean isAdmin = principal != null && principal.getRole() == UserRole.ADMIN;

        ListingResponse response = listingService.getListingDetails(id, currentUserId, isAdmin);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('OWNER') or hasRole('ADMIN')")
    public ResponseEntity<ListingResponse> updateListing(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpdateListingRequest request
    ) {
        boolean isAdmin = principal.getRole() == UserRole.ADMIN;
        ListingResponse response = listingService.updateListing(id, principal.getId(), isAdmin, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('OWNER') or hasRole('ADMIN')")
    public ResponseEntity<ListingResponse> publishListing(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody PublishListingRequest request
    ) {
        boolean isAdmin = principal.getRole() == UserRole.ADMIN;
        ListingResponse response = listingService.publishListing(id, principal.getId(), isAdmin, request.getVersion());
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('OWNER') or hasRole('ADMIN')")
    public ResponseEntity<ListingResponse> updateListingStatus(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpdateListingStatusRequest request
    ) {
        boolean isAdmin = principal.getRole() == UserRole.ADMIN;
        ListingResponse response = listingService.updateListingStatus(id, principal.getId(), isAdmin, request.getStatus(), request.getVersion());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('OWNER') or hasRole('ADMIN')")
    public ResponseEntity<Page<ListingResponse>> getMyListings(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) ListingStatus status,
            @RequestParam(name = "page", required = false) Integer requestedPage,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Pageable safePageable = OwnerPaginationGuard.validateAndNormalize(pageable, requestedPage, SORTABLE_FIELDS);
        Page<ListingResponse> page = listingService.getOwnerListings(principal.getId(), status, safePageable);
        return ResponseEntity.ok(page);
    }
}
