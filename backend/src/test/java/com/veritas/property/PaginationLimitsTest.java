package com.veritas.property;

import com.veritas.common.exception.GlobalExceptionHandler;
import com.veritas.config.JacksonConfig;
import com.veritas.config.SecurityConfig;
import com.veritas.listing.ListingController;
import com.veritas.listing.ListingService;
import com.veritas.listing.ListingStatus;
import com.veritas.security.CustomAccessDeniedHandler;
import com.veritas.security.CustomAuthenticationEntryPoint;
import com.veritas.security.CustomUserDetailsService;
import com.veritas.security.JwtAuthenticationFilter;
import com.veritas.security.JwtService;
import com.veritas.security.UserPrincipal;
import com.veritas.user.User;
import com.veritas.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the Spring Boot pagination bounds configured for the owner "my" endpoints
 * (Phase 3B.4B): a default page size of 20 and a hard maximum of 100. An oversized client
 * {@code size} parameter must be silently clamped to the maximum (not rejected, and not honoured).
 *
 * <p>Uses a web slice with the real controllers + security chain (as in
 * {@code PropertyControllerSecurityTest}); the services are mocked so the {@link Pageable} actually
 * resolved by Spring Data's argument resolver can be captured and inspected. The pageable bounds
 * are pinned via {@code @TestPropertySource} so this test is deterministic regardless of profile.</p>
 */
@WebMvcTest(controllers = {PropertyController.class, ListingController.class})
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
        "security.jwt.expiration-ms=3600000",
        "spring.data.web.pageable.default-page-size=20",
        "spring.data.web.pageable.max-page-size=100"
})
class PaginationLimitsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private PropertyService propertyService;

    @MockitoBean
    private ListingService listingService;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    private User owner;
    private String ownerToken;

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setId(UUID.randomUUID());
        owner.setName("Verified Owner");
        owner.setEmail("owner@example.com");
        owner.setRole(UserRole.OWNER);
        owner.setVerified(true);
        UserDetails principal = new UserPrincipal(owner);
        ownerToken = jwtService.generateToken(principal);
        when(customUserDetailsService.loadUserByUsername(owner.getEmail())).thenReturn(principal);

        // The endpoints' return value is irrelevant to page-size resolution; return empty pages.
        when(propertyService.getOwnerProperties(any(), any())).thenReturn(Page.empty());
        when(listingService.getOwnerListings(any(), any(), any())).thenReturn(Page.empty());
    }

    private int capturedPropertyPageSize(String query) throws Exception {
        mockMvc.perform(get("/api/v1/properties/my" + query)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk());
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(propertyService).getOwnerProperties(eq(owner.getId()), captor.capture());
        return captor.getValue().getPageSize();
    }

    @Test
    @DisplayName("owner properties default to page size 20")
    void defaultSizeIsTwenty() throws Exception {
        assertEquals(20, capturedPropertyPageSize(""));
    }

    @Test
    @DisplayName("owner properties honour an in-bounds requested size")
    void inBoundsSizeHonoured() throws Exception {
        assertEquals(50, capturedPropertyPageSize("?size=50"));
    }

    @Test
    @DisplayName("owner properties size=100000 is capped at the maximum of 100")
    void oversizedSizeCappedTo100() throws Exception {
        assertEquals(100, capturedPropertyPageSize("?size=100000"));
    }

    @Test
    @DisplayName("owner listings size=100000 is capped at the maximum of 100")
    void listingsOversizedSizeCappedTo100() throws Exception {
        mockMvc.perform(get("/api/v1/listings/my?size=100000")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk());
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(listingService).getOwnerListings(eq(owner.getId()), any(), captor.capture());
        assertEquals(100, captor.getValue().getPageSize());
    }

    // ---- Phase 3B.5: owner sorting + page-number safety -------------------------------------

    private Pageable propertyPageable(String query) throws Exception {
        mockMvc.perform(get("/api/v1/properties/my" + query)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk());
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(propertyService).getOwnerProperties(eq(owner.getId()), captor.capture());
        clearInvocations(propertyService);
        return captor.getValue();
    }

    private Pageable listingPageable(String query) throws Exception {
        mockMvc.perform(get("/api/v1/listings/my" + query)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk());
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(listingService).getOwnerListings(eq(owner.getId()), any(), captor.capture());
        clearInvocations(listingService);
        return captor.getValue();
    }

    private void assertPropertiesRejected(String query) throws Exception {
        mockMvc.perform(get("/api/v1/properties/my" + query)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isBadRequest());
        verify(propertyService, never()).getOwnerProperties(any(), any());
        clearInvocations(propertyService);
    }

    private void assertListingsRejected(String query) throws Exception {
        mockMvc.perform(get("/api/v1/listings/my" + query)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isBadRequest());
        verify(listingService, never()).getOwnerListings(any(), any(), any());
        clearInvocations(listingService);
    }

    @Test
    @DisplayName("owner properties default to page 0, size 20, createdAt DESC + id ASC tie-breaker")
    void defaultSortCarriesDeterministicTieBreaker() throws Exception {
        Pageable pageable = propertyPageable("");
        assertEquals(0, pageable.getPageNumber());
        assertEquals(20, pageable.getPageSize());
        assertEquals(Sort.by(Sort.Direction.DESC, "createdAt")
                .and(Sort.by(Sort.Direction.ASC, "id")), pageable.getSort());
    }

    @Test
    @DisplayName("owner properties maximum page 1000 is accepted")
    void maximumPageAccepted() throws Exception {
        assertEquals(1000, propertyPageable("?page=1000").getPageNumber());
    }

    @Test
    @DisplayName("owner properties page above the maximum is rejected with 400")
    void pageAboveMaximumRejected() throws Exception {
        assertPropertiesRejected("?page=1001");
    }

    @Test
    @DisplayName("owner properties negative page is rejected with 400")
    void negativePageRejected() throws Exception {
        assertPropertiesRejected("?page=-1");
    }

    @Test
    @DisplayName("owner properties size at the maximum 100 is accepted")
    void sizeAtMaximumAccepted() throws Exception {
        assertEquals(100, propertyPageable("?size=100").getPageSize());
    }

    @Test
    @DisplayName("owner properties honour a whitelisted sort field and preserve direction")
    void validPropertySortFieldHonoured() throws Exception {
        Pageable pageable = propertyPageable("?sort=city,asc");
        assertEquals(Sort.by(Sort.Direction.ASC, "city")
                .and(Sort.by(Sort.Direction.ASC, "id")), pageable.getSort());
    }

    @Test
    @DisplayName("owner properties reject an unknown sort field with 400")
    void unknownSortFieldRejected() throws Exception {
        assertPropertiesRejected("?sort=nonExistingField,asc");
    }

    @Test
    @DisplayName("owner properties reject a nested sort field (owner.name) with 400")
    void nestedSortFieldRejected() throws Exception {
        assertPropertiesRejected("?sort=owner.name,asc");
    }

    @Test
    @DisplayName("owner properties accept multiple whitelisted sort fields")
    void multipleValidSortFieldsAccepted() throws Exception {
        Pageable pageable = propertyPageable("?sort=city,asc&sort=locality,desc");
        assertEquals(Sort.by(Sort.Direction.ASC, "city")
                .and(Sort.by(Sort.Direction.DESC, "locality"))
                .and(Sort.by(Sort.Direction.ASC, "id")), pageable.getSort());
    }

    @Test
    @DisplayName("owner properties reject a request where one of several sort fields is invalid")
    void oneInvalidFieldAmongMultipleRejected() throws Exception {
        assertPropertiesRejected("?sort=city,asc&sort=bogus,desc");
    }

    @Test
    @DisplayName("owner properties reject an invalid sort direction with 400")
    void invalidSortDirectionRejected() throws Exception {
        assertPropertiesRejected("?sort=createdAt,foo");
    }

    @Test
    @DisplayName("owner listings default sort is createdAt DESC + id ASC")
    void listingsDefaultSortTieBreaker() throws Exception {
        assertEquals(Sort.by(Sort.Direction.DESC, "createdAt")
                .and(Sort.by(Sort.Direction.ASC, "id")), listingPageable("").getSort());
    }

    @Test
    @DisplayName("owner listings honour rent, bhk and availableFrom sorting")
    void listingsValidSortFieldsHonoured() throws Exception {
        assertEquals(Sort.by(Sort.Direction.ASC, "rent")
                .and(Sort.by(Sort.Direction.ASC, "id")), listingPageable("?sort=rent,asc").getSort());
        assertEquals(Sort.by(Sort.Direction.DESC, "bhk")
                .and(Sort.by(Sort.Direction.ASC, "id")), listingPageable("?sort=bhk,desc").getSort());
        assertEquals(Sort.by(Sort.Direction.ASC, "availableFrom")
                .and(Sort.by(Sort.Direction.ASC, "id")), listingPageable("?sort=availableFrom,asc").getSort());
    }

    @Test
    @DisplayName("owner listings accept multiple valid sort fields with tie-breaker")
    void listingsMultipleValidSortFieldsAccepted() throws Exception {
        Pageable pageable = listingPageable("?sort=rent,asc&sort=bhk,desc");
        assertEquals(Sort.by(Sort.Direction.ASC, "rent")
                .and(Sort.by(Sort.Direction.DESC, "bhk"))
                .and(Sort.by(Sort.Direction.ASC, "id")), pageable.getSort());
    }

    @Test
    @DisplayName("owner listings reject unknown and nested (property.city) sort fields with 400")
    void listingsInvalidSortFieldsRejected() throws Exception {
        assertListingsRejected("?sort=nonExistingField,asc");
        assertListingsRejected("?sort=property.city,asc");
    }

    @Test
    @DisplayName("owner listings enforce the same page boundaries")
    void listingsPageBoundariesEnforced() throws Exception {
        assertEquals(1000, listingPageable("?page=1000").getPageNumber());
        assertListingsRejected("?page=1001");
        assertListingsRejected("?page=-1");
    }

    @Test
    @DisplayName("owner listings keep status filtering intact alongside safe sorting")
    void listingsStatusFilteringPreserved() throws Exception {
        mockMvc.perform(get("/api/v1/listings/my?status=DRAFT&sort=rent,asc")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk());
        ArgumentCaptor<ListingStatus> statusCaptor = ArgumentCaptor.forClass(ListingStatus.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(listingService).getOwnerListings(eq(owner.getId()), statusCaptor.capture(), pageableCaptor.capture());
        assertEquals(ListingStatus.DRAFT, statusCaptor.getValue());
        assertEquals(Sort.by(Sort.Direction.ASC, "rent")
                .and(Sort.by(Sort.Direction.ASC, "id")), pageableCaptor.getValue().getSort());
    }
}
