package com.veritas.property;

import java.util.UUID;

/**
 * Read-only projection of one row from the batched image-count query: a property id paired with how
 * many images that property owns. Exposes ONLY the aggregate count (never storage keys or other
 * private image columns), so it is safe to use for authorized or public summary mapping alike. It is
 * the result element type that lets a whole page's image counts be resolved in a single grouped
 * query instead of one {@code countByPropertyId} call per row (N+1).
 */
public interface PropertyImageCountProjection {

    UUID getPropertyId();

    Long getImageCount();
}
