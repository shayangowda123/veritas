package com.veritas.poi.discovery;

import com.veritas.common.exception.GlobalExceptionHandler;
import com.veritas.config.JacksonConfig;
import com.veritas.config.SecurityConfig;
import com.veritas.poi.PoiType;
import com.veritas.poi.discovery.dto.PoiNearbyRequest;
import com.veritas.poi.discovery.dto.PoiNearbyResult;
import com.veritas.poi.discovery.dto.PoiNearbySummary;
import com.veritas.search.dto.PagedResponse;
import com.veritas.security.CustomAccessDeniedHandler;
import com.veritas.security.CustomAuthenticationEntryPoint;
import com.veritas.security.CustomUserDetailsService;
import com.veritas.security.JwtAuthenticationFilter;
import com.veritas.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice tests for the public nearby-places endpoint. Verifies anonymous access, correct
 * query defaults, both CSV and repeated category binding shapes, the privacy-safe serialized payload,
 * and that invalid category/radius/pagination/sort values are rejected through the shared 400
 * {@code errors} contract. The service is mocked so no database is required.
 */
@WebMvcTest(controllers = PoiNearbyController.class)
@Import({
        SecurityConfig.class,
        JacksonConfig.class,
        JwtService.class,
        JwtAuthenticationFilter.class,
        CustomAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
@TestPropertySource(properties = {
        "security.jwt.secret-base64=dGVzdC1zZWNyZXQta2V5LWZvci12ZXJpdGFzLXJlbnRhbC1hcHAtMjU2LWJpdC1taW5pbXVtIQ==",
        "security.jwt.expiration-ms=3600000"
})
class PoiNearbyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PoiNearbyService poiNearbyService;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    private static final UUID PROPERTY_ID = UUID.randomUUID();

    private PagedResponse<PoiNearbyResult> samplePage() {
        PoiNearbySummary summary = new PoiNearbySummary(
                UUID.randomUUID(), "Oak School", PoiType.SCHOOL, "Bengaluru", "Indiranagar");
        PoiNearbyResult result = new PoiNearbyResult(summary, 850.0);
        return new PagedResponse<>(List.of(result), 0, 20, 1, 1, true, true);
    }

    @Test
    @DisplayName("anonymous nearby-places request is permitted and returns a privacy-safe payload")
    void anonymousRequestAllowedAndPrivacySafe() throws Exception {
        when(poiNearbyService.search(any(), any())).thenReturn(samplePage());

        mockMvc.perform(get("/api/v1/properties/{propertyId}/nearby-places", PROPERTY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].poi.name").value("Oak School"))
                .andExpect(jsonPath("$.content[0].poi.category").value("SCHOOL"))
                .andExpect(jsonPath("$.content[0].poi.city").value("Bengaluru"))
                .andExpect(jsonPath("$.content[0].poi.locality").value("Indiranagar"))
                .andExpect(jsonPath("$.content[0].distanceMeters").value(850.0))
                // Privacy-by-construction: the serialized POI carries exactly the five safe keys and
                // nothing else - no latitude/longitude/address/source/externalId/licence/timestamps.
                .andExpect(jsonPath("$.content[0].poi", aMapWithSize(5)))
                .andExpect(jsonPath("$.content[0].poi.id").exists())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    @DisplayName("absent query parameters fall back to documented defaults")
    void appliesDefaults() throws Exception {
        when(poiNearbyService.search(any(), any())).thenReturn(samplePage());

        mockMvc.perform(get("/api/v1/properties/{propertyId}/nearby-places", PROPERTY_ID))
                .andExpect(status().isOk());

        ArgumentCaptor<PoiNearbyRequest> captor = ArgumentCaptor.forClass(PoiNearbyRequest.class);
        verify(poiNearbyService).search(eq(PROPERTY_ID), captor.capture());
        PoiNearbyRequest bound = captor.getValue();
        assertEquals(2000.0, bound.getRadiusInMeters());
        assertEquals(0, bound.getPage());
        assertEquals(20, bound.getSize());
        assertEquals("distance", bound.getSortBy());
        // No categories supplied => null (interpreted as "all" by the service).
        assertEquals(null, bound.getCategories());
    }

    @Test
    @DisplayName("comma-separated categories bind to a single list")
    void bindsCsvCategories() throws Exception {
        when(poiNearbyService.search(any(), any())).thenReturn(samplePage());

        mockMvc.perform(get("/api/v1/properties/{propertyId}/nearby-places", PROPERTY_ID)
                        .param("categories", "SCHOOL,COLLEGE"))
                .andExpect(status().isOk());

        ArgumentCaptor<PoiNearbyRequest> captor = ArgumentCaptor.forClass(PoiNearbyRequest.class);
        verify(poiNearbyService).search(eq(PROPERTY_ID), captor.capture());
        assertEquals(List.of(PoiType.SCHOOL, PoiType.COLLEGE), captor.getValue().getCategories());
    }

    @Test
    @DisplayName("repeated category parameters bind to a single list")
    void bindsRepeatedCategories() throws Exception {
        when(poiNearbyService.search(any(), any())).thenReturn(samplePage());

        mockMvc.perform(get("/api/v1/properties/{propertyId}/nearby-places", PROPERTY_ID)
                        .param("categories", "SCHOOL")
                        .param("categories", "PARK"))
                .andExpect(status().isOk());

        ArgumentCaptor<PoiNearbyRequest> captor = ArgumentCaptor.forClass(PoiNearbyRequest.class);
        verify(poiNearbyService).search(eq(PROPERTY_ID), captor.capture());
        assertEquals(List.of(PoiType.SCHOOL, PoiType.PARK), captor.getValue().getCategories());
    }

    @Test
    @DisplayName("an invalid category enum value is rejected with a 400 field error")
    void rejectsInvalidCategory() throws Exception {
        mockMvc.perform(get("/api/v1/properties/{propertyId}/nearby-places", PROPERTY_ID)
                        .param("categories", "NOT_A_PLACE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.categories").exists());
    }

    @Test
    @DisplayName("an out-of-range radius is rejected")
    void rejectsInvalidRadius() throws Exception {
        mockMvc.perform(get("/api/v1/properties/{propertyId}/nearby-places", PROPERTY_ID)
                        .param("radiusInMeters", "60000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.radiusInMeters").exists());

        mockMvc.perform(get("/api/v1/properties/{propertyId}/nearby-places", PROPERTY_ID)
                        .param("radiusInMeters", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.radiusInMeters").exists());
    }

    @Test
    @DisplayName("out-of-range pagination is rejected")
    void rejectsInvalidPagination() throws Exception {
        mockMvc.perform(get("/api/v1/properties/{propertyId}/nearby-places", PROPERTY_ID)
                        .param("size", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.size").exists());

        mockMvc.perform(get("/api/v1/properties/{propertyId}/nearby-places", PROPERTY_ID)
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.size").exists());

        mockMvc.perform(get("/api/v1/properties/{propertyId}/nearby-places", PROPERTY_ID)
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.page").exists());
    }

    @Test
    @DisplayName("a page within the accepted bounds is honoured (0 and 100000)")
    void acceptsBoundaryPages() throws Exception {
        when(poiNearbyService.search(any(), any())).thenReturn(samplePage());

        mockMvc.perform(get("/api/v1/properties/{propertyId}/nearby-places", PROPERTY_ID)
                        .param("page", "0"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/properties/{propertyId}/nearby-places", PROPERTY_ID)
                        .param("page", "100000"))
                .andExpect(status().isOk());

        verify(poiNearbyService, times(2)).search(eq(PROPERTY_ID), any());
    }

    @Test
    @DisplayName("a page above the maximum (100001) is rejected via the shared errors contract")
    void rejectsTooLargePage() throws Exception {
        mockMvc.perform(get("/api/v1/properties/{propertyId}/nearby-places", PROPERTY_ID)
                        .param("page", "100001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.page").exists());
    }

    @Test
    @DisplayName("an unsupported sort value is rejected")
    void rejectsInvalidSortBy() throws Exception {
        mockMvc.perform(get("/api/v1/properties/{propertyId}/nearby-places", PROPERTY_ID)
                        .param("sortBy", "ownerId"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.sortBy").exists());
    }

    @Test
    @DisplayName("a valid request reaches the service exactly once")
    void validRequestDelegatedOnce() throws Exception {
        when(poiNearbyService.search(any(), any())).thenReturn(samplePage());

        mockMvc.perform(get("/api/v1/properties/{propertyId}/nearby-places", PROPERTY_ID)
                        .param("categories", "SCHOOL,METRO")
                        .param("radiusInMeters", "1500")
                        .param("sortBy", "name"))
                .andExpect(status().isOk());

        verify(poiNearbyService, times(1)).search(eq(PROPERTY_ID), any());
    }
}
