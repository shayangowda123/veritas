package com.veritas.poi.discovery;

import com.veritas.poi.PoiType;
import com.veritas.poi.discovery.dto.PoiNearbyRequest;
import com.veritas.poi.discovery.dto.PoiNearbyResult;
import com.veritas.poi.discovery.dto.PoiNearbySummary;
import com.veritas.search.dto.PagedResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the discovery orchestration: published-property gating, empty-envelope behaviour,
 * criteria normalization and privacy-safe mapping. The repository is mocked so no database is needed.
 */
@ExtendWith(MockitoExtension.class)
class PoiNearbyServiceTest {

    @Mock
    private PoiNearbyRepository poiNearbyRepository;

    @InjectMocks
    private PoiNearbyService poiNearbyService;

    private static PoiNearbyRequest request() {
        PoiNearbyRequest request = new PoiNearbyRequest();
        request.setCategories(List.of(PoiType.SCHOOL, PoiType.COLLEGE));
        request.setRadiusInMeters(1500.0);
        request.setPage(0);
        request.setSize(20);
        request.setSortBy("distance");
        return request;
    }

    @Test
    @DisplayName("published property resolves a server-side centre and returns mapped, distance-carrying results")
    void publishedHappyPath() {
        UUID propertyId = UUID.randomUUID();
        when(poiNearbyRepository.findPublishedPropertyCentre(propertyId))
                .thenReturn(Optional.of(new PoiNearbyCentre(12.97, 77.59)));

        PoiNearbyRow row = new PoiNearbyRow(
                UUID.randomUUID(), "Oak School", PoiType.SCHOOL, "Bengaluru", "Indiranagar", 850.0);
        Page<PoiNearbyRow> page = new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1);
        when(poiNearbyRepository.search(any(PoiNearbyCriteria.class), any(Pageable.class)))
                .thenReturn(page);

        PagedResponse<PoiNearbyResult> response = poiNearbyService.search(propertyId, request());

        ArgumentCaptor<PoiNearbyCriteria> captor = ArgumentCaptor.forClass(PoiNearbyCriteria.class);
        verify(poiNearbyRepository).search(captor.capture(), any(Pageable.class));
        PoiNearbyCriteria criteria = captor.getValue();
        // Centre comes from the server, categories are normalized to enum names, params forwarded.
        assertEquals(12.97, criteria.latitude());
        assertEquals(77.59, criteria.longitude());
        assertEquals(1500.0, criteria.radiusInMeters());
        assertEquals(List.of("SCHOOL", "COLLEGE"), criteria.categories());
        assertEquals("distance", criteria.sortBy());
        assertTrue(criteria.hasCentre());

        assertEquals(1, response.content().size());
        PoiNearbyResult result = response.content().get(0);
        assertEquals("Oak School", result.poi().name());
        assertEquals(PoiType.SCHOOL, result.poi().category());
        assertEquals(850.0, result.distanceMeters());
        assertEquals(1, response.totalElements());
        assertEquals(1, response.totalPages());
        assertTrue(response.first());
        assertTrue(response.last());
    }

    @Test
    @DisplayName("unpublished property (no centre) returns an empty envelope and never queries POIs")
    void unpublishedReturnsEmptyAndSkipsSearch() {
        UUID propertyId = UUID.randomUUID();
        when(poiNearbyRepository.findPublishedPropertyCentre(propertyId))
                .thenReturn(Optional.empty());

        PagedResponse<PoiNearbyResult> response = poiNearbyService.search(propertyId, request());

        assertTrue(response.content().isEmpty());
        assertEquals(0, response.totalElements());
        assertEquals(0, response.totalPages());
        assertEquals(0, response.page());
        assertEquals(20, response.size());
        assertTrue(response.first());
        assertTrue(response.last());
        verify(poiNearbyRepository, never()).search(any(), any());
    }

    @Test
    @DisplayName("missing property yields the same empty envelope (no existence disclosure)")
    void missingPropertyIsEmptyEnvelope() {
        UUID propertyId = UUID.randomUUID();
        when(poiNearbyRepository.findPublishedPropertyCentre(propertyId))
                .thenReturn(Optional.empty());

        PagedResponse<PoiNearbyResult> response = poiNearbyService.search(propertyId, request());

        assertTrue(response.content().isEmpty());
        verify(poiNearbyRepository, never()).search(any(), any());
    }

    @Test
    @DisplayName("pagination page and size are honoured on the empty envelope")
    void emptyEnvelopePreservesRequestedPage() {
        UUID propertyId = UUID.randomUUID();
        PoiNearbyRequest request = request();
        request.setPage(2);
        request.setSize(15);
        when(poiNearbyRepository.findPublishedPropertyCentre(propertyId))
                .thenReturn(Optional.empty());

        PagedResponse<PoiNearbyResult> response = poiNearbyService.search(propertyId, request);

        assertEquals(2, response.page());
        assertEquals(15, response.size());
        assertFalse(response.first());
        assertTrue(response.last());
    }

    @Test
    @DisplayName("public projection is privacy-safe by construction: no coordinates or ingestion metadata")
    void resultCarriesNoCoordinatesOrMetadata() {
        List<String> summaryFields = Arrays.stream(PoiNearbySummary.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        // Only safe, coarse fields are ever exposed.
        assertEquals(List.of("id", "name", "category", "city", "locality"), summaryFields);
        for (String forbidden : List.of(
                "latitude", "longitude", "location", "address",
                "source", "externalId", "sourceUrl", "licence", "createdAt", "updatedAt", "lastSeenAt")) {
            assertFalse(summaryFields.contains(forbidden),
                    "public summary must not expose " + forbidden);
        }

        List<String> resultFields = Arrays.stream(PoiNearbyResult.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertEquals(List.of("poi", "distanceMeters"), resultFields);
    }
}
