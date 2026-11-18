package com.veritas.provisioning;

import com.veritas.user.User;
import com.veritas.user.UserRepository;
import com.veritas.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused unit tests for {@link AdminProvisioningService}.
 * All persistence and encoding dependencies are mocked.
 */
@ExtendWith(MockitoExtension.class)
class AdminProvisioningServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private AdminProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new AdminProvisioningService(userRepository, passwordEncoder);
    }

    // ─── 1. Email validation ────────────────────────────────────────────────

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "  "})
    @DisplayName("Missing or blank email returns INVALID_EMAIL")
    void blankEmail_invalid(String email) {
        var result = service.provision(email, "ValidPass1!");
        assertThat(result).isEqualTo(AdminProvisioningService.ProvisioningResult.INVALID_EMAIL);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-at-sign", "@no-local", "user@", "user@domain", "user@@domain.com", "@.com"})
    @DisplayName("Invalid email format returns INVALID_EMAIL")
    void invalidEmailFormat(String email) {
        var result = service.provision(email, "ValidPass1!");
        assertThat(result).isEqualTo(AdminProvisioningService.ProvisioningResult.INVALID_EMAIL);
        verify(userRepository, never()).saveAndFlush(any());
    }

    // ─── 2. Password validation ─────────────────────────────────────────────

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "  "})
    @DisplayName("Missing or blank password returns INVALID_PASSWORD")
    void blankPassword_invalid(String password) {
        var result = service.provision("admin@example.com", password);
        assertThat(result).isEqualTo(AdminProvisioningService.ProvisioningResult.INVALID_PASSWORD);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "1234567"})
    @DisplayName("Password shorter than 8 characters returns INVALID_PASSWORD")
    void passwordTooShort(String password) {
        var result = service.provision("admin@example.com", password);
        assertThat(result).isEqualTo(AdminProvisioningService.ProvisioningResult.INVALID_PASSWORD);
    }

    @Test
    @DisplayName("Password longer than 72 characters returns INVALID_PASSWORD")
    void passwordTooLong() {
        String longPw = "A".repeat(73);
        var result = service.provision("admin@example.com", longPw);
        assertThat(result).isEqualTo(AdminProvisioningService.ProvisioningResult.INVALID_PASSWORD);
    }

    // ─── 3. New ADMIN creation ──────────────────────────────────────────────

    @Test
    @DisplayName("New ADMIN is saved with correct role, hash, and verified status")
    void createsNewAdmin() {
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("SecureP@ss1")).thenReturn("$2a$10$hashedvalue");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.provision("Admin@Example.com", "SecureP@ss1");

        assertThat(result).isEqualTo(AdminProvisioningService.ProvisioningResult.CREATED);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        User saved = captor.getValue();

        assertThat(saved.getEmail()).isEqualTo("admin@example.com"); // normalized
        assertThat(saved.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(saved.getPasswordHash()).isEqualTo("$2a$10$hashedvalue");
        assertThat(saved.isVerified()).isTrue();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getName()).isEqualTo("Administrator");
    }

    @Test
    @DisplayName("Password encoder is invoked with raw password")
    void passwordEncodedUsingConfiguredEncoder() {
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("MyPass123!")).thenReturn("$2a$10$encoded");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.provision("admin@example.com", "MyPass123!");

        verify(passwordEncoder).encode("MyPass123!");
    }

    // ─── 4. Idempotent: existing ADMIN ──────────────────────────────────────

    @Test
    @DisplayName("Existing ADMIN account results in ALREADY_EXISTS (idempotent)")
    void existingAdmin_idempotent() {
        User existingAdmin = new User();
        existingAdmin.setEmail("admin@example.com");
        existingAdmin.setRole(UserRole.ADMIN);
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(existingAdmin));

        var result = service.provision("admin@example.com", "SecureP@ss1");

        assertThat(result).isEqualTo(AdminProvisioningService.ProvisioningResult.ALREADY_EXISTS);
        verify(userRepository, never()).saveAndFlush(any());
        verify(passwordEncoder, never()).encode(any());
    }

    // ─── 5. Conflict: existing OWNER / RENTER ───────────────────────────────

    @Test
    @DisplayName("Existing OWNER account is not promoted")
    void existingOwner_notPromoted() {
        User owner = new User();
        owner.setEmail("owner@example.com");
        owner.setRole(UserRole.OWNER);
        owner.setPasswordHash("$2a$10$originalhash");
        owner.setName("Original Owner");
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(owner));

        var result = service.provision("owner@example.com", "SecureP@ss1");

        assertThat(result).isEqualTo(AdminProvisioningService.ProvisioningResult.CONFLICT_NON_ADMIN);
        verify(userRepository, never()).saveAndFlush(any());
        // Existing user is not modified
        assertThat(owner.getRole()).isEqualTo(UserRole.OWNER);
        assertThat(owner.getPasswordHash()).isEqualTo("$2a$10$originalhash");
        assertThat(owner.getName()).isEqualTo("Original Owner");
    }

    @Test
    @DisplayName("Existing RENTER account is not promoted")
    void existingRenter_notPromoted() {
        User renter = new User();
        renter.setEmail("renter@example.com");
        renter.setRole(UserRole.RENTER);
        renter.setPasswordHash("$2a$10$renterhash");
        when(userRepository.findByEmail("renter@example.com")).thenReturn(Optional.of(renter));

        var result = service.provision("renter@example.com", "SecureP@ss1");

        assertThat(result).isEqualTo(AdminProvisioningService.ProvisioningResult.CONFLICT_NON_ADMIN);
        verify(userRepository, never()).saveAndFlush(any());
        assertThat(renter.getRole()).isEqualTo(UserRole.RENTER);
        assertThat(renter.getPasswordHash()).isEqualTo("$2a$10$renterhash");
    }

    // ─── 6. Concurrent duplicate handling ───────────────────────────────────

    @Test
    @DisplayName("DataIntegrityViolation on unique email is handled as DUPLICATE_RACE or ALREADY_EXISTS")
    void concurrentDuplicate_handled() {
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("SecureP@ss1")).thenReturn("$2a$10$h");
        DataIntegrityViolationException dup = new DataIntegrityViolationException(
                "ERROR: duplicate key value violates unique constraint \"users_email_key\" " +
                        "Detail: Key (email)=(admin@example.com) already exists. [SQLSTATE 23505]");
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(dup);

        User racedAdmin = new User();
        racedAdmin.setEmail("admin@example.com");
        racedAdmin.setRole(UserRole.ADMIN);
        when(userRepository.findByEmail("admin@example.com"))
                .thenReturn(Optional.empty())   // first call
                .thenReturn(Optional.of(racedAdmin)); // second call in catch block

        var result = service.provision("admin@example.com", "SecureP@ss1");

        assertThat(result).isEqualTo(AdminProvisioningService.ProvisioningResult.ALREADY_EXISTS);
    }

    @Test
    @DisplayName("Unrelated DataIntegrityViolation is re-thrown")
    void unrelatedViolation_rethrown() {
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("SecureP@ss1")).thenReturn("$2a$10$h");
        DataIntegrityViolationException unrelated = new DataIntegrityViolationException(
                "ERROR: null value in column \"name\" violates not-null constraint [SQLSTATE 23502]");
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(unrelated);

        org.junit.jupiter.api.Assertions.assertThrows(DataIntegrityViolationException.class,
                () -> service.provision("admin@example.com", "SecureP@ss1"));
    }

    // ─── 7. Email normalization ─────────────────────────────────────────────

    @Test
    @DisplayName("Email is normalized (trimmed + lowercase) before lookup and storage")
    void emailNormalizedConsistently() {
        when(userRepository.findByEmail("admin@corp.example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("ValidPass1!")).thenReturn("$2a$10$abc");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.provision("  Admin@Corp.Example.COM  ", "ValidPass1!");

        verify(userRepository).findByEmail("admin@corp.example.com");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("admin@corp.example.com");
    }

    // ─── 8. Provisioning runner: disabled by default ────────────────────────

    @Test
    @DisplayName("AdminProvisioningRunner has @ConditionalOnProperty with no matchIfMissing")
    void runnerConditionallyDisabled() {
        var annotation = AdminProvisioningRunner.class
                .getAnnotation(org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).containsExactly("app.admin-provisioning.enabled");
        assertThat(annotation.havingValue()).isEqualTo("true");
        assertThat(annotation.matchIfMissing()).isFalse();
    }
}
