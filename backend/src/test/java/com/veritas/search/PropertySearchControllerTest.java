package com.veritas.search;

import com.veritas.common.exception.GlobalExceptionHandler;
import com.veritas.config.JacksonConfig;
import com.veritas.config.SecurityConfig;
import com.veritas.property.dto.PropertySummaryResponse;
import com.veritas.search.dto.PagedResponse;
import com.veritas.search.dto.PropertySearchRequest;
import com.veritas.search.dto.PropertySearchResult;
import com.veritas.security.CustomAccessDeniedHandler;
import com.veritas.security.CustomAuthenticationEntryPoint;
import com.veritas.security.CustomUserDetailsService;
import com.veritas.security.JwtAuthenticationFilter;
import com.veritas.security.JwtService;
import com.veritas.verification.LocationVerificationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice tests for the public discovery endpoint. Verifies anonymous access, the privacy
 * shape of the serialized response, and that invalid filter combinations are rejected through the
 * shared 400 {@code errors} contract. The service is mocked so no database is required.
 */
@WebMvcTest(controllers = PropertySearchController.class)
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
class PropertySearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PropertySearchService propertySearchService;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    private PagedResponse<PropertySearchResult> samplePage() {
        PropertySummaryResponse summary = new PropertySummaryResponse(
                UUID.randomUUID(), "APARTMENT", null, "Indiranagar", "Bengaluru",
                null, null, LocationVerificationStatus.VERIFIED, 3, OffsetDateTime.now()
        );
        PropertySearchResult result = new PropertySearchResult(summary, 1500.0);
        return new PagedResponse<>(List.of(result), 0, 20, 1, 1, true, true);
    }

    @Test
    @DisplayName("anonymous search is permitted and returns a privacy-safe paginated payload")
    void anonymousSearchAllowedAndPrivacySafe() throws Exception {
        when(propertySearchService.search(any())).thenReturn(samplePage());

        mockMvc.perform(get("/api/v1/properties/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].property.locality").value("Indiranagar"))
                .andExpect(jsonPath("$.content[0].property.city").value("Bengaluru"))
                .andExpect(jsonPath("$.content[0].property.locationVerificationStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.content[0].property.address", nullValue()))
                .andExpect(jsonPath("$.content[0].property.latitude", nullValue()))
                .andExpect(jsonPath("$.content[0].property.longitude", nullValue()))
                .andExpect(jsonPath("$.content[0].distanceMeters").value(1500.0))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    @DisplayName("supported filters are bound and passed to the service")
    void bindsSupportedFilters() throws Exception {
        when(propertySearchService.search(any())).thenReturn(samplePage());

        mockMvc.perform(get("/api/v1/properties/search")
                        .param("city", "Bengaluru")
                        .param("locality", "Indiranagar")
                        .param("bhk", "2")
                        .param("minRent", "10000")
                        .param("maxRent", "50000")
                        .param("furnishingStatus", "SEMI_FURNISHED")
                        .param("availableFrom", "2026-01-01")
                        .param("petsAllowed", "true")
                        .param("page", "1")
                        .param("size", "15")
                        .param("sortBy", "rent")
                        .param("sortDirection", "desc"))
                .andExpect(status().isOk());

        ArgumentCaptor<PropertySearchRequest> captor =
                ArgumentCaptor.forClass(PropertySearchRequest.class);
        verify(propertySearchService).search(captor.capture());
        PropertySearchRequest bound = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("Bengaluru", bound.getCity());
        org.junit.jupiter.api.Assertions.assertEquals(2, bound.getBhk());
        org.junit.jupiter.api.Assertions.assertEquals(
                com.veritas.property.FurnishingStatus.SEMI_FURNISHED, bound.getFurnishingStatus());
        org.junit.jupiter.api.Assertions.assertEquals(15, bound.getSize());
    }

    @Test
    @DisplayName("minRent greater than maxRent is rejected with an error on maxRent")
    void rejectsInvertedRentRange() throws Exception {
        mockMvc.perform(get("/api/v1/properties/search")
                        .param("minRent", "50000")
                        .param("maxRent", "10000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.maxRent").exists());
    }

    @Test
    @DisplayName("latitude without longitude is rejected")
    void rejectsIncompleteCoordinates() throws Exception {
        mockMvc.perform(get("/api/v1/properties/search").param("latitude", "12.97"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.longitude").exists());
    }

    @Test
    @DisplayName("radius without a centre is rejected")
    void rejectsRadiusWithoutCentre() throws Exception {
        mockMvc.perform(get("/api/v1/properties/search").param("radiusInMeters", "3000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.radiusInMeters").exists());
    }

    @Test
    @DisplayName("excessive radius is rejected")
    void rejectsExcessiveRadius() throws Exception {
        mockMvc.perform(get("/api/v1/properties/search")
                        .param("latitude", "12.97")
                        .param("longitude", "77.59")
                        .param("radiusInMeters", "60000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.radiusInMeters").exists());
    }

    @Test
    @DisplayName("out-of-range page size is rejected")
    void rejectsInvalidPageSize() throws Exception {
        mockMvc.perform(get("/api/v1/properties/search").param("size", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.size").exists());

        mockMvc.perform(get("/api/v1/properties/search").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.size").exists());
    }

    @Test
    @DisplayName("unsupported sort field is rejected")
    void rejectsUnsupportedSort() throws Exception {
        mockMvc.perform(get("/api/v1/properties/search").param("sortBy", "ownerId"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.sortBy").exists());
    }

    @Test
    @DisplayName("an invalid enum value is rejected via type-conversion binding error")
    void rejectsInvalidEnum() throws Exception {
        mockMvc.perform(get("/api/v1/properties/search").param("furnishingStatus", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.furnishingStatus").exists());
    }

    @Test
    @DisplayName("a non-numeric rent value is rejected via type-conversion binding error")
    void rejectsNonNumericRent() throws Exception {
        mockMvc.perform(get("/api/v1/properties/search").param("minRent", "free"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.minRent").exists());
    }

    @Test
    @DisplayName("a valid spatial request reaches the service exactly once")
    void validSpatialRequestDelegatedOnce() throws Exception {
        when(propertySearchService.search(any())).thenReturn(samplePage());

        mockMvc.perform(get("/api/v1/properties/search")
                        .param("latitude", "12.97")
                        .param("longitude", "77.59")
                        .param("radiusInMeters", "3000")
                        .param("sortBy", "distance"))
                .andExpect(status().isOk());

        verify(propertySearchService, times(1)).search(any());
    }
}
