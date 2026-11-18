package com.veritas.listing.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request body for the dedicated publish endpoint. Publish is a listing mutation, so (Phase 3B.6C) it
 * must carry the optimistic-lock version the client originally received. A missing value fails bean
 * validation with the standard HTTP 400 envelope; a value that does not match the current persisted
 * version is rejected with HTTP 409 by {@code ListingService.publishListing}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PublishListingRequest {

    @NotNull(message = "Version is required")
    private Integer version;
}
