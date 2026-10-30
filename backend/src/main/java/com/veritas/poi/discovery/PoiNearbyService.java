package com.veritas.poi.discovery;

import com.veritas.poi.PoiType;
import com.veritas.poi.discovery.dto.PoiNearbyRequest;
import com.veritas.poi.discovery.dto.PoiNearbyResult;
import com.veritas.poi.discovery.dto.PoiNearbySummary;
import com.veritas.search.dto.PagedResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates nearby-places discovery for a published property.
 *
 * <p>Responsibilities stay separate: resolve the search centre on the server from a published
 * listing, normalize the request into {@link PoiNearbyCriteria}, delegate to
 * {@link PoiNearbyRepository} for a single data query plus a matching count, and map rows to
 * privacy-safe public responses. When the property is not publicly discoverable (no published
 * listing, or the property does not exist) an empty paginated envelope is returned and the POI
 * search is never executed. The resolved centre is never placed in any response.</p>
 */
@Service
public class PoiNearbyService {

    private final PoiNearbyRepository poiNearbyRepository;

    public PoiNearbyService(PoiNearbyRepository poiNearbyRepository) {
        this.poiNearbyRepository = poiNearbyRepository;
    }

    @Transactional(readOnly = true)
    public PagedResponse<PoiNearbyResult> search(UUID propertyId, PoiNearbyRequest request) {
        Pageable pageable = PageRequest.of(request.getPage(), request.getSize());

        Optional<PoiNearbyCentre> centre =
                poiNearbyRepository.findPublishedPropertyCentre(propertyId);
        if (centre.isEmpty()) {
            // Not publicly discoverable: empty envelope, no POI query executed.
            return toEnvelope(new PageImpl<>(List.of(), pageable, 0L));
        }

        PoiNearbyCentre resolved = centre.get();
        PoiNearbyCriteria criteria = new PoiNearbyCriteria(
                resolved.latitude(),
                resolved.longitude(),
                request.getRadiusInMeters(),
                toCategoryNames(request.getCategories()),
                request.getSortBy()
        );

        Page<PoiNearbyRow> page = poiNearbyRepository.search(criteria, pageable);
        return toEnvelope(page.map(this::toResult));
    }

    private List<String> toCategoryNames(List<PoiType> categories) {
        if (categories == null || categories.isEmpty()) {
            return List.of();
        }
        return categories.stream().map(PoiType::name).toList();
    }

    private PoiNearbyResult toResult(PoiNearbyRow row) {
        PoiNearbySummary summary = new PoiNearbySummary(
                row.id(), row.name(), row.type(), row.city(), row.locality()
        );
        return new PoiNearbyResult(summary, row.distanceMeters());
    }

    private PagedResponse<PoiNearbyResult> toEnvelope(Page<PoiNearbyResult> page) {
        return new PagedResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
    }
}
