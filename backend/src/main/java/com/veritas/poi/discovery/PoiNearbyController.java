package com.veritas.poi.discovery;

import com.veritas.poi.discovery.dto.PoiNearbyRequest;
import com.veritas.poi.discovery.dto.PoiNearbyResult;
import com.veritas.search.dto.PagedResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Public entry point for nearby-places discovery.
 *
 * <p>Exposes a single read-only endpoint returning privacy-safe POIs near a published property.
 * No authentication is required and no renter-supplied coordinates are accepted: the search centre
 * is resolved on the server from the published property. Results are identical for every caller, so
 * no client-supplied owner/admin flag is consulted and no other authorization rule is affected.</p>
 */
@RestController
@RequestMapping("/api/v1/properties")
public class PoiNearbyController {

    private final PoiNearbyService poiNearbyService;

    public PoiNearbyController(PoiNearbyService poiNearbyService) {
        this.poiNearbyService = poiNearbyService;
    }

    @GetMapping("/{propertyId}/nearby-places")
    public ResponseEntity<PagedResponse<PoiNearbyResult>> nearbyPlaces(
            @PathVariable UUID propertyId,
            @Valid @ModelAttribute PoiNearbyRequest request
    ) {
        return ResponseEntity.ok(poiNearbyService.search(propertyId, request));
    }
}
