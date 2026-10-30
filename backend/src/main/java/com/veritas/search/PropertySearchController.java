package com.veritas.search;

import com.veritas.search.dto.PagedResponse;
import com.veritas.search.dto.PropertySearchRequest;
import com.veritas.search.dto.PropertySearchResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public entry point for property discovery.
 *
 * <p>Exposes a single read-only endpoint that returns only properties backed by currently published
 * listings, mapped to privacy-safe summaries. No authentication is required and no client-supplied
 * owner/admin flag is consulted: discovery results are identical for every caller, so authorization
 * rules elsewhere in the application are neither consulted nor weakened.</p>
 */
@RestController
@RequestMapping("/api/v1/properties")
public class PropertySearchController {

    private final PropertySearchService propertySearchService;

    public PropertySearchController(PropertySearchService propertySearchService) {
        this.propertySearchService = propertySearchService;
    }

    @GetMapping("/search")
    public ResponseEntity<PagedResponse<PropertySearchResult>> search(
            @Valid @ModelAttribute PropertySearchRequest request
    ) {
        PagedResponse<PropertySearchResult> response = propertySearchService.search(request);
        return ResponseEntity.ok(response);
    }
}
