package com.veritas.listing.dto;

import com.veritas.listing.ListingStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateListingStatusRequest {

    @NotNull(message = "Status is required")
    private ListingStatus status;
}
