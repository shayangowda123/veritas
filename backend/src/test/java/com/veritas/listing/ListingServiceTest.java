package com.veritas.listing;

import com.veritas.common.exception.*;
import com.veritas.listing.dto.CreateListingRequest;
import com.veritas.listing.dto.ListingResponse;
import com.veritas.listing.dto.UpdateListingRequest;
import com.veritas.property.FurnishingStatus;
import com.veritas.property.Property;
import com.veritas.property.PropertyRepository;
import com.veritas.property.PropertyService;
import com.veritas.property.dto.PropertySummaryResponse;
import com.veritas.user.User;
import com.veritas.verification.LocationVerificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ListingServiceTest {

    @Mock
    private ListingRepository listingRepository;

    @Mock
    private PropertyRepository propertyRepository;

    @Mock
    private PropertyService propertyService;

    @InjectMocks
    private ListingService listingService;

    private User owner;
    private Property property;
    private Listing listing;
    private UUID ownerId;
    private UUID propertyId;
    private UUID listingId;

    @BeforeEach
    void setUp() {
        ownerId = UUID.randomUUID();
        propertyId = UUID.randomUUID();
        listingId = UUID.randomUUID();

        owner = new User();
        owner.setId(ownerId);
        owner.setName("Owner");

        property = new Property();
        property.setId(propertyId);
        property.setOwner(owner);
        property.setAddress("123 MG Road");
        property.setPropertyType("FLAT");
        property.setLocationVerificationStatus(LocationVerificationStatus.PENDING);

        listing = new Listing();
        listing.setId(listingId);
        listing.setProperty(property);
        listing.setRent(new BigDecimal("25000.00"));
        listing.setDeposit(new BigDecimal("100000.00"));
        listing.setBhk(2);
        listing.setBathrooms(2);
        listing.setBuiltUpArea(new BigDecimal("1100.00"));
        listing.setFloor(3);
        listing.setFurnishingStatus(FurnishingStatus.SEMI_FURNISHED);
        listing.setAvailableFrom(LocalDate.now().plusDays(7));
        listing.setStatus(ListingStatus.DRAFT);
        listing.setCreatedAt(OffsetDateTime.now());
        listing.setUpdatedAt(OffsetDateTime.now());

        PropertySummaryResponse summary = new PropertySummaryResponse(
                propertyId, "FLAT", "123 MG Road", "MG Road", "Bengaluru",
                12.97, 77.59, LocationVerificationStatus.PENDING, 0, OffsetDateTime.now()
        );
        lenient().when(propertyService.mapToSummary(any(Property.class), anyBoolean())).thenReturn(summary);
    }

    @Test
    @DisplayName("Should create draft listing for owned property")
    void shouldCreateDraftListing() {
        CreateListingRequest request = new CreateListingRequest(
                propertyId,
                new BigDecimal("25000.00"),
                new BigDecimal("100000.00"),
                2, 2,
                new BigDecimal("1100.00"),
                3,
                FurnishingStatus.SEMI_FURNISHED,
                true, true, false,
                LocalDate.now().plusDays(7),
                "Spacious 2BHK flat"
        );

        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(listingRepository.save(any(Listing.class))).thenAnswer(invocation -> {
            Listing l = invocation.getArgument(0);
            l.setId(listingId);
            return l;
        });

        ListingResponse response = listingService.createDraftListing(ownerId, request);

        assertNotNull(response);
        assertEquals(ListingStatus.DRAFT, response.getStatus());
        assertEquals(new BigDecimal("25000.00"), response.getRent());
    }

    @Test
    @DisplayName("Reject creating listing for property owned by another user")
    void shouldRejectListingCreationForUnownedProperty() {
        UUID otherOwnerId = UUID.randomUUID();
        CreateListingRequest request = new CreateListingRequest(
                propertyId,
                new BigDecimal("25000.00"),
                new BigDecimal("100000.00"),
                2, 2,
                new BigDecimal("1100.00"),
                3,
                FurnishingStatus.SEMI_FURNISHED,
                true, true, false,
                LocalDate.now().plusDays(7),
                "Spacious 2BHK flat"
        );

        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));

        assertThrows(UnauthorizedActionException.class, () ->
                listingService.createDraftListing(otherOwnerId, request)
        );
    }

    @Test
    @DisplayName("Draft listing is not visible to public or non-owner")
    void shouldHideDraftListingsFromPublic() {
        UUID thirdPartyUserId = UUID.randomUUID();
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));

        assertThrows(ResourceNotFoundException.class, () ->
                listingService.getListingDetails(listingId, thirdPartyUserId, false)
        );

        assertThrows(ResourceNotFoundException.class, () ->
                listingService.getListingDetails(listingId, null, false)
        );
    }

    @Test
    @DisplayName("Owner can view their own draft listing")
    void shouldAllowOwnerToViewDraftListing() {
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));

        ListingResponse response = listingService.getListingDetails(listingId, ownerId, false);
        assertNotNull(response);
        assertEquals(ListingStatus.DRAFT, response.getStatus());
    }

    @Test
    @DisplayName("Reject publishing incomplete listing (zero or negative rent)")
    void shouldRejectPublishingZeroRentListing() {
        listing.setRent(BigDecimal.ZERO);
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));

        assertThrows(IncompleteListingException.class, () ->
                listingService.publishListing(listingId, ownerId)
        );
    }

    @Test
    @DisplayName("Reject publishing if another active published listing already exists for the property")
    void shouldPreventDuplicatePublishedListing() {
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listingRepository.existsByPropertyIdAndStatusAndIdNot(propertyId, ListingStatus.PUBLISHED, listingId))
                .thenReturn(true);

        assertThrows(ListingConflictException.class, () ->
                listingService.publishListing(listingId, ownerId)
        );
    }

    @Test
    @DisplayName("Successfully publish valid complete listing")
    void shouldPublishCompleteListing() {
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listingRepository.existsByPropertyIdAndStatusAndIdNot(propertyId, ListingStatus.PUBLISHED, listingId))
                .thenReturn(false);
        when(listingRepository.save(any(Listing.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ListingResponse response = listingService.publishListing(listingId, ownerId);

        assertEquals(ListingStatus.PUBLISHED, response.getStatus());
    }

    @Test
    @DisplayName("Reject invalid lifecycle transition (DRAFT directly to RENTED)")
    void shouldRejectInvalidLifecycleTransition() {
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));

        assertThrows(InvalidStateTransitionException.class, () ->
                listingService.updateListingStatus(listingId, ownerId, ListingStatus.RENTED)
        );
    }

    @Test
    @DisplayName("Reject updating an archived listing")
    void shouldRejectUpdatingArchivedListing() {
        listing.setStatus(ListingStatus.ARCHIVED);
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));

        UpdateListingRequest request = new UpdateListingRequest(
                new BigDecimal("26000.00"),
                new BigDecimal("100000.00"),
                2, 2,
                new BigDecimal("1100.00"),
                3,
                FurnishingStatus.SEMI_FURNISHED,
                true, true, false,
                LocalDate.now().plusDays(7),
                "Updated description"
        );

        assertThrows(InvalidStateTransitionException.class, () ->
                listingService.updateListing(listingId, ownerId, request)
        );
    }

    @Test
    @DisplayName("Public renter viewing a published listing receives a nested summary without exact address or coordinates")
    void shouldSanitizeNestedPropertySummaryForPublicListing() {
        listing.setStatus(ListingStatus.PUBLISHED);
        property.setLocationVerificationStatus(LocationVerificationStatus.VERIFIED);
        UUID renterId = UUID.randomUUID();

        // Mirrors PropertyService.mapToSummary(property, false): privacy-safe public summary
        PropertySummaryResponse publicSummary = new PropertySummaryResponse(
                propertyId, "FLAT", null, "MG Road", "Bengaluru",
                null, null, LocationVerificationStatus.VERIFIED, 3, OffsetDateTime.now()
        );
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(propertyService.mapToSummary(property, false)).thenReturn(publicSummary);

        ListingResponse response = listingService.getListingDetails(listingId, renterId, false);

        assertNotNull(response);
        assertNull(response.getProperty().getAddress(), "Nested summary must not leak exact address to renters");
        assertNull(response.getProperty().getLatitude(), "Nested summary must not leak latitude to renters");
        assertNull(response.getProperty().getLongitude(), "Nested summary must not leak longitude to renters");
        assertEquals("MG Road", response.getProperty().getLocality());
        assertEquals(LocationVerificationStatus.VERIFIED, response.getProperty().getLocationVerificationStatus());
        assertEquals(3, response.getProperty().getImageCount());
    }

    @Test
    @DisplayName("Owner viewing their listing receives the full authorized nested property summary")
    void shouldProvideFullNestedPropertySummaryToOwner() {
        PropertySummaryResponse ownerSummary = new PropertySummaryResponse(
                propertyId, "FLAT", "123 MG Road", "MG Road", "Bengaluru",
                12.97, 77.59, LocationVerificationStatus.VERIFIED, 3, OffsetDateTime.now()
        );
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(propertyService.mapToSummary(property, true)).thenReturn(ownerSummary);

        ListingResponse response = listingService.getListingDetails(listingId, ownerId, false);

        assertNotNull(response);
        assertEquals("123 MG Road", response.getProperty().getAddress());
        assertEquals(12.97, response.getProperty().getLatitude());
        assertEquals(77.59, response.getProperty().getLongitude());
    }
}
