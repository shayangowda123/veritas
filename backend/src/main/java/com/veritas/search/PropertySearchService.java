package com.veritas.search;

import com.veritas.property.dto.PropertySummaryResponse;
import com.veritas.search.dto.PagedResponse;
import com.veritas.search.dto.PropertySearchRequest;
import com.veritas.search.dto.PropertySearchResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Orchestrates public, paginated property discovery.
 *
 * <p>Responsibilities are kept separate: the request is normalized into internal
 * {@link PropertySearchCriteria}, delegated to {@link PropertySearchRepository} for a single
 * data query plus a matching count, and mapped to privacy-safe public responses. This service
 * performs no per-result image lookups: the image count already arrives with each projected row,
 * so discovery issues no N+1 queries.</p>
 */
@Service
public class PropertySearchService {

    private final PropertySearchRepository propertySearchRepository;

    public PropertySearchService(PropertySearchRepository propertySearchRepository) {
        this.propertySearchRepository = propertySearchRepository;
    }

    @Transactional(readOnly = true)
    public PagedResponse<PropertySearchResult> search(PropertySearchRequest request) {
        PropertySearchCriteria criteria = toCriteria(request);
        Pageable pageable = PageRequest.of(request.getPage(), request.getSize());

        Page<PropertySearchRow> page = propertySearchRepository.search(criteria, pageable);

        List<PropertySearchResult> content = page.getContent().stream()
                .map(this::toResult)
                .toList();

        return new PagedResponse<>(
                content,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
    }

    private PropertySearchCriteria toCriteria(PropertySearchRequest request) {
        return new PropertySearchCriteria(
                request.getMinRent(),
                request.getMaxRent(),
                request.getBhk(),
                request.getCity(),
                request.getLocality(),
                request.getFurnishingStatus() != null ? request.getFurnishingStatus().name() : null,
                request.getAvailableFrom(),
                request.getPetsAllowed(),
                request.getParkingAvailable(),
                request.getBachelorsAllowed(),
                request.getLatitude(),
                request.getLongitude(),
                request.getRadiusInMeters(),
                request.getSortBy(),
                request.getSortDirection()
        );
    }

    /**
     * Maps a projected row to the privacy-safe public response. Address, latitude, longitude and
     * owner id are explicitly null: the discovery query never selects them, so they cannot leak.
     */
    private PropertySearchResult toResult(PropertySearchRow row) {
        PropertySummaryResponse summary = new PropertySummaryResponse(
                row.getPropertyId(),
                row.getPropertyType(),
                null,                       // exact address: never public
                row.getLocality(),          // safe general-area signal
                row.getCity(),              // safe general-area signal
                null,                       // exact latitude: never public
                null,                       // exact longitude: never public
                row.getLocationVerificationStatus(), // trust signal, preserved publicly
                row.getImageCount(),
                row.getCreatedAt()
        );
        return new PropertySearchResult(summary, row.getDistanceMeters());
    }
}
