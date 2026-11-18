package com.veritas.security;

import com.veritas.common.exception.UnauthorizedActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Single source of truth for the resource-management ownership rule (Phase 3B.6E).
 *
 * <p>Approved policy: an OWNER may manage only resources whose owner ID equals the authenticated
 * actor's ID; an ADMIN may manage any owner's resource. The ADMIN flag must be derived by the
 * controller from the database-loaded {@link UserPrincipal} role and is therefore never
 * client-supplied. A renter (or any other role) never reaches these checks because controller
 * method security ({@code @PreAuthorize}) blocks such writes earlier with HTTP 403.</p>
 *
 * <p>Design guarantees:</p>
 * <ul>
 *     <li>ADMIN bypasses ONLY the ownership comparison. Expected-version (3B.6C), lifecycle
 *         (3B.6D), completeness and database-constraint checks are the callers' responsibility and
 *         must remain in place around this call.</li>
 *     <li>Fail-safe nulls: a null actor ID or a null resource-owner ID never grants access to a
 *         non-admin. An owner resource without a resolvable owner is a data anomaly, so the
 *         concrete reason is logged (never returned to the client) while the caller's 403
 *         exception shape is preserved.</li>
 *     <li>No additional database queries: ownership is evaluated on IDs the caller has already
 *         loaded through the existing entity relationships.</li>
 * </ul>
 */
public final class ResourceAccess {

    private static final Logger log = LoggerFactory.getLogger(ResourceAccess.class);

    private ResourceAccess() {
    }

    /**
     * Authorizes a management mutation of an owner-scoped resource.
     *
     * @param isAdmin         server-derived ADMIN flag from the authenticated principal
     * @param resourceOwnerId owner ID resolved from the loaded resource (via its ownership chain)
     * @param actorId         ID of the authenticated acting user (may be null for defense in depth)
     * @param denialMessage   caller-specific message for the 403 envelope; contains no PII or
     *                        resource-existence detail beyond what the endpoint already discloses
     * @throws UnauthorizedActionException (mapped to HTTP 403) when a non-admin is not the owner
     */
    public static void requireOwnershipOrAdmin(
            boolean isAdmin,
            UUID resourceOwnerId,
            UUID actorId,
            String denialMessage
    ) {
        if (isAdmin) {
            return;
        }

        if (resourceOwnerId == null || actorId == null || !resourceOwnerId.equals(actorId)) {
            if (resourceOwnerId == null) {
                // Log the concrete cause for operators without disclosing it to the client.
                log.warn("Authorization denied because the resource has no resolvable owner. actorId={}", actorId);
            }
            throw new UnauthorizedActionException(denialMessage);
        }
    }
}
