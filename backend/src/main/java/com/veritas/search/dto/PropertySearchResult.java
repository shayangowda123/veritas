package com.veritas.search.dto;

import com.veritas.property.dto.PropertySummaryResponse;

/**
 * A single public discovery result: a privacy-safe property summary plus the distance (in metres)
 * from the supplied search centre, when one was provided.
 *
 * <p>Distance is derived from a user-supplied centre and therefore does not reveal the property's
 * absolute coordinates. The nested {@link PropertySummaryResponse} never carries the exact address,
 * coordinates, owner id, or internal verification/storage data.</p>
 *
 * @param property       privacy-safe summary
 * @param distanceMeters metres from the search centre; {@code null} when no centre was supplied
 */
public record PropertySearchResult(
        PropertySummaryResponse property,
        Double distanceMeters
) {
}
