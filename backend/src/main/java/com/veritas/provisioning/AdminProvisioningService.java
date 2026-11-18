package com.veritas.provisioning;

import com.veritas.user.User;
import com.veritas.user.UserRepository;
import com.veritas.user.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;

/**
 * Encapsulates ADMIN account provisioning business logic.
 *
 * <p>This service is activated only by {@link AdminProvisioningRunner} inside
 * the isolated CLI context. It is never invoked during normal web application
 * startup because the runner is gated by {@code @ConditionalOnProperty}.</p>
 */
@Service
public class AdminProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(AdminProvisioningService.class);

    /**
     * Result enum representing the outcome of a provisioning attempt.
     */
    public enum ProvisioningResult {
        CREATED,
        ALREADY_EXISTS,
        CONFLICT_NON_ADMIN,
        INVALID_EMAIL,
        INVALID_PASSWORD,
        DUPLICATE_RACE
    }

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminProvisioningService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Provisions an ADMIN account.
     *
     * @param rawEmail    the email supplied by the operator (will be normalized)
     * @param rawPassword the plaintext password (hashed immediately, never stored/logged)
     * @return outcome indicating what happened
     */
    @Transactional
    public ProvisioningResult provision(String rawEmail, String rawPassword) {
        // --- Validate email ---
        if (rawEmail == null || rawEmail.isBlank()) {
            log.error("Provisioning failed: email is missing or blank");
            return ProvisioningResult.INVALID_EMAIL;
        }
        String email = normalizeEmail(rawEmail);
        if (!isValidEmail(email)) {
            log.error("Provisioning failed: email format is invalid");
            return ProvisioningResult.INVALID_EMAIL;
        }

        // --- Validate password ---
        if (rawPassword == null || rawPassword.isBlank()) {
            log.error("Provisioning failed: password is missing or blank");
            return ProvisioningResult.INVALID_PASSWORD;
        }
        if (rawPassword.length() < 8 || rawPassword.length() > 72) {
            log.error("Provisioning failed: password does not meet length policy (8-72)");
            return ProvisioningResult.INVALID_PASSWORD;
        }

        // --- Check existing account ---
        Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isPresent()) {
            User user = existing.get();
            if (user.getRole() == UserRole.ADMIN) {
                log.info("ADMIN account already exists — no changes made");
                return ProvisioningResult.ALREADY_EXISTS;
            } else {
                log.error("Provisioning refused: email is registered to a {} account. " +
                        "Role promotion requires a separate reviewed process.", user.getRole());
                return ProvisioningResult.CONFLICT_NON_ADMIN;
            }
        }

        // --- Create new ADMIN ---
        User admin = new User();
        admin.setName("Administrator");
        admin.setEmail(email);
        admin.setPasswordHash(passwordEncoder.encode(rawPassword));
        admin.setRole(UserRole.ADMIN);
        admin.setVerified(true);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        admin.setCreatedAt(now);
        admin.setUpdatedAt(now);

        try {
            userRepository.saveAndFlush(admin);
            log.info("ADMIN account created successfully");
            return ProvisioningResult.CREATED;
        } catch (DataIntegrityViolationException ex) {
            // Unique-email constraint race: another process inserted between our check and save.
            if (isDuplicateEmailViolation(ex)) {
                log.warn("Concurrent creation detected — re-checking existing account");
                Optional<User> raced = userRepository.findByEmail(email);
                if (raced.isPresent() && raced.get().getRole() == UserRole.ADMIN) {
                    log.info("ADMIN account already exists (concurrent) — no changes made");
                    return ProvisioningResult.ALREADY_EXISTS;
                }
                log.error("Concurrent race resolved but existing account is not ADMIN — aborting");
                return ProvisioningResult.DUPLICATE_RACE;
            }
            throw ex; // Re-throw unrelated integrity violations
        }
    }

    /**
     * Normalizes email identically to {@code UserService.register}.
     */
    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Basic email format validation — consistent with existing registration pattern.
     */
    private static boolean isValidEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) return false;                         // local part must be non-empty
        if (at != email.lastIndexOf('@')) return false;    // exactly one '@'
        if (at >= email.length() - 1) return false;        // domain part must be non-empty
        int dot = email.indexOf('.', at);                  // domain must contain a dot
        return dot > at && dot < email.length() - 1;
    }

    private static boolean isDuplicateEmailViolation(DataIntegrityViolationException ex) {
        Throwable root = ex.getMostSpecificCause();
        // SQLSTATE 23505 = unique_violation in PostgreSQL
        String message = root.getMessage();
        return message != null && (message.contains("23505")
                || message.contains("users_email_key")
                || message.contains("uq_users_email"));
    }
}
