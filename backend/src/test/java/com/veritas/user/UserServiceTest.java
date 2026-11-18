package com.veritas.user;

import com.veritas.common.exception.EmailAlreadyExistsException;
import com.veritas.common.exception.InvalidUserRoleException;
import com.veritas.common.exception.PasswordMismatchException;
import com.veritas.otp.OtpService;
import com.veritas.security.JwtService;
import com.veritas.security.UserPrincipal;
import com.veritas.user.dto.RegisterUserRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit coverage for public-registration role policy (Phase 3B.6E): registration may only create
 * OWNER or RENTER accounts; ADMIN must be rejected before anything is persisted, while all other
 * registration behavior (email uniqueness, password confirmation, hashing, OTP generation) is
 * preserved exactly.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private OtpService otpService;

    @InjectMocks
    private UserService userService;

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private RegisterUserRequest request(String role) {
        RegisterUserRequest r = new RegisterUserRequest();
        r.setName("Test User");
        r.setEmail("test@example.com");
        r.setPassword("Password123!");
        r.setConfirmPassword("Password123!");
        r.setPhone("+919876543210");
        r.setRole(role);
        return r;
    }

    private void stubHappyPath() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });
    }

    @Test
    @DisplayName("OWNER registration succeeds and is persisted with hashed password and OTP generation")
    void ownerRegistrationSucceeds() {
        stubHappyPath();

        User saved = userService.register(request("OWNER"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User captured = captor.getValue();
        assertEquals(UserRole.OWNER, captured.getRole());
        assertEquals("hashed", captured.getPasswordHash(), "password must never be stored raw");
        assertFalse(captured.isVerified(), "new registrations start unverified (phone OTP flow)");
        verify(passwordEncoder).encode("Password123!");
        verify(otpService).generatePhoneVerificationOtp(saved);
    }

    @Test
    @DisplayName("RENTER registration succeeds")
    void renterRegistrationSucceeds() {
        stubHappyPath();

        userService.register(request("RENTER"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals(UserRole.RENTER, captor.getValue().getRole());
    }

    @Test
    @DisplayName("ADMIN self-registration is rejected before anything is persisted")
    void adminSelfRegistrationIsRejected() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);

        assertThrows(InvalidUserRoleException.class, () -> userService.register(request("ADMIN")));

        verify(userRepository, never()).save(any(User.class));
        verify(passwordEncoder, never()).encode(anyString());
        verifyNoInteractions(otpService);
    }

    @Test
    @DisplayName("ADMIN rejection is case/whitespace-insensitive (\"  admin \" is still ADMIN)")
    void paddedLowercaseAdminIsAlsoRejected() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);

        assertThrows(InvalidUserRoleException.class, () -> userService.register(request("  admin ")));

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Unknown role values keep the existing InvalidUserRoleException behavior")
    void unknownRoleIsRejected() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);

        assertThrows(InvalidUserRoleException.class, () -> userService.register(request("SUPERUSER")));

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Null/missing role is rejected by the DTO @NotBlank constraint (validation policy unchanged)")
    void nullRoleFailsDtoValidation() {
        RegisterUserRequest r = request(null);
        Set<ConstraintViolation<RegisterUserRequest>> violations = VALIDATOR.validate(r);

        assertEquals(1, violations.size(), "exactly the role constraint must fire");
        assertEquals("role", violations.iterator().next().getPropertyPath().toString());
    }

    @Test
    @DisplayName("Email conflict is still rejected before role handling or persistence")
    void emailConflictBehaviorIntact() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);

        assertThrows(EmailAlreadyExistsException.class, () -> userService.register(request("OWNER")));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Password confirmation mismatch is still rejected")
    void passwordMismatchBehaviorIntact() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        RegisterUserRequest r = request("OWNER");
        r.setConfirmPassword("Different123!");

        assertThrows(PasswordMismatchException.class, () -> userService.register(r));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("An ADMIN provisioned outside registration can still authenticate (JWT issues and validates)")
    void existingAdminAccountStillAuthenticates() {
        // No public path creates this user: it models trusted out-of-band provisioning.
        User admin = new User();
        admin.setId(UUID.randomUUID());
        admin.setName("Provisioned Admin");
        admin.setEmail("admin@example.com");
        admin.setPasswordHash("hashed");
        admin.setRole(UserRole.ADMIN);
        admin.setVerified(true);

        JwtService jwtService = new JwtService(
                "dGVzdC1zZWNyZXQta2V5LWZvci12ZXJpdGFzLXJlbnRhbC1hcHAtMjU2LWJpdC1taW5pbXVtIQ==", 3600000L);
        UserPrincipal principal = new UserPrincipal(admin);

        String token = jwtService.generateToken(principal);

        assertTrue(jwtService.isTokenValid(token, principal));
        assertEquals("admin@example.com", jwtService.extractUsername(token));
        assertEquals("ROLE_ADMIN", principal.getAuthorities().iterator().next().getAuthority());
    }
}
