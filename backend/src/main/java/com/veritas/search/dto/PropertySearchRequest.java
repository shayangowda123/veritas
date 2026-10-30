package com.veritas.search.dto;

import com.veritas.property.FurnishingStatus;
import com.veritas.search.validation.ValidPropertySearchFilters;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Public property-discovery query parameters.
 *
 * <p>Every field is optional; the corresponding filter is only applied when the value is present.
 * Field-level constraints express the simple range/limit rules while the class-level
 * {@link ValidPropertySearchFilters} constraint enforces the cross-field rules
 * (coordinate pairing, radius dependency and rent ordering). Validation failures are surfaced
 * through the shared {@code errors} field-to-message contract used across the application.</p>
 */
@Getter
@Setter
@ValidPropertySearchFilters
public class PropertySearchRequest {

    @DecimalMin(value = "0.0", message = "minRent cannot be negative")
    private BigDecimal minRent;

    @DecimalMin(value = "0.0", message = "maxRent cannot be negative")
    private BigDecimal maxRent;

    @Min(value = 1, message = "bhk must be at least 1")
    private Integer bhk;

    private String city;

    private String locality;

    private FurnishingStatus furnishingStatus;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate availableFrom;

    private Boolean petsAllowed;

    private Boolean parkingAvailable;

    private Boolean bachelorsAllowed;

    @DecimalMin(value = "-90.0", message = "Latitude must be >= -90.0")
    @DecimalMax(value = "90.0", message = "Latitude must be <= 90.0")
    private Double latitude;

    @DecimalMin(value = "-180.0", message = "Longitude must be >= -180.0")
    @DecimalMax(value = "180.0", message = "Longitude must be <= 180.0")
    private Double longitude;

    @DecimalMin(value = "0.0", inclusive = false, message = "radiusInMeters must be positive")
    @DecimalMax(value = "50000.0", message = "radiusInMeters must not exceed 50000 metres")
    private Double radiusInMeters;

    @Min(value = 0, message = "page must be zero or greater")
    private int page = 0;

    @Min(value = 1, message = "size must be positive")
    @Max(value = 50, message = "size must not exceed 50")
    private int size = 20;

    @Pattern(regexp = "distance|createdAt|rent|availableFrom|bhk",
            message = "sortBy must be one of: distance, createdAt, rent, availableFrom, bhk")
    private String sortBy;

    @Pattern(regexp = "(?i)asc|desc", message = "sortDirection must be 'asc' or 'desc'")
    private String sortDirection;
}
