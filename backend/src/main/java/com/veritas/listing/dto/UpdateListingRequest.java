package com.veritas.listing.dto;

import com.veritas.property.FurnishingStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateListingRequest {

    @NotNull(message = "Rent is required")
    @DecimalMin(value = "1.0", message = "Rent must be greater than zero")
    private BigDecimal rent;

    @NotNull(message = "Deposit is required")
    @DecimalMin(value = "0.0", message = "Deposit cannot be negative")
    private BigDecimal deposit;

    @NotNull(message = "BHK is required")
    @Min(value = 1, message = "BHK must be at least 1")
    private Integer bhk;

    @NotNull(message = "Bathrooms is required")
    @Min(value = 1, message = "Bathrooms must be at least 1")
    private Integer bathrooms;

    @NotNull(message = "Built-up area is required")
    @DecimalMin(value = "1.0", message = "Built-up area must be greater than zero")
    private BigDecimal builtUpArea;

    @NotNull(message = "Floor is required")
    private Integer floor;

    private FurnishingStatus furnishingStatus;

    private Boolean bachelorsAllowed = false;

    private Boolean parkingAvailable = false;

    private Boolean petsAllowed = false;

    public boolean isBachelorsAllowed() {
        return Boolean.TRUE.equals(bachelorsAllowed);
    }

    public boolean isParkingAvailable() {
        return Boolean.TRUE.equals(parkingAvailable);
    }

    public boolean isPetsAllowed() {
        return Boolean.TRUE.equals(petsAllowed);
    }

    @NotNull(message = "Available from date is required")
    private LocalDate availableFrom;

    private String description;
}
