package com.veritas.listing;

import com.veritas.common.exception.*;
import com.veritas.listing.dto.CreateListingRequest;
import com.veritas.listing.dto.ListingResponse;
import com.veritas.listing.dto.UpdateListingRequest;
import com.veritas.property.Property;
import com.veritas.property.PropertyRepository;
import com.veritas.property.PropertyService;
import com.veritas.property.dto.PropertySummaryResponse;
import com.veritas.security.ResourceAccess;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ListingService {

    // PostgreSQL SQLSTATE for a unique violation, and the exact name of the authoritative published-
    // listing index (V4). A base DataIntegrityViolationException is reclassified as a listing conflict
    // only when BOTH match, so unrelated integrity failures are never turned into an HTTP 409.
    private static final String UNIQUE_VIOLATION_SQLSTATE = "23505";
    private static final String PUBLISHED_LISTING_UNIQUE_INDEX = "uq_active_published_listing_per_property";

    // Centralized listing lifecycle policy (Phase 3B.6D). ONE source of truth for permitted state
    // transitions so updateListing / publishListing / updateListingStatus -- and both the dedicated
    // publish endpoint and the status endpoint -- cannot diverge or bypass a rule. This table governs
    // only transitions between DIFFERENT states; same-state requests are valid idempotent no-ops handled
    // by the callers BEFORE any write (no DB write, no version increment). ARCHIVED is terminal (empty
    // allowed-set); RENTED may only advance to ARCHIVED, so RENTED -> PUBLISHED/UNAVAILABLE/DRAFT and any
    // ARCHIVED -> different-state are rejected here. Every non-ARCHIVED state may be archived.
    private static final Map<ListingStatus, Set<ListingStatus>> ALLOWED_TRANSITIONS;
    static {
        Map<ListingStatus, Set<ListingStatus>> transitions = new EnumMap<>(ListingStatus.class);
        transitions.put(ListingStatus.DRAFT,       EnumSet.of(ListingStatus.PUBLISHED, ListingStatus.ARCHIVED));
        transitions.put(ListingStatus.PUBLISHED,   EnumSet.of(ListingStatus.UNAVAILABLE, ListingStatus.RENTED, ListingStatus.ARCHIVED));
        transitions.put(ListingStatus.UNAVAILABLE, EnumSet.of(ListingStatus.PUBLISHED, ListingStatus.RENTED, ListingStatus.ARCHIVED));
        transitions.put(ListingStatus.RENTED,      EnumSet.of(ListingStatus.ARCHIVED));
        transitions.put(ListingStatus.ARCHIVED,    EnumSet.noneOf(ListingStatus.class));
        ALLOWED_TRANSITIONS = Collections.unmodifiableMap(transitions);
    }

    // States whose listings are frozen against business-field edits (Phase 3B.6D). RENTED and ARCHIVED
    // cannot be edited; DRAFT, PUBLISHED and UNAVAILABLE can.
    private static final Set<ListingStatus> NON_EDITABLE_STATUSES =
            EnumSet.of(ListingStatus.RENTED, ListingStatus.ARCHIVED);

    private final ListingRepository listingRepository;
    private final PropertyRepository propertyRepository;
    private final PropertyService propertyService;

    public ListingService(
            ListingRepository listingRepository,
            PropertyRepository propertyRepository,
            PropertyService propertyService
    ) {
        this.listingRepository = listingRepository;
        this.propertyRepository = propertyRepository;
        this.propertyService = propertyService;
    }

    @Transactional
    public ListingResponse createDraftListing(UUID actorId, boolean actorIsAdmin, CreateListingRequest request) {
        Property property = propertyRepository.findById(request.getPropertyId())
                .orElseThrow(() -> new ResourceNotFoundException("Property not found with id: " + request.getPropertyId()));

        // Phase 3B.6E: ADMIN may create a listing on any owner's property; the listing stays
        // associated with the original property and its original owner (no denormalized owner field).
        ResourceAccess.requireOwnershipOrAdmin(
                actorIsAdmin,
                property.getOwner().getId(),
                actorId,
                "You are not authorized to create a listing for this property");

        Listing listing = new Listing();
        listing.setProperty(property);
        listing.setRent(request.getRent());
        listing.setDeposit(request.getDeposit());
        listing.setBhk(request.getBhk());
        listing.setBathrooms(request.getBathrooms() != null ? request.getBathrooms() : 1);
        listing.setBuiltUpArea(request.getBuiltUpArea());
        listing.setFloor(request.getFloor());
        listing.setFurnishingStatus(request.getFurnishingStatus() != null ? request.getFurnishingStatus() : com.veritas.property.FurnishingStatus.UNFURNISHED);
        listing.setBachelorsAllowed(request.isBachelorsAllowed());
        listing.setParkingAvailable(request.isParkingAvailable());
        listing.setPetsAllowed(request.isPetsAllowed());
        listing.setAvailableFrom(request.getAvailableFrom());
        listing.setDescription(request.getDescription());
        listing.setStatus(ListingStatus.DRAFT);

        OffsetDateTime now = OffsetDateTime.now();
        listing.setCreatedAt(now);
        listing.setUpdatedAt(now);

        Listing saved = listingRepository.save(listing);
        // Owner-only action: return the authorized (privileged) view explicitly.
        return mapToResponse(saved, true);
    }

    @Transactional(readOnly = true)
    public ListingResponse getListingDetails(UUID listingId, UUID currentUserId, boolean isAdmin) {
        Listing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("Listing not found with id: " + listingId));

        boolean isOwner = currentUserId != null && listing.getProperty().getOwner().getId().equals(currentUserId);
        boolean isOwnerOrAdmin = isOwner || isAdmin;

        // READ visibility rule: every non-PUBLISHED listing (DRAFT, UNAVAILABLE, RENTED or
        // ARCHIVED) is invisible to non-owners/non-admins. A 404 -- not a 403 -- is returned so the
        // existence of a private listing is not disclosed, consistent with private-property reads.
        // WRITE authorization for this resource still returns 403 UnauthorizedActionException.
        if (listing.getStatus() != ListingStatus.PUBLISHED && !isOwnerOrAdmin) {
            throw new ResourceNotFoundException("Listing not found with id: " + listingId);
        }

        return mapToResponse(listing, isOwnerOrAdmin);
    }

    @Transactional
    public ListingResponse updateListing(UUID listingId, UUID actorId, boolean actorIsAdmin, UpdateListingRequest request) {
        Listing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("Listing not found with id: " + listingId));

        // Authorization FIRST (Phase 3B.6E: ADMIN may manage any owner's listing; ownership is
        // resolved through the property relationship, never a separate listing owner field).
        ResourceAccess.requireOwnershipOrAdmin(
                actorIsAdmin,
                listing.getProperty().getOwner().getId(),
                actorId,
                "You are not authorized to update this listing");

        // Expected-version gate (Phase 3B.6C) runs BEFORE lifecycle validation, per the approved
        // Phase 3B.6D precedence (authorization -> expected-version -> lifecycle -> mutation): a stale
        // client is a 409 even when the edit would otherwise be illegal, and a client holding the
        // current version of a frozen listing gets a 400.
        validateExpectedVersion(listing, request.getVersion());

        // Editability gate (Phase 3B.6D): RENTED and ARCHIVED listings are frozen against edits. Runs
        // after authorization + expected-version, so security and locking precedence are preserved.
        validateEditable(listing);

        listing.setRent(request.getRent());
        listing.setDeposit(request.getDeposit());
        listing.setBhk(request.getBhk());
        listing.setBathrooms(request.getBathrooms());
        listing.setBuiltUpArea(request.getBuiltUpArea());
        listing.setFloor(request.getFloor());
        if (request.getFurnishingStatus() != null) {
            listing.setFurnishingStatus(request.getFurnishingStatus());
        }
        listing.setBachelorsAllowed(request.isBachelorsAllowed());
        listing.setParkingAvailable(request.isParkingAvailable());
        listing.setPetsAllowed(request.isPetsAllowed());
        listing.setAvailableFrom(request.getAvailableFrom());
        listing.setDescription(request.getDescription());
        listing.setUpdatedAt(OffsetDateTime.now());

        // saveAndFlush (not save) forces the versioned UPDATE within this transaction so Hibernate
        // increments the @Version here and the mapped response exposes the NEW version to the client.
        Listing saved = listingRepository.saveAndFlush(listing);
        // Owner-only action: return the authorized (privileged) view explicitly.
        return mapToResponse(saved, true);
    }

    @Transactional
    public ListingResponse publishListing(UUID listingId, UUID actorId, boolean actorIsAdmin, Integer expectedVersion) {
        Listing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("Listing not found with id: " + listingId));

        ResourceAccess.requireOwnershipOrAdmin(
                actorIsAdmin,
                listing.getProperty().getOwner().getId(),
                actorId,
                "You are not authorized to publish this listing");

        // Expected-version gate (Phase 3B.6C): reject a stale client representation before any state or
        // field is changed. Placed after the authorization check to preserve 403 precedence; it also gates
        // the idempotent already-PUBLISHED return below, so a stale version on an already-published listing
        // is a 409 rather than a silent success.
        validateExpectedVersion(listing, expectedVersion);

        // Lifecycle gate (Phase 3B.6D) via the centralized policy, AFTER authorization + expected-version.
        // Only DRAFT and UNAVAILABLE may publish; RENTED -> PUBLISHED and ARCHIVED -> PUBLISHED are 400.
        ListingStatus currentStatus = listing.getStatus();
        validateTransition(currentStatus, ListingStatus.PUBLISHED);

        // Idempotent same-state no-op: publishing an already-PUBLISHED listing returns the CURRENT
        // version and writes nothing -- it does not re-run completeness or duplicate checks.
        if (currentStatus == ListingStatus.PUBLISHED) {
            return mapToResponse(listing, true);
        }

        // Validate completeness
        validateListingCompleteness(listing);

        // Prevent duplicate published listings for the same property
        boolean hasConflict = listingRepository.existsByPropertyIdAndStatusAndIdNot(
                listing.getProperty().getId(),
                ListingStatus.PUBLISHED,
                listing.getId()
        );

        if (hasConflict) {
            throw new ListingConflictException("Another active published listing already exists for this property");
        }

        listing.setStatus(ListingStatus.PUBLISHED);
        listing.setUpdatedAt(OffsetDateTime.now());

        // The partial unique index uq_active_published_listing_per_property (V4) is the authoritative
        // guard against two PUBLISHED listings for the same property. The existence check above is a
        // fast path and can still race under concurrent transactions, so saveAndFlush() forces the
        // UPDATE to hit the index inside this @Transactional boundary. Verified against real PostgreSQL
        // (Phase 3B.6B Testcontainers suite): the flush surfaces the published-listing unique violation
        // as the BASE DataIntegrityViolationException wrapping Hibernate ConstraintViolationException and
        // a PSQLException with SQLSTATE 23505 and the index name in its message -- NOT as
        // DuplicateKeyException. Both shapes are therefore handled: a DuplicateKeyException here is
        // unambiguously this conflict, and a base DataIntegrityViolationException is reclassified ONLY
        // when it is that specific unique violation. saveAndFlush() is required because save() on the
        // already-managed entity would defer the write to commit time, outside this catch. No SQL or
        // constraint internals are exposed to the client; the transaction rolls back either way.
        Listing saved;
        try {
            saved = listingRepository.saveAndFlush(listing);
        } catch (DuplicateKeyException ex) {
            throw new ListingConflictException(
                    "Another active published listing already exists for this property");
        } catch (DataIntegrityViolationException ex) {
            // Foreign key (23503), not-null (23502), check (23514), numeric overflow (22xxx) and any other
            // unique constraint are re-propagated unchanged so they are never misclassified as a 409.
            if (isPublishedListingUniqueViolation(ex)) {
                throw new ListingConflictException(
                        "Another active published listing already exists for this property");
            }
            throw ex;
        }
        // Owner-only action: return the authorized (privileged) view explicitly.
        return mapToResponse(saved, true);
    }

    @Transactional
    public ListingResponse updateListingStatus(UUID listingId, UUID actorId, boolean actorIsAdmin, ListingStatus targetStatus, Integer expectedVersion) {
        Listing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("Listing not found with id: " + listingId));

        ResourceAccess.requireOwnershipOrAdmin(
                actorIsAdmin,
                listing.getProperty().getOwner().getId(),
                actorId,
                "You are not authorized to change the status of this listing");

        // Expected-version gate (Phase 3B.6C): reject a stale client representation before any status
        // transition. Placed after authorization to preserve 403 precedence. For a PUBLISHED target the
        // same expected version is forwarded to publishListing so the check is not lost or re-evaluated
        // against a different entity state during delegation.
        validateExpectedVersion(listing, expectedVersion);

        ListingStatus currentStatus = listing.getStatus();

        // Lifecycle gate (Phase 3B.6D): the same centralized policy used by publish/edit governs the
        // status endpoint, so neither endpoint can bypass a rule. Runs AFTER authorization + version.
        validateTransition(currentStatus, targetStatus);

        // Idempotent same-state no-op: valid per the transition policy, returns the CURRENT version and
        // performs NO write and NO version increment.
        if (currentStatus == targetStatus) {
            return mapToResponse(listing, true);
        }

        // PUBLISHED targets are funnelled through publishListing so publication completeness, the
        // duplicate precheck and the Phase 3B.6B unique-index handling each apply exactly once. The
        // expected version AND the ADMIN flag are forwarded, so optimistic locking and authorization
        // are preserved and the version is incremented only by that single delegated flush (never twice).
        if (targetStatus == ListingStatus.PUBLISHED) {
            return publishListing(listingId, actorId, actorIsAdmin, expectedVersion);
        }

        listing.setStatus(targetStatus);
        listing.setUpdatedAt(OffsetDateTime.now());

        // saveAndFlush forces the versioned UPDATE now, so the returned response carries the incremented
        // version and any concurrent overlapping write is detected inside this transaction.
        Listing saved = listingRepository.saveAndFlush(listing);
        // Owner-only action: return the authorized (privileged) view explicitly.
        return mapToResponse(saved, true);
    }

    @Transactional(readOnly = true)
    public Page<ListingResponse> getOwnerListings(UUID ownerId, ListingStatus status, Pageable pageable) {
        Page<Listing> page;
        if (status != null) {
            page = listingRepository.findByOwnerIdAndStatus(ownerId, status, pageable);
        } else {
            page = listingRepository.findByOwnerId(ownerId, pageable);
        }
        // One grouped image-count query for every distinct property referenced on this page, so the
        // nested property summary no longer triggers a COUNT per listing (N+1). listing.property is
        // join-fetched by the repository query, so collecting the ids issues no additional loads.
        Map<UUID, Long> imageCounts = propertyService.loadImageCounts(
                page.getContent().stream()
                        .map(l -> l.getProperty().getId())
                        .collect(Collectors.toSet()));
        // Caller is the authenticated owner, so listings include full authorized property detail.
        return page.map(listing -> mapToResponse(listing, true, imageCounts));
    }

    private void validateListingCompleteness(Listing listing) {
        if (listing.getRent() == null || listing.getRent().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IncompleteListingException("Listing rent must be strictly greater than zero to publish");
        }
        if (listing.getDeposit() == null || listing.getDeposit().compareTo(BigDecimal.ZERO) < 0) {
            throw new IncompleteListingException("Listing deposit cannot be negative to publish");
        }
        if (listing.getBhk() == null || listing.getBhk() <= 0) {
            throw new IncompleteListingException("Listing must specify a positive BHK count to publish");
        }
        if (listing.getBathrooms() == null || listing.getBathrooms() <= 0) {
            throw new IncompleteListingException("Listing must specify a positive bathroom count to publish");
        }
        if (listing.getBuiltUpArea() == null || listing.getBuiltUpArea().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IncompleteListingException("Listing built-up area must be strictly greater than zero to publish");
        }
        if (listing.getFloor() == null) {
            throw new IncompleteListingException("Listing floor must be specified to publish");
        }
        if (listing.getAvailableFrom() == null) {
            throw new IncompleteListingException("Listing availability date must be specified to publish");
        }
        if (listing.getProperty() == null || listing.getProperty().getAddress() == null || listing.getProperty().getAddress().isBlank()) {
            throw new IncompleteListingException("Associated property must have a valid address to publish");
        }
    }

    /**
     * Narrowly identifies the concurrent-publication conflict: true only when the throwable wraps a
     * {@link SQLException} with PostgreSQL SQLSTATE 23505 whose message names the
     * {@code uq_active_published_listing_per_property} index. Every other integrity failure (foreign key,
     * not-null, check, numeric overflow, or a different unique constraint) returns false so it propagates
     * unchanged and is never misclassified as an HTTP 409. The SQL message itself is never exposed.
     */
    private static boolean isPublishedListingUniqueViolation(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof SQLException sql) {
                if (UNIQUE_VIOLATION_SQLSTATE.equals(sql.getSQLState())) {
                    String message = sql.getMessage();
                    return message != null && message.contains(PUBLISHED_LISTING_UNIQUE_INDEX);
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Rejects a mutation whose expected optimistic-lock version does not match the currently persisted
     * version of the loaded listing. This is the protection that {@code @Version} alone cannot provide:
     * it catches a sequential stale-client request whose transaction begins after an earlier update has
     * already committed (the fresh load would otherwise match and silently overwrite). Runs on the managed
     * entity BEFORE any field is changed, so a rejection leaves the row completely untouched. A mismatch
     * reuses the existing {@link ListingConflictException} -> HTTP 409 envelope; the transaction is
     * unchecked-exception rolled back and nothing is written.
     */
    private void validateExpectedVersion(Listing listing, Integer expectedVersion) {
        if (!Objects.equals(listing.getVersion(), expectedVersion)) {
            throw new ListingConflictException(
                    "Listing has been modified. Please refresh and try again.");
        }
    }

    /**
     * Centralized lifecycle transition policy (Phase 3B.6D) and the SINGLE source of truth shared by
     * every state-changing path. A same-state request is a valid idempotent no-op and returns without
     * throwing -- the caller then performs the no-op before any write. A cross-state request is permitted
     * only when the target is in the current state's allowed set; otherwise the existing
     * {@link InvalidStateTransitionException} (HTTP 400) is raised with a state-only message (no PII or
     * ownership detail). Callers invoke this AFTER authorization and expected-version validation, so the
     * approved precedence (authorization -> expected-version -> lifecycle -> mutation) is preserved.
     */
    private void validateTransition(ListingStatus current, ListingStatus target) {
        if (current == target) {
            return; // idempotent same-state no-op
        }
        if (!ALLOWED_TRANSITIONS.get(current).contains(target)) {
            throw new InvalidStateTransitionException(
                    "Cannot transition a listing from " + current + " to " + target);
        }
    }

    /**
     * Centralized edit policy (Phase 3B.6D): RENTED and ARCHIVED listings are frozen against business-field
     * edits. Invoked AFTER authorization and expected-version validation so the approved precedence holds;
     * a rejected edit therefore leaves every field and the version untouched.
     */
    private void validateEditable(Listing listing) {
        if (NON_EDITABLE_STATUSES.contains(listing.getStatus())) {
            throw new InvalidStateTransitionException(
                    "This listing cannot be edited in its current state (" + listing.getStatus() + ").");
        }
    }

    public ListingResponse mapToResponse(Listing listing, boolean isOwnerOrAdmin) {
        // Single-item path (detail/create/update/publish): the property summary resolves its own
        // image COUNT. Paged owner-listing reads use the batched overload instead to avoid N+1.
        PropertySummaryResponse propertySummary = propertyService.mapToSummary(listing.getProperty(), isOwnerOrAdmin);
        return toResponse(listing, propertySummary);
    }

    public ListingResponse mapToResponse(Listing listing, boolean isOwnerOrAdmin, Map<UUID, Long> imageCounts) {
        // Batched path (paged owner listings): reuse the page-wide image-count map. A property absent
        // from the grouped result (no images) resolves to a count of 0.
        Property property = listing.getProperty();
        long imageCount = imageCounts.getOrDefault(property.getId(), 0L);
        PropertySummaryResponse propertySummary = propertyService.mapToSummary(property, isOwnerOrAdmin, imageCount);
        return toResponse(listing, propertySummary);
    }

    private ListingResponse toResponse(Listing listing, PropertySummaryResponse propertySummary) {
        return new ListingResponse(
                listing.getId(),
                listing.getProperty().getId(),
                propertySummary,
                listing.getRent(),
                listing.getDeposit(),
                listing.getBhk(),
                listing.getBathrooms(),
                listing.getBuiltUpArea(),
                listing.getFloor(),
                listing.getFurnishingStatus(),
                listing.isBachelorsAllowed(),
                listing.isParkingAvailable(),
                listing.isPetsAllowed(),
                listing.getAvailableFrom(),
                listing.getDescription(),
                listing.getStatus(),
                listing.getCreatedAt(),
                listing.getUpdatedAt(),
                listing.getVersion()
        );
    }
}
