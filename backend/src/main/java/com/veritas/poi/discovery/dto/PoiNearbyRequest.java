package com.veritas.poi.discovery.dto;

import com.veritas.poi.PoiType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Query parameters for nearby-places discovery.
 *
 * <p>There is deliberately no latitude/longitude here: the search centre is always resolved on the
 * server from the published property, so a renter can never probe arbitrary coordinates. Field-level
 * constraints express the radius/pagination/sort rules; binding failures (including invalid enum
 * category values) surface through the existing {@code BindException} field-to-message contract.</p>
 */
@Getter
@Setter
public class PoiNearbyRequest {

    /** Optional categories. Bound from {@code categories=A,B} and/or repeated params. Empty = all. */
    private List<PoiType> categories;

    @DecimalMin(value = "1.0", message = "radiusInMeters must be at least 1 metre")
    @DecimalMax(value = "50000.0", message = "radiusInMeters must not exceed 50000 metres")
    private Double radiusInMeters = 2000.0;

    @Min(value = 0, message = "page must be between 0 and 100000")
    @Max(value = 100000, message = "page must be between 0 and 100000")
    private int page = 0;

    @Min(value = 1, message = "size must be positive")
    @Max(value = 50, message = "size must not exceed 50")
    private int size = 20;

    @Pattern(regexp = "distance|name", message = "sortBy must be one of: distance, name")
    private String sortBy = "distance";
}
