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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
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
        listing.setVersion(0);
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

        ListingResponse response = listingService.createDraftListing(ownerId, false, request);

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
                listingService.createDraftListing(otherOwnerId, false, request)
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
                listingService.publishListing(listingId, ownerId, false, 0)
        );
    }

    @Test
    @DisplayName("Reject publishing if another active published listing already exists for the property")
    void shouldPreventDuplicatePublishedListing() {
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listingRepository.existsByPropertyIdAndStatusAndIdNot(propertyId, ListingStatus.PUBLISHED, listingId))
                .thenReturn(true);

        assertThrows(ListingConflictException.class, () ->
                listingService.publishListing(listingId, ownerId, false, 0)
        );
    }

    @Test
    @DisplayName("Successfully publish valid complete listing")
    void shouldPublishCompleteListing() {
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listingRepository.existsByPropertyIdAndStatusAndIdNot(propertyId, ListingStatus.PUBLISHED, listingId))
                .thenReturn(false);
        when(listingRepository.saveAndFlush(any(Listing.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ListingResponse response = listingService.publishListing(listingId, ownerId, false, 0);

        assertEquals(ListingStatus.PUBLISHED, response.getStatus());
    }

    @Test
    @DisplayName("Reject invalid lifecycle transition (DRAFT directly to RENTED)")
    void shouldRejectInvalidLifecycleTransition() {
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));

        assertThrows(InvalidStateTransitionException.class, () ->
                listingService.updateListingStatus(listingId, ownerId, false, ListingStatus.RENTED, 0)
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
                "Updated description", 0
        );

        assertThrows(InvalidStateTransitionException.class, () ->
                listingService.updateListing(listingId, ownerId, false, request)
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

    @Test
    @DisplayName("Concurrent publication race: DuplicateKeyException is translated to ListingConflictException (409)")
    void shouldTranslateDuplicateKeyToConflict() {
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listingRepository.existsByPropertyIdAndStatusAndIdNot(propertyId, ListingStatus.PUBLISHED, listingId))
                .thenReturn(false);
        when(listingRepository.saveAndFlush(any(Listing.class))).thenThrow(new DuplicateKeyException(
                "duplicate key value violates unique constraint \"uq_active_published_listing_per_property\""));

        // The DB unique-index violation becomes the existing 409 domain conflict; the raw
        // SQL/constraint message is never surfaced to the caller.
        assertThrows(ListingConflictException.class, () ->
                listingService.publishListing(listingId, ownerId, false, 0));
    }

    @Test
    @DisplayName("Non-duplicate DataIntegrityViolationException is NOT reclassified as a listing conflict")
    void shouldNotMisclassifyOtherDataIntegrityViolations() {
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listingRepository.existsByPropertyIdAndStatusAndIdNot(propertyId, ListingStatus.PUBLISHED, listingId))
                .thenReturn(false);
        when(listingRepository.saveAndFlush(any(Listing.class)))
                .thenThrow(new DataIntegrityViolationException("null value in column \"x\" violates not-null constraint"));

        // FK/CHECK/NOT-NULL style failures must propagate untouched (no global DB -> 409 mapping).
        assertThrows(DataIntegrityViolationException.class, () ->
                listingService.publishListing(listingId, ownerId, false, 0));
    }

    @Test
    @DisplayName("Real PostgreSQL case: base DataIntegrityViolationException on the published-listing index becomes a 409 conflict")
    void baseDataIntegrityViolationOnPublishedIndexBecomesConflict() {
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listingRepository.existsByPropertyIdAndStatusAndIdNot(propertyId, ListingStatus.PUBLISHED, listingId))
                .thenReturn(false);
        // Exactly what Hibernate/PostgreSQL surfaces on a concurrent publish: the BASE
        // DataIntegrityViolationException (not DuplicateKeyException) wrapping a 23505 PSQLException.
        SQLException sql = new SQLException(
                "ERROR: duplicate key value violates unique constraint \"uq_active_published_listing_per_property\"",
                "23505", 0);
        when(listingRepository.saveAndFlush(any(Listing.class)))
                .thenThrow(new DataIntegrityViolationException("could not execute statement", sql));

        assertThrows(ListingConflictException.class, () ->
                listingService.publishListing(listingId, ownerId, false, 0));
    }

    @Test
    @DisplayName("Unique violation on a DIFFERENT constraint is NOT reclassified as a listing conflict")
    void uniqueViolationOnDifferentConstraintIsNotReclassified() {
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listingRepository.existsByPropertyIdAndStatusAndIdNot(propertyId, ListingStatus.PUBLISHED, listingId))
                .thenReturn(false);
        SQLException sql = new SQLException(
                "ERROR: duplicate key value violates unique constraint \"uq_users_email\"", "23505", 0);
        when(listingRepository.saveAndFlush(any(Listing.class)))
                .thenThrow(new DataIntegrityViolationException("could not execute statement", sql));

        // 23505 but a different index -> must propagate, never a 409.
        assertThrows(DataIntegrityViolationException.class, () ->
                listingService.publishListing(listingId, ownerId, false, 0));
    }

    @Test
    @DisplayName("Foreign-key violation (SQLSTATE 23503) is NOT reclassified as a listing conflict")
    void foreignKeyViolationIsNotReclassified() {
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listingRepository.existsByPropertyIdAndStatusAndIdNot(propertyId, ListingStatus.PUBLISHED, listingId))
                .thenReturn(false);
        SQLException sql = new SQLException(
                "ERROR: insert or update on table \"listings\" violates foreign key constraint \"fk_listing_property\"",
                "23503", 0);
        when(listingRepository.saveAndFlush(any(Listing.class)))
                .thenThrow(new DataIntegrityViolationException("could not execute statement", sql));

        assertThrows(DataIntegrityViolationException.class, () ->
                listingService.publishListing(listingId, ownerId, false, 0));
    }

    @Test
    @DisplayName("Non-owner READ of any non-published status (DRAFT/UNAVAILABLE/RENTED/ARCHIVED) yields 404")
    void shouldReturn404ForNonOwnerAcrossNonPublishedStatuses() {
        UUID thirdParty = UUID.randomUUID();
        for (ListingStatus status : List.of(
                ListingStatus.DRAFT, ListingStatus.UNAVAILABLE, ListingStatus.RENTED, ListingStatus.ARCHIVED)) {
            listing.setStatus(status);
            when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
            assertThrows(ResourceNotFoundException.class, () ->
                            listingService.getListingDetails(listingId, thirdParty, false),
                    "status " + status + " must be a 404 for a non-owner");
        }
    }

    @Test
    @DisplayName("Unauthorized WRITE operations still yield 403 (ownership enforcement unchanged)")
    void shouldKeepWriteAuthorizationForbidden() {
        UUID intruder = UUID.randomUUID();
        UpdateListingRequest request = new UpdateListingRequest(
                new BigDecimal("26000.00"), new BigDecimal("100000.00"), 2, 2,
                new BigDecimal("1100.00"), 3, FurnishingStatus.SEMI_FURNISHED,
                true, true, false, LocalDate.now().plusDays(7), "updated", 0);

        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));

        assertThrows(UnauthorizedActionException.class, () ->
                listingService.updateListing(listingId, intruder, false, request));
        assertThrows(UnauthorizedActionException.class, () ->
                listingService.publishListing(listingId, intruder, false, 0));
        assertThrows(UnauthorizedActionException.class, () ->
                listingService.updateListingStatus(listingId, intruder, false, ListingStatus.PUBLISHED, 0));
    }

    @Test
    @DisplayName("getOwnerListings batches image counts once for the whole page (no per-listing COUNT)")
    void getOwnerListingsBatchesImageCounts() {
        Pageable pageable = PageRequest.of(0, 20);

        // Second listing points at a different property that has NO images (absent from the grouped result).
        UUID otherPropertyId = UUID.randomUUID();
        Property otherProperty = new Property();
        otherProperty.setId(otherPropertyId);
        otherProperty.setOwner(owner);
        Listing otherListing = new Listing();
        otherListing.setId(UUID.randomUUID());
        otherListing.setProperty(otherProperty);
        otherListing.setStatus(ListingStatus.DRAFT);

        // `listing` (from setUp) uses `property` (propertyId).
        List<Listing> content = List.of(listing, otherListing);
        when(listingRepository.findByOwnerId(ownerId, pageable))
                .thenReturn(new PageImpl<>(content, pageable, content.size()));
        // Only `property` has images; otherProperty is intentionally absent -> must map to 0.
        when(propertyService.loadImageCounts(any())).thenReturn(Map.of(propertyId, 4L));
        // Mirror PropertyService.mapToSummary(property, flag, count): encode the provided count into the summary.
        when(propertyService.mapToSummary(any(Property.class), anyBoolean(), anyLong())).thenAnswer(inv -> {
            Property p = inv.getArgument(0);
            long count = inv.getArgument(2);
            return new PropertySummaryResponse(p.getId(), "FLAT", "addr", "loc", "city",
                    1.0, 2.0, LocationVerificationStatus.VERIFIED, (int) count, OffsetDateTime.now());
        });

        Page<ListingResponse> page = listingService.getOwnerListings(ownerId, null, pageable);

        assertEquals(4, page.getContent().get(0).getProperty().getImageCount(),
                "property present in the grouped result uses its batched count");
        assertEquals(0, page.getContent().get(1).getProperty().getImageCount(),
                "property absent from the grouped result must default to 0");
        // One grouped load for the page; the per-row 2-arg mapToSummary is never used in the page path.
        verify(propertyService, times(1)).loadImageCounts(any());
        verify(propertyService, never()).mapToSummary(any(Property.class), anyBoolean());
    }

    // ---- Phase 3B.6C: expected-version (optimistic-locking) enforcement ------------------------

    private UpdateListingRequest updateRequest(int version) {
        UpdateListingRequest r = new UpdateListingRequest();
        r.setRent(new BigDecimal("26000.00"));
        r.setDeposit(new BigDecimal("100000.00"));
        r.setBhk(2);
        r.setBathrooms(2);
        r.setBuiltUpArea(new BigDecimal("1100.00"));
        r.setFloor(3);
        r.setAvailableFrom(LocalDate.now().plusDays(7));
        r.setDescription("updated");
        r.setVersion(version);
        return r;
    }

    @Test
    @DisplayName("Update with a stale expected version is rejected as a conflict and never written")
    void updateStaleVersionRejected() {
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing)); // listing version 0

        assertThrows(ListingConflictException.class, () ->
                listingService.updateListing(listingId, ownerId, false, updateRequest(5)));

        // The client-supplied version must never be copied onto the managed entity, and no write occurs.
        assertEquals(0, listing.getVersion(), "service must not assign the client version to the entity");
        verify(listingRepository, never()).saveAndFlush(any(Listing.class));
        verify(listingRepository, never()).save(any(Listing.class));
    }

    @Test
    @DisplayName("Update with a matching expected version succeeds")
    void updateMatchingVersionSucceeds() {
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listingRepository.saveAndFlush(any(Listing.class))).thenAnswer(inv -> inv.getArgument(0));

        ListingResponse response = listingService.updateListing(listingId, ownerId, false, updateRequest(0));

        assertNotNull(response);
        assertEquals(new BigDecimal("26000.00"), response.getRent());
        verify(listingRepository).saveAndFlush(any(Listing.class));
    }

    @Test
    @DisplayName("Publish with a stale expected version is rejected before the duplicate precheck")
    void publishStaleVersionRejected() {
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing)); // version 0

        assertThrows(ListingConflictException.class, () ->
                listingService.publishListing(listingId, ownerId, false, 7));

        assertEquals(0, listing.getVersion());
        verify(listingRepository, never()).existsByPropertyIdAndStatusAndIdNot(any(), any(), any());
        verify(listingRepository, never()).saveAndFlush(any(Listing.class));
    }

    @Test
    @DisplayName("Publish with a matching expected version succeeds")
    void publishMatchingVersionSucceeds() {
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listingRepository.existsByPropertyIdAndStatusAndIdNot(propertyId, ListingStatus.PUBLISHED, listingId))
                .thenReturn(false);
        when(listingRepository.saveAndFlush(any(Listing.class))).thenAnswer(inv -> inv.getArgument(0));

        ListingResponse response = listingService.publishListing(listingId, ownerId, false, 0);

        assertEquals(ListingStatus.PUBLISHED, response.getStatus());
    }

    @Test
    @DisplayName("Status change with a stale expected version is rejected as a conflict and never written")
    void statusStaleVersionRejected() {
        listing.setStatus(ListingStatus.PUBLISHED); // so UNAVAILABLE would be a legal transition
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing)); // version 0

        assertThrows(ListingConflictException.class, () ->
                listingService.updateListingStatus(listingId, ownerId, false, ListingStatus.UNAVAILABLE, 9));

        assertEquals(ListingStatus.PUBLISHED, listing.getStatus(), "stale request must not change status");
        verify(listingRepository, never()).saveAndFlush(any(Listing.class));
    }

    @Test
    @DisplayName("Status change with a matching expected version succeeds")
    void statusMatchingVersionSucceeds() {
        listing.setStatus(ListingStatus.PUBLISHED); // PUBLISHED -> UNAVAILABLE is legal
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listingRepository.saveAndFlush(any(Listing.class))).thenAnswer(inv -> inv.getArgument(0));

        ListingResponse response =
                listingService.updateListingStatus(listingId, ownerId, false, ListingStatus.UNAVAILABLE, 0);

        assertEquals(ListingStatus.UNAVAILABLE, response.getStatus());
    }

    // ---- Phase 3B.6D: centralized lifecycle enforcement ----------------------------------------

    @Test
    @DisplayName("DRAFT listing is editable")
    void draftEditSucceeds() {
        listing.setStatus(ListingStatus.DRAFT);
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listingRepository.saveAndFlush(any(Listing.class))).thenAnswer(inv -> inv.getArgument(0));

        ListingResponse response = listingService.updateListing(listingId, ownerId, false, updateRequest(0));

        assertEquals(new BigDecimal("26000.00"), response.getRent());
        verify(listingRepository).saveAndFlush(any(Listing.class));
    }

    @Test
    @DisplayName("PUBLISHED listing is editable")
    void publishedEditSucceeds() {
        listing.setStatus(ListingStatus.PUBLISHED);
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listingRepository.saveAndFlush(any(Listing.class))).thenAnswer(inv -> inv.getArgument(0));

        listingService.updateListing(listingId, ownerId, false, updateRequest(0));

        verify(listingRepository).saveAndFlush(any(Listing.class));
    }

    @Test
    @DisplayName("UNAVAILABLE listing is editable")
    void unavailableEditSucceeds() {
        listing.setStatus(ListingStatus.UNAVAILABLE);
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listingRepository.saveAndFlush(any(Listing.class))).thenAnswer(inv -> inv.getArgument(0));

        listingService.updateListing(listingId, ownerId, false, updateRequest(0));

        verify(listingRepository).saveAndFlush(any(Listing.class));
    }

    @Test
    @DisplayName("RENTED listing cannot be edited and no field or version changes")
    void rentedEditRejected() {
        listing.setStatus(ListingStatus.RENTED);
        listing.setRent(new BigDecimal("25000.00"));
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));

        assertThrows(InvalidStateTransitionException.class, () ->
                listingService.updateListing(listingId, ownerId, false, updateRequest(0)));

        assertEquals(ListingStatus.RENTED, listing.getStatus());
        assertEquals(new BigDecimal("25000.00"), listing.getRent(), "rejected edit must not touch business fields");
        assertEquals(0, listing.getVersion());
        verify(listingRepository, never()).save(any(Listing.class));
        verify(listingRepository, never()).saveAndFlush(any(Listing.class));
    }

    @Test
    @DisplayName("Every allowed transition succeeds through the status endpoint")
    void allowedTransitionsSucceed() {
        // PUBLISHED -> RENTED
        listing.setStatus(ListingStatus.PUBLISHED);
        assertTransitionOk(ListingStatus.RENTED, ListingStatus.RENTED);
        // UNAVAILABLE -> RENTED
        listing.setStatus(ListingStatus.UNAVAILABLE);
        assertTransitionOk(ListingStatus.RENTED, ListingStatus.RENTED);
        // RENTED -> ARCHIVED
        listing.setStatus(ListingStatus.RENTED);
        assertTransitionOk(ListingStatus.ARCHIVED, ListingStatus.ARCHIVED);
        // DRAFT -> ARCHIVED, PUBLISHED -> ARCHIVED, UNAVAILABLE -> ARCHIVED (any non-ARCHIVED -> ARCHIVED)
        listing.setStatus(ListingStatus.DRAFT);
        assertTransitionOk(ListingStatus.ARCHIVED, ListingStatus.ARCHIVED);
        listing.setStatus(ListingStatus.PUBLISHED);
        assertTransitionOk(ListingStatus.ARCHIVED, ListingStatus.ARCHIVED);
        listing.setStatus(ListingStatus.UNAVAILABLE);
        assertTransitionOk(ListingStatus.ARCHIVED, ListingStatus.ARCHIVED);
    }

    @Test
    @DisplayName("UNAVAILABLE -> PUBLISHED is allowed via the publish operation")
    void unavailableToPublishedAllowed() {
        listing.setStatus(ListingStatus.UNAVAILABLE);
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listingRepository.existsByPropertyIdAndStatusAndIdNot(propertyId, ListingStatus.PUBLISHED, listingId))
                .thenReturn(false);
        when(listingRepository.saveAndFlush(any(Listing.class))).thenAnswer(inv -> inv.getArgument(0));

        ListingResponse response = listingService.publishListing(listingId, ownerId, false, 0);

        assertEquals(ListingStatus.PUBLISHED, response.getStatus());
    }

    @Test
    @DisplayName("UNAVAILABLE -> PUBLISHED is allowed via the status endpoint delegation")
    void unavailableToPublishedAllowedViaStatus() {
        listing.setStatus(ListingStatus.UNAVAILABLE);
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listingRepository.existsByPropertyIdAndStatusAndIdNot(propertyId, ListingStatus.PUBLISHED, listingId))
                .thenReturn(false);
        when(listingRepository.saveAndFlush(any(Listing.class))).thenAnswer(inv -> inv.getArgument(0));

        ListingResponse response =
                listingService.updateListingStatus(listingId, ownerId, false, ListingStatus.PUBLISHED, 0);

        assertEquals(ListingStatus.PUBLISHED, response.getStatus());
    }

    @Test
    @DisplayName("Rejected transitions throw the 400 lifecycle exception and never write")
    void rejectedTransitionsFail() {
        // RENTED -> UNAVAILABLE, RENTED -> DRAFT
        listing.setStatus(ListingStatus.RENTED);
        assertTransitionRejected(ListingStatus.UNAVAILABLE);
        assertTransitionRejected(ListingStatus.DRAFT);
        // DRAFT -> UNAVAILABLE
        listing.setStatus(ListingStatus.DRAFT);
        assertTransitionRejected(ListingStatus.UNAVAILABLE);
        // ARCHIVED -> PUBLISHED / DRAFT / RENTED / UNAVAILABLE (terminal)
        listing.setStatus(ListingStatus.ARCHIVED);
        assertTransitionRejected(ListingStatus.PUBLISHED);
        assertTransitionRejected(ListingStatus.DRAFT);
        assertTransitionRejected(ListingStatus.RENTED);
        assertTransitionRejected(ListingStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("RENTED -> PUBLISHED is rejected through the dedicated publish operation")
    void rentedDirectPublishRejected() {
        listing.setStatus(ListingStatus.RENTED);
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));

        assertThrows(InvalidStateTransitionException.class, () ->
                listingService.publishListing(listingId, ownerId, false, 0));

        assertEquals(ListingStatus.RENTED, listing.getStatus());
        verify(listingRepository, never()).existsByPropertyIdAndStatusAndIdNot(any(), any(), any());
        verify(listingRepository, never()).saveAndFlush(any(Listing.class));
    }

    @Test
    @DisplayName("RENTED -> PUBLISHED via the status endpoint cannot bypass the publish lifecycle policy")
    void rentedPublishViaStatusRejected() {
        listing.setStatus(ListingStatus.RENTED);
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));

        assertThrows(InvalidStateTransitionException.class, () ->
                listingService.updateListingStatus(listingId, ownerId, false, ListingStatus.PUBLISHED, 0));

        assertEquals(ListingStatus.RENTED, listing.getStatus());
        verify(listingRepository, never()).saveAndFlush(any(Listing.class));
    }

    @Test
    @DisplayName("Same-state requests are idempotent no-ops: response returned, no write, version unchanged")
    void sameStateIsIdempotentNoOp() {
        for (ListingStatus status : List.of(ListingStatus.PUBLISHED,
                ListingStatus.UNAVAILABLE, ListingStatus.RENTED, ListingStatus.ARCHIVED)) {
            listing.setStatus(status);
            reset(listingRepository);
            when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));

            ListingResponse response =
                    listingService.updateListingStatus(listingId, ownerId, false, status, 0);

            assertEquals(status, response.getStatus(), "same-state must echo current status for " + status);
            assertEquals(0, listing.getVersion(), "same-state no-op must not change the version for " + status);
            verify(listingRepository, never()).save(any(Listing.class));
            verify(listingRepository, never()).saveAndFlush(any(Listing.class));
        }
    }

    @Test
    @DisplayName("Publishing an already-PUBLISHED listing is an idempotent no-op")
    void publishAlreadyPublishedIsNoOp() {
        listing.setStatus(ListingStatus.PUBLISHED);
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));

        ListingResponse response = listingService.publishListing(listingId, ownerId, false, 0);

        assertEquals(ListingStatus.PUBLISHED, response.getStatus());
        assertEquals(0, listing.getVersion());
        verify(listingRepository, never()).saveAndFlush(any(Listing.class));
    }

    @Test
    @DisplayName("Precedence: authorization (403) is checked before lifecycle validity")
    void unauthorizedBegetsInvalidTransitionIsForbidden() {
        listing.setStatus(ListingStatus.RENTED); // RENTED -> PUBLISHED would be an invalid transition
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        UUID intruder = UUID.randomUUID();

        assertThrows(UnauthorizedActionException.class, () ->
                listingService.publishListing(listingId, intruder, false, 0));
        assertThrows(UnauthorizedActionException.class, () ->
                listingService.updateListingStatus(listingId, intruder, false, ListingStatus.PUBLISHED, 0));
    }

    @Test
    @DisplayName("Precedence: expected-version (409) is checked before lifecycle validity (400)")
    void staleVersionBegetsInvalidTransitionIsConflict() {
        listing.setStatus(ListingStatus.RENTED); // RENTED -> PUBLISHED is invalid, but version is also stale
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));

        // Stale version wins: 409 ListingConflict, not 400 lifecycle.
        assertThrows(ListingConflictException.class, () ->
                listingService.publishListing(listingId, ownerId, false, 5));
        assertThrows(ListingConflictException.class, () ->
                listingService.updateListingStatus(listingId, ownerId, false, ListingStatus.PUBLISHED, 5));
        // Stale version on a frozen (RENTED) edit is likewise 409 before the 400 editability rejection.
        assertThrows(ListingConflictException.class, () ->
                listingService.updateListing(listingId, ownerId, false, updateRequest(5)));
    }

    // ---- Phase 3B.6E: ADMIN authorization across another owner's listings ----------------------

    @Test
    @DisplayName("ADMIN can create a draft listing on another owner's property; listing stays on that property")
    void adminCanCreateDraftOnOthersProperty() {
        UUID adminId = UUID.randomUUID();
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
                "Admin-created draft"
        );

        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(listingRepository.save(any(Listing.class))).thenAnswer(invocation -> {
            Listing l = invocation.getArgument(0);
            l.setId(listingId);
            return l;
        });

        ListingResponse response = listingService.createDraftListing(adminId, true, request);

        assertEquals(ListingStatus.DRAFT, response.getStatus());
        assertEquals(propertyId, response.getPropertyId(), "listing must remain on the ORIGINAL owner's property");
        assertSame(owner, property.getOwner(), "ADMIN action must not transfer property ownership");
    }

    @Test
    @DisplayName("ADMIN can update another owner's listing with the current expected version")
    void adminCanUpdateOthersListing() {
        UUID adminId = UUID.randomUUID();
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listingRepository.saveAndFlush(any(Listing.class))).thenAnswer(inv -> inv.getArgument(0));

        ListingResponse response = listingService.updateListing(listingId, adminId, true, updateRequest(0));

        assertEquals(new BigDecimal("26000.00"), response.getRent());
        verify(listingRepository).saveAndFlush(any(Listing.class));
    }

    @Test
    @DisplayName("ADMIN can publish another owner's listing; completeness, precheck and index handling all still apply")
    void adminCanPublishOthersListing() {
        UUID adminId = UUID.randomUUID();
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listingRepository.existsByPropertyIdAndStatusAndIdNot(propertyId, ListingStatus.PUBLISHED, listingId))
                .thenReturn(false);
        when(listingRepository.saveAndFlush(any(Listing.class))).thenAnswer(inv -> inv.getArgument(0));

        ListingResponse response = listingService.publishListing(listingId, adminId, true, 0);

        assertEquals(ListingStatus.PUBLISHED, response.getStatus());
    }

    @Test
    @DisplayName("ADMIN status change delegates to publish with the ADMIN flag forwarded (no partial-fix 403)")
    void adminStatusDelegationToPublishForwardsAdminFlag() {
        UUID adminId = UUID.randomUUID();
        listing.setStatus(ListingStatus.UNAVAILABLE); // UNAVAILABLE -> PUBLISHED funnels through publishListing
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listingRepository.existsByPropertyIdAndStatusAndIdNot(propertyId, ListingStatus.PUBLISHED, listingId))
                .thenReturn(false);
        when(listingRepository.saveAndFlush(any(Listing.class))).thenAnswer(inv -> inv.getArgument(0));

        ListingResponse response = listingService.updateListingStatus(
                listingId, adminId, true, ListingStatus.PUBLISHED, 0);

        assertEquals(ListingStatus.PUBLISHED, response.getStatus(),
                "delegated publication must authorize the ADMIN exactly once via the forwarded flag");
        verify(listingRepository).saveAndFlush(any(Listing.class));
    }

    @Test
    @DisplayName("ADMIN remains bound by expected-version: stale publish and stale status change are still 409")
    void adminStaleVersionStillRejected() {
        UUID adminId = UUID.randomUUID();
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing)); // version 0

        assertThrows(ListingConflictException.class, () ->
                listingService.publishListing(listingId, adminId, true, 9));
        assertThrows(ListingConflictException.class, () ->
                listingService.updateListingStatus(listingId, adminId, true, ListingStatus.PUBLISHED, 9));
        assertThrows(ListingConflictException.class, () ->
                listingService.updateListing(listingId, adminId, true, updateRequest(9)));

        assertEquals(0, listing.getVersion());
        verify(listingRepository, never()).saveAndFlush(any(Listing.class));
    }

    @Test
    @DisplayName("ADMIN cannot bypass lifecycle: RENTED/ARCHIVED edits and RENTED republish remain 400")
    void adminCannotBypassLifecycleRules() {
        UUID adminId = UUID.randomUUID();

        // Frozen edit targets
        for (ListingStatus frozen : List.of(ListingStatus.RENTED, ListingStatus.ARCHIVED)) {
            reset(listingRepository);
            listing.setStatus(frozen);
            when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));

            assertThrows(InvalidStateTransitionException.class,
                    () -> listingService.updateListing(listingId, adminId, true, updateRequest(0)),
                    "ADMIN must not be able to edit a " + frozen + " listing");
            verify(listingRepository, never()).saveAndFlush(any(Listing.class));
        }

        // RENTED -> PUBLISHED republish remains rejected for an ADMIN too
        reset(listingRepository);
        listing.setStatus(ListingStatus.RENTED);
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        assertThrows(InvalidStateTransitionException.class,
                () -> listingService.publishListing(listingId, adminId, true, 0));

        // ARCHIVED is terminal for an ADMIN as well
        reset(listingRepository);
        listing.setStatus(ListingStatus.ARCHIVED);
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        assertThrows(InvalidStateTransitionException.class,
                () -> listingService.updateListingStatus(
                        listingId, adminId, true, ListingStatus.PUBLISHED, 0));
    }

    @Test
    @DisplayName("Same-state idempotent no-op also applies to an ADMIN actor (no write, no increment)")
    void adminSameStateIsIdempotentNoOp() {
        UUID adminId = UUID.randomUUID();
        listing.setStatus(ListingStatus.RENTED);
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));

        ListingResponse response = listingService.updateListingStatus(
                listingId, adminId, true, ListingStatus.RENTED, 0);

        assertEquals(ListingStatus.RENTED, response.getStatus());
        assertEquals(0, listing.getVersion());
        verify(listingRepository, never()).save(any(Listing.class));
        verify(listingRepository, never()).saveAndFlush(any(Listing.class));
    }

    /** Runs a status transition that is expected to succeed, asserting the returned target status. */
    private void assertTransitionOk(ListingStatus target, ListingStatus expectedStatus) {
        reset(listingRepository);
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listingRepository.saveAndFlush(any(Listing.class))).thenAnswer(inv -> inv.getArgument(0));

        ListingResponse response = listingService.updateListingStatus(listingId, ownerId, false, target, 0);

        assertEquals(expectedStatus, response.getStatus());
    }

    /** Asserts a rejected status transition writes nothing and leaves the status unchanged. */
    private void assertTransitionRejected(ListingStatus target) {
        ListingStatus before = listing.getStatus();
        reset(listingRepository);
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));

        assertThrows(InvalidStateTransitionException.class, () ->
                listingService.updateListingStatus(listingId, ownerId, false, target, 0));

        assertEquals(before, listing.getStatus(), "rejected transition must not change status");
        verify(listingRepository, never()).save(any(Listing.class));
        verify(listingRepository, never()).saveAndFlush(any(Listing.class));
    }
}
