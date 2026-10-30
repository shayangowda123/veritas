package com.veritas.property;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.veritas.common.exception.GlobalExceptionHandler;
import com.veritas.config.JacksonConfig;
import com.veritas.config.SecurityConfig;
import com.veritas.listing.ListingController;
import com.veritas.listing.ListingService;
import com.veritas.listing.ListingStatus;
import com.veritas.listing.dto.CreateListingRequest;
import com.veritas.listing.dto.ListingResponse;
import com.veritas.property.dto.CreatePropertyRequest;
import com.veritas.property.dto.PropertyImageResponse;
import com.veritas.property.dto.PropertyResponse;
import com.veritas.property.dto.PropertySummaryResponse;
import com.veritas.security.*;
import com.veritas.user.User;
import com.veritas.user.UserRole;
import com.veritas.verification.LocationVerificationController;
import com.veritas.verification.LocationVerificationService;
import com.veritas.verification.LocationVerificationStatus;
import com.veritas.verification.VerificationMethod;
import com.veritas.verification.dto.LocationVerificationResponse;
import com.veritas.verification.dto.VerifyLocationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {
        PropertyController.class,
        ListingController.class,
        PropertyImageController.class,
        LocationVerificationController.class
})
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
class PropertyControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private PropertyService propertyService;

    @MockitoBean
    private ListingService listingService;

    @MockitoBean
    private PropertyImageService propertyImageService;

    @MockitoBean
    private LocationVerificationService locationVerificationService;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    private User owner;
    private UserDetails ownerPrincipal;
    private String ownerToken;

    private User renter;
    private UserDetails renterPrincipal;
    private String renterToken;

    private User admin;
    private UserDetails adminPrincipal;
    private String adminToken;

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setId(UUID.randomUUID());
        owner.setName("Verified Owner");
        owner.setEmail("owner@example.com");
        owner.setRole(UserRole.OWNER);
        owner.setVerified(true);
        ownerPrincipal = new UserPrincipal(owner);
        ownerToken = jwtService.generateToken(ownerPrincipal);

        renter = new User();
        renter.setId(UUID.randomUUID());
        renter.setName("Verified Renter");
        renter.setEmail("renter@example.com");
        renter.setRole(UserRole.RENTER);
        renter.setVerified(true);
        renterPrincipal = new UserPrincipal(renter);
        renterToken = jwtService.generateToken(renterPrincipal);

        admin = new User();
        admin.setId(UUID.randomUUID());
        admin.setName("Admin User");
        admin.setEmail("admin@example.com");
        admin.setRole(UserRole.ADMIN);
        admin.setVerified(true);
        adminPrincipal = new UserPrincipal(admin);
        adminToken = jwtService.generateToken(adminPrincipal);

        when(customUserDetailsService.loadUserByUsername(owner.getEmail())).thenReturn(ownerPrincipal);
        when(customUserDetailsService.loadUserByUsername(renter.getEmail())).thenReturn(renterPrincipal);
        when(customUserDetailsService.loadUserByUsername(admin.getEmail())).thenReturn(adminPrincipal);
    }

    @Test
    @DisplayName("Unauthenticated request to create property returns 401 Unauthorized")
    void shouldReturn401WhenCreatingPropertyWithoutAuth() throws Exception {
        CreatePropertyRequest request = new CreatePropertyRequest(
                "APARTMENT", "123 Main St", "Indiranagar", "Bengaluru", 12.97, 77.59
        );

        mockMvc.perform(post("/api/v1/properties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Renter attempting to create property returns 403 Forbidden")
    void shouldReturn403WhenRenterCreatesProperty() throws Exception {
        CreatePropertyRequest request = new CreatePropertyRequest(
                "APARTMENT", "123 Main St", "Indiranagar", "Bengaluru", 12.97, 77.59
        );

        mockMvc.perform(post("/api/v1/properties")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + renterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Owner successfully creates property returning 201 Created")
    void shouldAllowOwnerToCreateProperty() throws Exception {
        CreatePropertyRequest request = new CreatePropertyRequest(
                "APARTMENT", "123 Main St", "Indiranagar", "Bengaluru", 12.97, 77.59
        );

        PropertyResponse response = new PropertyResponse(
                UUID.randomUUID(), owner.getId(), owner.getName(), "APARTMENT",
                "123 Main St", "Indiranagar", "Bengaluru", 12.97, 77.59,
                LocationVerificationStatus.PENDING, null, null, null, List.of(),
                OffsetDateTime.now(), OffsetDateTime.now()
        );

        when(propertyService.createProperty(eq(owner.getId()), any(CreatePropertyRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/properties")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.propertyType").value("APARTMENT"))
                .andExpect(jsonPath("$.locationVerificationStatus").value("PENDING"));
    }

    @Test
    @DisplayName("Invalid latitude (> 90) returns 400 Bad Request with validation details")
    void shouldRejectInvalidCoordinates() throws Exception {
        CreatePropertyRequest request = new CreatePropertyRequest(
                "APARTMENT", "123 Main St", "Indiranagar", "Bengaluru", 95.0, 77.59
        );

        mockMvc.perform(post("/api/v1/properties")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.latitude").exists());
    }

    @Test
    @DisplayName("Negative or zero rent returns 400 Bad Request with validation details")
    void shouldRejectInvalidRent() throws Exception {
        String json = """
                {
                    "propertyId": "%s",
                    "rent": 0,
                    "deposit": 50000,
                    "bhk": 2,
                    "bathrooms": 2,
                    "builtUpArea": 1000,
                    "floor": 2,
                    "bachelorsAllowed": true,
                    "parkingAvailable": true,
                    "petsAllowed": false,
                    "availableFrom": "%s",
                    "description": "Cozy home"
                }
                """.formatted(UUID.randomUUID(), LocalDate.now().plusDays(5));

        mockMvc.perform(post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.rent").exists());
    }

    @Test
    @DisplayName("Owner cannot call admin verification endpoint (403 Forbidden)")
    void shouldDenyOwnerAccessToAdminVerificationEndpoint() throws Exception {
        VerifyLocationRequest request = new VerifyLocationRequest(
                LocationVerificationStatus.VERIFIED, VerificationMethod.SITE_VISIT, "Verified"
        );

        mockMvc.perform(post("/api/v1/admin/properties/" + UUID.randomUUID() + "/verify-location")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Admin can call admin verification endpoint returning 200 OK")
    void shouldAllowAdminAccessToVerificationEndpoint() throws Exception {
        UUID propertyId = UUID.randomUUID();
        VerifyLocationRequest request = new VerifyLocationRequest(
                LocationVerificationStatus.VERIFIED, VerificationMethod.SITE_VISIT, "Verified physically"
        );

        LocationVerificationResponse response = new LocationVerificationResponse(
                propertyId, LocationVerificationStatus.VERIFIED, OffsetDateTime.now(),
                "SITE_VISIT", "Verified physically"
        );

        when(locationVerificationService.recordVerificationDecision(eq(propertyId), eq(admin.getId()), any()))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/admin/properties/" + propertyId + "/verify-location")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"));
    }

    @Test
    @DisplayName("Owner can upload property image via multipart form data")
    void shouldAllowOwnerToUploadImage() throws Exception {
        UUID propertyId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "bedroom.jpg",
                "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}
        );

        PropertyImageResponse response = new PropertyImageResponse(
                UUID.randomUUID(), propertyId, "key.jpg", "bedroom.jpg",
                "image/jpeg", 100L, 0, OffsetDateTime.now()
        );

        when(propertyImageService.uploadImage(eq(propertyId), eq(owner.getId()), any()))
                .thenReturn(response);

        mockMvc.perform(multipart("/api/v1/properties/" + propertyId + "/images")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalFilename").value("bedroom.jpg"));
    }

    private PropertyImageResponse sanitizedImage(UUID propertyId, UUID imageId) {
        // Mirrors PropertyService/PropertyImageService public mapping: storageKey nulled, contentUrl derived
        return new PropertyImageResponse(
                imageId, propertyId, null, "photo.jpg",
                "image/jpeg", 2048L, 0, OffsetDateTime.now()
        );
    }

    @Test
    @DisplayName("Anonymous GET property serializes JSON with sensitive fields redacted but keeps verification status")
    void shouldNotLeakSensitiveFieldsToAnonymousPropertyRequest() throws Exception {
        UUID propertyId = UUID.randomUUID();
        UUID imageId = UUID.randomUUID();
        PropertyResponse publicResponse = new PropertyResponse(
                propertyId, null, "Verified Owner", "APARTMENT",
                null, "Indiranagar", "Bengaluru", null, null,
                LocationVerificationStatus.VERIFIED, null, null, null,
                List.of(sanitizedImage(propertyId, imageId)),
                OffsetDateTime.now(), OffsetDateTime.now()
        );

        when(propertyService.getPropertyDetails(eq(propertyId), isNull(), eq(false)))
                .thenReturn(publicResponse);

        mockMvc.perform(get("/api/v1/properties/" + propertyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId", nullValue()))
                .andExpect(jsonPath("$.address", nullValue()))
                .andExpect(jsonPath("$.latitude", nullValue()))
                .andExpect(jsonPath("$.longitude", nullValue()))
                .andExpect(jsonPath("$.verifiedAt", nullValue()))
                .andExpect(jsonPath("$.verificationNotes", nullValue()))
                .andExpect(jsonPath("$.verificationMethod", nullValue()))
                .andExpect(jsonPath("$.locality").value("Indiranagar"))
                .andExpect(jsonPath("$.city").value("Bengaluru"))
                .andExpect(jsonPath("$.locationVerificationStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.images[0].storageKey", nullValue()))
                .andExpect(jsonPath("$.images[0].contentUrl").exists());
    }

    @Test
    @DisplayName("Authenticated renter GET property is treated as public (no privileged fields)")
    void shouldNotLeakSensitiveFieldsToRenterPropertyRequest() throws Exception {
        UUID propertyId = UUID.randomUUID();
        PropertyResponse publicResponse = new PropertyResponse(
                propertyId, null, "Verified Owner", "APARTMENT",
                null, "Indiranagar", "Bengaluru", null, null,
                LocationVerificationStatus.VERIFIED, null, null, null,
                List.of(), OffsetDateTime.now(), OffsetDateTime.now()
        );

        when(propertyService.getPropertyDetails(eq(propertyId), eq(renter.getId()), eq(false)))
                .thenReturn(publicResponse);

        mockMvc.perform(get("/api/v1/properties/" + propertyId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + renterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId", nullValue()))
                .andExpect(jsonPath("$.address", nullValue()))
                .andExpect(jsonPath("$.latitude", nullValue()))
                .andExpect(jsonPath("$.longitude", nullValue()))
                .andExpect(jsonPath("$.locationVerificationStatus").value("VERIFIED"));
    }

    @Test
    @DisplayName("Owner GET property retains authorized address, coordinate and verification details")
    void shouldSerializeFullDetailsForOwnerPropertyRequest() throws Exception {
        UUID propertyId = UUID.randomUUID();
        PropertyResponse ownerResponse = new PropertyResponse(
                propertyId, owner.getId(), "Verified Owner", "APARTMENT",
                "123 Main St", "Indiranagar", "Bengaluru", 12.97, 77.59,
                LocationVerificationStatus.VERIFIED, OffsetDateTime.now(), "Checked on site", "SITE_VISIT",
                List.of(), OffsetDateTime.now(), OffsetDateTime.now()
        );

        when(propertyService.getPropertyDetails(eq(propertyId), eq(owner.getId()), eq(false)))
                .thenReturn(ownerResponse);

        mockMvc.perform(get("/api/v1/properties/" + propertyId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(owner.getId().toString()))
                .andExpect(jsonPath("$.address").value("123 Main St"))
                .andExpect(jsonPath("$.latitude").value(12.97))
                .andExpect(jsonPath("$.longitude").value(77.59))
                .andExpect(jsonPath("$.verificationNotes").value("Checked on site"))
                .andExpect(jsonPath("$.verificationMethod").value("SITE_VISIT"));
    }

    @Test
    @DisplayName("Anonymous GET listing serializes nested property summary without address or coordinates")
    void shouldNotLeakNestedPropertyDetailsInPublicListing() throws Exception {
        UUID listingId = UUID.randomUUID();
        UUID propertyId = UUID.randomUUID();
        PropertySummaryResponse sanitizedSummary = new PropertySummaryResponse(
                propertyId, "FLAT", null, "Indiranagar", "Bengaluru",
                null, null, LocationVerificationStatus.VERIFIED, 2, OffsetDateTime.now()
        );
        ListingResponse listingResponse = new ListingResponse(
                listingId, propertyId, sanitizedSummary,
                new BigDecimal("25000.00"), new BigDecimal("100000.00"), 2, 2,
                new BigDecimal("1100.00"), 3, null,
                true, true, false, LocalDate.now().plusDays(7),
                "Spacious flat", ListingStatus.PUBLISHED, OffsetDateTime.now(), OffsetDateTime.now()
        );

        when(listingService.getListingDetails(eq(listingId), isNull(), eq(false)))
                .thenReturn(listingResponse);

        mockMvc.perform(get("/api/v1/listings/" + listingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.property.address", nullValue()))
                .andExpect(jsonPath("$.property.latitude", nullValue()))
                .andExpect(jsonPath("$.property.longitude", nullValue()))
                .andExpect(jsonPath("$.property.locality").value("Indiranagar"))
                .andExpect(jsonPath("$.property.locationVerificationStatus").value("VERIFIED"));
    }

    @Test
    @DisplayName("Authenticated renter GET image list omits storageKey but keeps contentUrl")
    void shouldNotLeakImageStorageKeyToRenter() throws Exception {
        UUID propertyId = UUID.randomUUID();
        UUID imageId = UUID.randomUUID();
        when(propertyImageService.getImagesForProperty(eq(propertyId), eq(renter.getId()), eq(false)))
                .thenReturn(List.of(sanitizedImage(propertyId, imageId)));

        mockMvc.perform(get("/api/v1/properties/" + propertyId + "/images")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + renterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].storageKey", nullValue()))
                .andExpect(jsonPath("$[0].propertyId").value(propertyId.toString()))
                .andExpect(jsonPath("$[0].contentUrl").exists());
    }
}
