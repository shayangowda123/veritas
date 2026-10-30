package com.veritas.search;

import com.veritas.property.FurnishingStatus;
import com.veritas.property.dto.PropertySummaryResponse;
import com.veritas.search.dto.PagedResponse;
import com.veritas.search.dto.PropertySearchRequest;
import com.veritas.search.dto.PropertySearchResult;
import com.veritas.verification.LocationVerificationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies service orchestration: request normalization, privacy-safe mapping, the pagination
 * envelope and the guarantee that discovery performs no per-result image lookup (the image count
 * already arrives on the projected row).
 */
@ExtendWith(MockitoExtension.class)
class PropertySearchServiceTest {

    @Mock
    private PropertySearchRepository propertySearchRepository;

    @InjectMocks
    private PropertySearchService propertySearchService;

    @Test
    @DisplayName("normalizes the request into internal criteria")
    void mapsRequestToCriteria() {
        PropertySearchRequest request = new PropertySearchRequest();
        request.setCity("Bengaluru");
        request.setLocality("Indiranagar");
        request.setBhk(2);
        request.setFurnishingStatus(FurnishingStatus.SEMI_FURNISHED);
        request.setLatitude(12.97);
        request.setLongitude(77.59);
        request.setRadiusInMeters(3000.0);
        request.setPage(1);
        request.setSize(15);
        request.setSortBy("distance");

        when(propertySearchRepository.search(any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 15), 0));

        propertySearchService.search(request);

        ArgumentCaptor<PropertySearchCriteria> criteriaCaptor =
                ArgumentCaptor.forClass(PropertySearchCriteria.class);
        ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(propertySearchRepository).search(criteriaCaptor.capture(), pageCaptor.capture());

        PropertySearchCriteria criteria = criteriaCaptor.getValue();
        assertEquals("Bengaluru", criteria.city());
        assertEquals("Indiranagar", criteria.locality());
        assertEquals(2, criteria.bhk());
        assertEquals("SEMI_FURNISHED", criteria.furnishingStatus());
        assertTrue(criteria.hasCentre());
        assertTrue(criteria.hasRadiusFilter());
        assertEquals(1, pageCaptor.getValue().getPageNumber());
        assertEquals(15, pageCaptor.getValue().getPageSize());
    }

    @Test
    @DisplayName("maps a row to a privacy-safe summary preserving verification status and distance")
    void mapsRowPrivately() {
        UUID id = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.now();
        PropertySearchRow row = new PropertySearchRow(
                id, "APARTMENT", "Indiranagar", "Bengaluru",
                LocationVerificationStatus.VERIFIED, 4, createdAt, 1234.5
        );
        Page<PropertySearchRow> page = new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1);
        when(propertySearchRepository.search(any(), any())).thenReturn(page);

        PropertySearchRequest request = new PropertySearchRequest();
        PagedResponse<PropertySearchResult> response = propertySearchService.search(request);

        assertEquals(1, response.content().size());
        PropertySummaryResponse summary = response.content().get(0).property();
        assertEquals(id, summary.getId());
        assertEquals("APARTMENT", summary.getPropertyType());
        // Privacy: exact address and coordinates are never exposed publicly.
        assertNull(summary.getAddress());
        assertNull(summary.getLatitude());
        assertNull(summary.getLongitude());
        // Trust signal preserved + safe general-area retained.
        assertEquals(LocationVerificationStatus.VERIFIED, summary.getLocationVerificationStatus());
        assertEquals("Indiranagar", summary.getLocality());
        assertEquals("Bengaluru", summary.getCity());
        assertEquals(4, summary.getImageCount());
        assertEquals(1234.5, response.content().get(0).distanceMeters());
    }

    @Test
    @DisplayName("distance is null when no search centre was supplied")
    void distanceNullWithoutCentre() {
        PropertySearchRow row = new PropertySearchRow(
                UUID.randomUUID(), "FLAT", "Koramangala", "Bengaluru",
                LocationVerificationStatus.PENDING, 0, OffsetDateTime.now(), null
        );
        when(propertySearchRepository.search(any(), any()))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));

        PagedResponse<PropertySearchResult> response =
                propertySearchService.search(new PropertySearchRequest());

        assertNull(response.content().get(0).distanceMeters());
    }

    @Test
    @DisplayName("builds the pagination envelope from the repository page")
    void buildsEnvelope() {
        List<PropertySearchRow> rows = List.of(row(), row());
        Page<PropertySearchRow> page = new PageImpl<>(rows, PageRequest.of(1, 2), 5);
        when(propertySearchRepository.search(any(), any())).thenReturn(page);

        PropertySearchRequest request = new PropertySearchRequest();
        request.setPage(1);
        request.setSize(2);
        PagedResponse<PropertySearchResult> response = propertySearchService.search(request);

        assertEquals(2, response.content().size());
        assertEquals(1, response.page());
        assertEquals(2, response.size());
        assertEquals(5, response.totalElements());
        assertEquals(3, response.totalPages());
        assertTrue(!response.first() && !response.last());
    }

    @Test
    @DisplayName("issues exactly one repository search call and no per-result image lookups (no N+1)")
    void singleRepositoryCallNoImageLookup() {
        List<PropertySearchRow> rows = List.of(row(), row(), row());
        when(propertySearchRepository.search(any(), any()))
                .thenReturn(new PageImpl<>(rows, PageRequest.of(0, 20), 3));

        propertySearchService.search(new PropertySearchRequest());

        // One data query drives the whole page; the service collaborates only with the search
        // repository (the image count arrives projected on each row), so no per-result query runs.
        verify(propertySearchRepository, times(1)).search(any(), any());
        verifyNoMoreInteractions(propertySearchRepository);
    }

    private PropertySearchRow row() {
        return new PropertySearchRow(
                UUID.randomUUID(), "FLAT", "Lokhandwala", "Mumbai",
                LocationVerificationStatus.VERIFIED, 2, OffsetDateTime.now(), null
        );
    }
}
