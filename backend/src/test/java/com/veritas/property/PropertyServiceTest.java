package com.veritas.property;

import com.veritas.common.exception.ResourceNotFoundException;
import com.veritas.common.exception.UnauthorizedActionException;
import com.veritas.listing.ListingRepository;
import com.veritas.property.dto.CreatePropertyRequest;
import com.veritas.property.dto.PropertyResponse;
import com.veritas.property.dto.PropertySummaryResponse;
import com.veritas.property.dto.UpdatePropertyRequest;
import com.veritas.user.User;
import com.veritas.user.UserRepository;
import com.veritas.user.UserRole;
import com.veritas.verification.LocationVerificationStatus;
import com.veritas.verification.geocoding.service.LocationConsistencyResult;
import com.veritas.verification.geocoding.service.LocationConsistencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PropertyServiceTest {

    @Mock
    private PropertyRepository propertyRepository;

    @Mock
    private PropertyImageRepository propertyImageRepository;

    @Mock
    private ListingRepository listingRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private LocationConsistencyService locationConsistencyService;

    @InjectMocks
    private PropertyService propertyService;

    private User owner;
    private User renter;
    private Property property;
    private UUID propertyId;
    private UUID ownerId;
    private UUID renterId;
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    @BeforeEach
    void setUp() {
        ownerId = UUID.randomUUID();
        renterId = UUID.randomUUID();
        propertyId = UUID.randomUUID();

        owner = new User();
        owner.setId(ownerId);
        owner.setName("Property Owner");
        owner.setEmail("owner@example.com");
        owner.setRole(UserRole.OWNER);

        renter = new User();
        renter.setId(renterId);
        renter.setName("Renter");
        renter.setEmail("renter@example.com");
        renter.setRole(UserRole.RENTER);

        property = new Property();
        property.setId(propertyId);
        property.setOwner(owner);
        property.setPropertyType("APARTMENT");
        property.setAddress("123 Indiranagar, Bengaluru");
        property.setLocality("Indiranagar");
        property.setCity("Bengaluru");
        property.setLocation(geometryFactory.createPoint(new Coordinate(77.641154, 12.978369)));
        property.setLocationVerificationStatus(LocationVerificationStatus.VERIFIED);
        property.setVerifiedAt(OffsetDateTime.now());
        property.setVerificationMethod("SITE_AUDIT");
        property.setVerificationNotes("Physically checked");
        property.setCreatedAt(OffsetDateTime.now());
        property.setUpdatedAt(OffsetDateTime.now());
    }

    @Test
    @DisplayName("Should create property with valid owner and initial PENDING verification status even on MATCH")
    void shouldCreatePropertySuccessfully() {
        CreatePropertyRequest request = new CreatePropertyRequest(
                "APARTMENT",
                "123 Indiranagar",
                "Indiranagar",
                "Bengaluru",
                12.978369,
                77.641154
        );

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(locationConsistencyService.checkConsistency(any(), any(), any(), any(), any()))
                .thenReturn(LocationConsistencyResult.match(45.0, null));
        when(propertyRepository.save(any(Property.class))).thenAnswer(invocation -> {
            Property p = invocation.getArgument(0);
            p.setId(propertyId);
            return p;
        });

        PropertyResponse response = propertyService.createProperty(ownerId, request);

        assertNotNull(response);
        assertEquals("APARTMENT", response.getPropertyType());
        // CRITICAL INVARIANT: Automated match never sets VERIFIED status; it remains PENDING
        assertEquals(LocationVerificationStatus.PENDING, response.getLocationVerificationStatus());
        assertNull(response.getVerifiedAt(), "verifiedAt must remain null for automated consistency matches");
        assertEquals("AUTOMATED_GEOCODING", response.getVerificationMethod());
        assertTrue(response.getVerificationNotes().contains("MATCH"));
        assertEquals(12.978369, response.getLatitude(), 0.0001);
        assertEquals(77.641154, response.getLongitude(), 0.0001);
    }

    @Test
    @DisplayName("Should assign REVIEW_REQUIRED status when location consistency detects a MISMATCH")
    void shouldSetReviewRequiredOnLocationMismatch() {
        CreatePropertyRequest request = new CreatePropertyRequest(
                "APARTMENT",
                "123 Indiranagar",
                "Indiranagar",
                "Bengaluru",
                12.978369,
                77.641154
        );

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(locationConsistencyService.checkConsistency(any(), any(), any(), any(), any()))
                .thenReturn(LocationConsistencyResult.mismatch(4500.0, null));
        when(propertyRepository.save(any(Property.class))).thenAnswer(invocation -> {
            Property p = invocation.getArgument(0);
            p.setId(propertyId);
            return p;
        });

        PropertyResponse response = propertyService.createProperty(ownerId, request);

        assertNotNull(response);
        assertEquals(LocationVerificationStatus.REVIEW_REQUIRED, response.getLocationVerificationStatus());
        assertNull(response.getVerifiedAt());
        assertTrue(response.getVerificationNotes().contains("MISMATCH"));
    }

    @Test
    @DisplayName("Should fail safely and preserve reviewable PENDING state if geocoding throws an exception")
    void shouldFailSafelyWhenGeocodingFails() {
        CreatePropertyRequest request = new CreatePropertyRequest(
                "APARTMENT",
                "123 Indiranagar",
                "Indiranagar",
                "Bengaluru",
                12.978369,
                77.641154
        );

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(locationConsistencyService.checkConsistency(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Simulated external connection failure"));
        when(propertyRepository.save(any(Property.class))).thenAnswer(invocation -> {
            Property p = invocation.getArgument(0);
            p.setId(propertyId);
            return p;
        });

        // Property creation must not throw; it must fail safely preserving PENDING state
        PropertyResponse response = propertyService.createProperty(ownerId, request);

        assertNotNull(response);
        assertEquals(LocationVerificationStatus.PENDING, response.getLocationVerificationStatus());
        assertNull(response.getVerifiedAt());
        assertTrue(response.getVerificationNotes().contains("unexpected internal error"));
    }

    @Test
    @DisplayName("Should reject property creation if user is not OWNER or ADMIN")
    void shouldRejectPropertyCreationForRenter() {
        CreatePropertyRequest request = new CreatePropertyRequest(
                "APARTMENT",
                "123 Indiranagar",
                "Indiranagar",
                "Bengaluru",
                12.978369,
                77.641154
        );

        when(userRepository.findById(renterId)).thenReturn(Optional.of(renter));

        assertThrows(UnauthorizedActionException.class, () ->
                propertyService.createProperty(renterId, request)
        );
    }

    @Test
    @DisplayName("Updating property coordinates or address automatically resets verification to PENDING")
    void shouldResetVerificationOnLocationChange() {
        UpdatePropertyRequest request = new UpdatePropertyRequest(
                "APARTMENT",
                "456 New Road, Koramangala",
                "Koramangala",
                "Bengaluru",
                12.935242,
                77.624480
        );

        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(propertyRepository.save(any(Property.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PropertyResponse response = propertyService.updateProperty(propertyId, ownerId, false, request);

        assertEquals(LocationVerificationStatus.PENDING, response.getLocationVerificationStatus());
        assertNull(response.getVerifiedAt());
        assertNull(response.getVerificationMethod());
        assertNull(response.getVerificationNotes());
    }

    @Test
    @DisplayName("Owner A cannot update Owner B's property")
    void shouldRejectUpdateFromUnauthorizedOwner() {
        UUID otherOwnerId = UUID.randomUUID();
        UpdatePropertyRequest request = new UpdatePropertyRequest(
                "APARTMENT",
                "123 Indiranagar",
                "Indiranagar",
                "Bengaluru",
                12.978369,
                77.641154
        );

        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));

        assertThrows(UnauthorizedActionException.class, () ->
                propertyService.updateProperty(propertyId, otherOwnerId, false, request)
        );
    }

    @Test
    @DisplayName("ADMIN can update another owner's property (Phase 3B.6E)")
    void adminCanUpdateOthersProperty() {
        UUID adminId = UUID.randomUUID();
        UpdatePropertyRequest request = new UpdatePropertyRequest(
                "APARTMENT",
                "456 Admin Fixed Road, Koramangala",
                "Koramangala",
                "Bengaluru",
                12.935242,
                77.624480
        );

        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(propertyRepository.save(any(Property.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PropertyResponse response = propertyService.updateProperty(propertyId, adminId, true, request);

        assertEquals("456 Admin Fixed Road, Koramangala", response.getAddress());
        assertEquals("Koramangala", response.getLocality());
    }

    @Test
    @DisplayName("ADMIN update must NOT transfer property ownership (Phase 3B.6E)")
    void adminUpdateDoesNotTransferOwnership() {
        UUID adminId = UUID.randomUUID();
        UpdatePropertyRequest request = new UpdatePropertyRequest(
                "APARTMENT",
                "123 Indiranagar",
                "Indiranagar",
                "Bengaluru",
                12.978369,
                77.641154
        );

        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(propertyRepository.save(any(Property.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PropertyResponse response = propertyService.updateProperty(propertyId, adminId, true, request);

        // The original owner must remain the owner in both the entity and the mapped response.
        assertSame(owner, property.getOwner(), "ADMIN update must not reassign property.owner");
        assertEquals(ownerId, response.getOwnerId(), "response must still show the ORIGINAL owner id");
        assertEquals("Property Owner", response.getOwnerName());
    }

    @Test
    @DisplayName("Non-admin stranger without a role match remains rejected (fail-closed flag)")
    void nonAdminActorRemainsRejected() {
        UpdatePropertyRequest request = new UpdatePropertyRequest(
                "APARTMENT",
                "123 Indiranagar",
                "Indiranagar",
                "Bengaluru",
                12.978369,
                77.641154
        );

        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));

        // actorIsAdmin=false must never be treated as admin by the shared helper.
        assertThrows(UnauthorizedActionException.class, () ->
                propertyService.updateProperty(propertyId, renterId, false, request)
        );
        verify(propertyRepository, never()).save(any(Property.class));
    }

    @Test
    @DisplayName("Non-owner receives 404 (not 403) for a private property, so existence is not disclosed")
    void shouldDenyPrivatePropertyAccessToNonOwner() {
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(listingRepository.hasPublishedListing(propertyId)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () ->
                propertyService.getPropertyDetails(propertyId, renterId, false)
        );
    }

    @Test
    @DisplayName("Non-owner browsing published listing receives sanitized property response omitting full address, coordinates and notes")
    void shouldAllowViewingPropertyWithPublishedListing() {
        PropertyImage img = new PropertyImage();
        img.setId(UUID.randomUUID());
        img.setProperty(property);
        img.setStorageKey("secret/path/img.jpg");
        img.setOriginalFilename("photo.jpg");
        img.setContentType("image/jpeg");
        img.setFileSizeBytes(5000L);
        img.setDisplayOrder(0);
        img.setCreatedAt(OffsetDateTime.now());

        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(listingRepository.hasPublishedListing(propertyId)).thenReturn(true);
        when(propertyImageRepository.findByPropertyIdOrderByDisplayOrderAsc(propertyId)).thenReturn(List.of(img));

        // Renter browsing published listing
        PropertyResponse response = propertyService.getPropertyDetails(propertyId, renterId, false);

        assertNotNull(response);
        assertEquals(propertyId, response.getId());
        // PRIVACY HARDENING ASSERTIONS:
        assertNull(response.getOwnerId(), "Internal owner id must be omitted for non-owners");
        assertNull(response.getOwnerName(), "Owner name (PII) must be omitted for non-owners");
        assertNull(response.getAddress(), "Full street address must be omitted for non-owners");
        assertNull(response.getLatitude(), "Exact latitude must be omitted for non-owners");
        assertNull(response.getLongitude(), "Exact longitude must be omitted for non-owners");
        assertNull(response.getVerificationNotes(), "Internal verification notes must be omitted for non-owners");
        assertNull(response.getVerificationMethod(), "Internal verification method must be omitted for non-owners");
        assertNull(response.getVerifiedAt(), "Verification timestamp must be omitted for non-owners");
        // Safe general neighborhood attributes are preserved
        assertEquals("Indiranagar", response.getLocality());
        assertEquals("Bengaluru", response.getCity());
        assertEquals(LocationVerificationStatus.VERIFIED, response.getLocationVerificationStatus());
        // Images must omit internal storage keys
        assertEquals(1, response.getImages().size());
        assertNull(response.getImages().get(0).getStorageKey(), "Storage keys must be omitted for non-owners");
        assertNotNull(response.getImages().get(0).getContentUrl(), "Safe content URL must be provided");
    }

    @Test
    @DisplayName("Property owner retains full access to exact address, coordinates, and verification notes")
    void shouldProvideFullDetailsToAuthorizedOwner() {
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(propertyImageRepository.findByPropertyIdOrderByDisplayOrderAsc(propertyId)).thenReturn(List.of());

        PropertyResponse response = propertyService.getPropertyDetails(propertyId, ownerId, false);

        assertNotNull(response);
        assertEquals(ownerId, response.getOwnerId(), "Owner id must remain available to the authorized owner");
        assertEquals("Property Owner", response.getOwnerName(), "Owner name must remain available to the authorized owner");
        assertEquals("123 Indiranagar, Bengaluru", response.getAddress());
        assertEquals(12.978369, response.getLatitude(), 0.0001);
        assertEquals(77.641154, response.getLongitude(), 0.0001);
        assertEquals("SITE_AUDIT", response.getVerificationMethod());
        assertEquals("Physically checked", response.getVerificationNotes());
        assertNotNull(response.getVerifiedAt());
    }

    @Test
    @DisplayName("Public property summary omits exact address and coordinates but keeps locality, city and verification status")
    void shouldSanitizePublicPropertySummary() {
        when(propertyImageRepository.countByPropertyId(propertyId)).thenReturn(4L);

        PropertySummaryResponse summary = propertyService.mapToSummary(property, false);

        assertNull(summary.getAddress(), "Exact address must be omitted from public summaries");
        assertNull(summary.getLatitude(), "Latitude must be omitted from public summaries");
        assertNull(summary.getLongitude(), "Longitude must be omitted from public summaries");
        assertEquals("Indiranagar", summary.getLocality());
        assertEquals("Bengaluru", summary.getCity());
        assertEquals(LocationVerificationStatus.VERIFIED, summary.getLocationVerificationStatus());
        assertEquals(4, summary.getImageCount());
    }

    @Test
    @DisplayName("Owner property summary retains exact address and coordinates")
    void shouldProvideFullOwnerPropertySummary() {
        when(propertyImageRepository.countByPropertyId(propertyId)).thenReturn(4L);

        PropertySummaryResponse summary = propertyService.mapToSummary(property, true);

        assertEquals("123 Indiranagar, Bengaluru", summary.getAddress());
        assertEquals(12.978369, summary.getLatitude(), 0.0001);
        assertEquals(77.641154, summary.getLongitude(), 0.0001);
        assertEquals(LocationVerificationStatus.VERIFIED, summary.getLocationVerificationStatus());
    }

    @Test
    @DisplayName("Administrator retains full access to property verification details")
    void shouldProvideFullDetailsToAdmin() {
        UUID adminId = UUID.randomUUID();
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(propertyImageRepository.findByPropertyIdOrderByDisplayOrderAsc(propertyId)).thenReturn(List.of());

        PropertyResponse response = propertyService.getPropertyDetails(propertyId, adminId, true);

        assertNotNull(response);
        assertEquals("Property Owner", response.getOwnerName(), "Owner name must remain available to an authorized admin");
        assertEquals("123 Indiranagar, Bengaluru", response.getAddress());
        assertEquals(12.978369, response.getLatitude(), 0.0001);
        assertEquals(77.641154, response.getLongitude(), 0.0001);
        assertEquals("SITE_AUDIT", response.getVerificationMethod());
        assertEquals("Physically checked", response.getVerificationNotes());
    }

    @Test
    @DisplayName("Anonymous viewer of a published property receives no owner name and keeps existing privacy redaction")
    void shouldRedactOwnerNameForAnonymousViewer() {
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(listingRepository.hasPublishedListing(propertyId)).thenReturn(true);
        when(propertyImageRepository.findByPropertyIdOrderByDisplayOrderAsc(propertyId)).thenReturn(List.of());

        PropertyResponse response = propertyService.getPropertyDetails(propertyId, null, false);

        // PII fix: owner name must be omitted for anonymous viewers alongside the existing redactions.
        assertNull(response.getOwnerName(), "Owner name (PII) must be omitted for anonymous viewers");
        assertNull(response.getOwnerId(), "Internal owner id must be omitted for anonymous viewers");
        assertNull(response.getAddress(), "Full street address must be omitted for anonymous viewers");
        assertNull(response.getLatitude(), "Latitude must be omitted for anonymous viewers");
        assertNull(response.getLongitude(), "Longitude must be omitted for anonymous viewers");
        // Safe general attributes remain visible for published properties.
        assertEquals("Indiranagar", response.getLocality());
        assertEquals("Bengaluru", response.getCity());
    }

    // ---- Phase 3B.4B: batched image counts (N+1 elimination) ---------------------------------

    /** Test-only implementation of the grouped image-count projection. */
    private record ImageCount(UUID propertyId, Long imageCount) implements PropertyImageCountProjection {
        @Override
        public UUID getPropertyId() {
            return propertyId;
        }

        @Override
        public Long getImageCount() {
            return imageCount;
        }
    }

    private Property propertyWithId(UUID id) {
        Property p = new Property();
        p.setId(id);
        p.setOwner(owner);
        p.setPropertyType("APARTMENT");
        p.setAddress("Addr " + id);
        p.setLocality("Indiranagar");
        p.setCity("Bengaluru");
        p.setLocation(geometryFactory.createPoint(new Coordinate(77.641154, 12.978369)));
        p.setLocationVerificationStatus(LocationVerificationStatus.VERIFIED);
        p.setCreatedAt(OffsetDateTime.now());
        p.setUpdatedAt(OffsetDateTime.now());
        return p;
    }

    @Test
    @DisplayName("getOwnerProperties batches image counts in ONE grouped query (no per-row COUNT)")
    void getOwnerPropertiesBatchesImageCounts() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();
        List<Property> props = List.of(propertyWithId(p1), propertyWithId(p2), propertyWithId(p3));
        Pageable pageable = PageRequest.of(0, 20);
        when(propertyRepository.findByOwnerId(ownerId, pageable))
                .thenReturn(new PageImpl<>(props, pageable, props.size()));
        // p1 -> 2, p2 -> 5, p3 absent from the grouped result -> 0
        when(propertyImageRepository.countByPropertyIds(any()))
                .thenReturn(List.of(new ImageCount(p1, 2L), new ImageCount(p2, 5L)));

        Page<PropertySummaryResponse> page = propertyService.getOwnerProperties(ownerId, pageable);

        assertEquals(2, page.getContent().get(0).getImageCount());
        assertEquals(5, page.getContent().get(1).getImageCount());
        assertEquals(0, page.getContent().get(2).getImageCount(),
                "a property absent from the grouped result must receive a count of 0");
        // Exactly one grouped query, and never the per-row count.
        verify(propertyImageRepository, times(1)).countByPropertyIds(any());
        verify(propertyImageRepository, never()).countByPropertyId(any());
    }

    @Test
    @DisplayName("Empty owner-properties page issues no image-count query at all")
    void emptyOwnerPropertiesPageIssuesNoImageCountQuery() {
        Pageable pageable = PageRequest.of(0, 20);
        when(propertyRepository.findByOwnerId(ownerId, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        Page<PropertySummaryResponse> page = propertyService.getOwnerProperties(ownerId, pageable);

        assertTrue(page.isEmpty());
        verify(propertyImageRepository, never()).countByPropertyIds(any());
        verify(propertyImageRepository, never()).countByPropertyId(any());
    }

    @Test
    @DisplayName("loadImageCounts dedupes repeated ids into a single grouped query")
    void loadImageCountsDedupesIds() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(propertyImageRepository.countByPropertyIds(any()))
                .thenReturn(List.of(new ImageCount(a, 3L), new ImageCount(b, 1L)));

        Map<UUID, Long> counts = propertyService.loadImageCounts(List.of(a, b, a, a, b));

        assertEquals(3L, counts.get(a));
        assertEquals(1L, counts.get(b));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(propertyImageRepository).countByPropertyIds(captor.capture());
        assertEquals(2, captor.getValue().size(), "duplicate ids must be collapsed before the query");
    }

    @Test
    @DisplayName("loadImageCounts short-circuits an empty id set without querying")
    void loadImageCountsEmptyShortCircuits() {
        Map<UUID, Long> counts = propertyService.loadImageCounts(List.of());

        assertTrue(counts.isEmpty());
        verify(propertyImageRepository, never()).countByPropertyIds(any());
    }
}
