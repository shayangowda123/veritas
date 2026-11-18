package com.veritas.security;

import com.veritas.common.exception.UnauthorizedActionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit coverage for the shared Phase 3B.6E ownership/ADMIN authorization rule.
 * Pure logic: no Spring context, no mocks, no database.
 */
class ResourceAccessTest {

    private static final String DENIAL = "You are not authorized to perform this action";

    private final UUID ownerId = UUID.randomUUID();
    private final UUID strangerId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    @Test
    @DisplayName("Owner is granted access to their own resource")
    void ownerOfResourceIsGranted() {
        assertDoesNotThrow(() ->
                ResourceAccess.requireOwnershipOrAdmin(false, ownerId, ownerId, DENIAL));
    }

    @Test
    @DisplayName("ADMIN bypasses only the ownership comparison, for any resource")
    void adminIsGrantedForAnyResource() {
        assertDoesNotThrow(() ->
                ResourceAccess.requireOwnershipOrAdmin(true, ownerId, adminId, DENIAL));
        // Even an ADMIN whose own id happens to be null must not NPE: the flag short-circuits first.
        assertDoesNotThrow(() ->
                ResourceAccess.requireOwnershipOrAdmin(true, ownerId, null, DENIAL));
    }

    @Test
    @DisplayName("Non-owner, non-admin actor is denied")
    void strangerIsDenied() {
        assertThrows(UnauthorizedActionException.class, () ->
                ResourceAccess.requireOwnershipOrAdmin(false, ownerId, strangerId, DENIAL));
    }

    @Test
    @DisplayName("Case-insensitive exact-match only: partial id equality is irrelevant for UUIDs but nulls fail safe")
    void nullActorDeniedEvenWithAdminFlagFalse() {
        assertThrows(UnauthorizedActionException.class, () ->
                ResourceAccess.requireOwnershipOrAdmin(false, ownerId, null, DENIAL));
    }

    @Test
    @DisplayName("Null resource owner fails safe: a non-admin is never granted access")
    void nullResourceOwnerDeniedForNonAdmin() {
        assertThrows(UnauthorizedActionException.class, () ->
                ResourceAccess.requireOwnershipOrAdmin(false, null, ownerId, DENIAL));
    }

    @Test
    @DisplayName("Both sides null must not accidentally grant access")
    void bothNullIsDenied() {
        assertThrows(UnauthorizedActionException.class, () ->
                ResourceAccess.requireOwnershipOrAdmin(false, null, null, DENIAL));
    }

    @Test
    @DisplayName("The caller's denial message is propagated unchanged to the exception")
    void denialMessageIsPreserved() {
        UnauthorizedActionException ex = assertThrows(UnauthorizedActionException.class, () ->
                ResourceAccess.requireOwnershipOrAdmin(false, ownerId, strangerId, DENIAL));
        org.junit.jupiter.api.Assertions.assertEquals(DENIAL, ex.getMessage(),
                "helper must not rewrite per-endpoint denial messages (403 envelope contract)");
    }
}
