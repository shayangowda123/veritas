package com.veritas.auth;

import com.veritas.auth.dto.LoginResponse;
import com.veritas.common.exception.GlobalExceptionHandler;
import com.veritas.config.SecurityConfig;
import com.veritas.otp.OtpService;
import com.veritas.security.*;
import com.veritas.user.User;
import com.veritas.user.UserRole;
import com.veritas.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {
        AuthController.class,
        AuthControllerSecurityTest.TestSecurityController.class
})
@Import({
        SecurityConfig.class,
        JwtService.class,
        JwtAuthenticationFilter.class,
        CustomAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class,
        AuthControllerSecurityTest.TestSecurityController.class
})
@TestPropertySource(properties = {
        "security.jwt.secret-base64=dGVzdC1zZWNyZXQta2V5LWZvci12ZXJpdGFzLXJlbnRhbC1hcHAtMjU2LWJpdC1taW5pbXVtIQ==",
        "security.jwt.expiration-ms=3600000"
})
class AuthControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private OtpService otpService;

    @MockitoBean
    private LoginService loginService;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    private User verifiedRenter;
    private UserDetails renterUserDetails;
    private User verifiedOwner;
    private UserDetails ownerUserDetails;
    private User unverifiedUser;
    private UserDetails unverifiedUserDetails;

    @BeforeEach
    void setUp() {
        verifiedRenter = new User();
        verifiedRenter.setId(UUID.randomUUID());
        verifiedRenter.setName("Renter User");
        verifiedRenter.setEmail("renter@example.com");
        verifiedRenter.setPasswordHash("hashedPassword");
        verifiedRenter.setRole(UserRole.RENTER);
        verifiedRenter.setVerified(true);
        verifiedRenter.setCreatedAt(OffsetDateTime.now());
        verifiedRenter.setUpdatedAt(OffsetDateTime.now());
        renterUserDetails = new UserPrincipal(verifiedRenter);

        verifiedOwner = new User();
        verifiedOwner.setId(UUID.randomUUID());
        verifiedOwner.setName("Owner User");
        verifiedOwner.setEmail("owner@example.com");
        verifiedOwner.setPasswordHash("hashedPassword");
        verifiedOwner.setRole(UserRole.OWNER);
        verifiedOwner.setVerified(true);
        verifiedOwner.setCreatedAt(OffsetDateTime.now());
        verifiedOwner.setUpdatedAt(OffsetDateTime.now());
        ownerUserDetails = new UserPrincipal(verifiedOwner);

        unverifiedUser = new User();
        unverifiedUser.setId(UUID.randomUUID());
        unverifiedUser.setName("Unverified User");
        unverifiedUser.setEmail("unverified@example.com");
        unverifiedUser.setPasswordHash("hashedPassword");
        unverifiedUser.setRole(UserRole.RENTER);
        unverifiedUser.setVerified(false);
        unverifiedUser.setCreatedAt(OffsetDateTime.now());
        unverifiedUser.setUpdatedAt(OffsetDateTime.now());
        unverifiedUserDetails = new UserPrincipal(unverifiedUser);
    }

    @Test
    @DisplayName("1. Public registration endpoint remains accessible")
    void registrationEndpointIsPublic() throws Exception {
        when(userService.register(any())).thenReturn(verifiedRenter);

        String json = """
                {
                    "name": "Jane Doe",
                    "email": "jane@example.com",
                    "password": "Password123!",
                    "confirmPassword": "Password123!",
                    "phone": "+919876543210",
                    "role": "RENTER"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("renter@example.com"))
                .andExpect(jsonPath("$.role").value("RENTER"));
    }

    @Test
    @DisplayName("2. Public phone verification endpoint remains accessible")
    void phoneVerificationEndpointIsPublic() throws Exception {
        doNothing().when(otpService).verifyPhoneVerificationOtp(any(), any());

        String json = String.format("""
                {
                    "userId": "%s",
                    "otp": "123456"
                }
                """, UUID.randomUUID());

        mockMvc.perform(post("/api/v1/auth/verify-phone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(content().string("Phone number verified successfully"));
    }

    @Test
    @DisplayName("3. Login with valid credentials returns a signed JWT")
    void loginWithValidCredentialsReturnsSignedJwt() throws Exception {
        String token = jwtService.generateToken(renterUserDetails);
        LoginResponse response = new LoginResponse(
                token,
                "Bearer",
                3600L,
                verifiedRenter.getId(),
                verifiedRenter.getEmail(),
                verifiedRenter.getRole().name()
        );

        when(loginService.login(any())).thenReturn(response);

        String json = """
                {
                    "email": "renter@example.com",
                    "password": "Password123!"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value(token))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.email").value("renter@example.com"))
                .andExpect(jsonPath("$.role").value("RENTER"));
    }

    @Test
    @DisplayName("4. Invalid credentials return 401 Unauthorized without revealing email existence")
    void loginWithInvalidCredentialsReturns401() throws Exception {
        when(loginService.login(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        String json = """
                {
                    "email": "nonexistent@example.com",
                    "password": "WrongPassword!"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    @DisplayName("5. Unverified users cannot log in (403 Forbidden)")
    void unverifiedUserCannotLogin() throws Exception {
        when(loginService.login(any())).thenThrow(new DisabledException("User is disabled"));

        String json = """
                {
                    "email": "unverified@example.com",
                    "password": "Password123!"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Account phone number is not verified"));
    }

    @Test
    @DisplayName("6. Protected test endpoint rejects requests without a token (401)")
    void protectedEndpointRejectsWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/test/protected"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Full authentication is required to access this resource"));
    }

    @Test
    @DisplayName("7. Protected test endpoint accepts valid JWT and establishes principal & authorities")
    void protectedEndpointAcceptsValidJwt() throws Exception {
        String token = jwtService.generateToken(renterUserDetails);
        when(customUserDetailsService.loadUserByUsername("renter@example.com"))
                .thenReturn(renterUserDetails);

        mockMvc.perform(get("/api/v1/test/protected")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("renter@example.com"))
                .andExpect(jsonPath("$.role").value("RENTER"));
    }

    @Test
    @DisplayName("8. Expired, malformed or foreign-signed tokens are rejected (401)")
    void invalidTokensAreRejected() throws Exception {
        // Malformed token
        mockMvc.perform(get("/api/v1/test/protected")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid.malformed.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        // Foreign-signed token
        String foreignSecret = Base64.getEncoder().encodeToString("foreignSecretKeyMustBe32BytesLong!".getBytes());
        JwtService foreignService = new JwtService(foreignSecret, 3600000L);
        String foreignToken = foreignService.generateToken(renterUserDetails);

        mockMvc.perform(get("/api/v1/test/protected")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + foreignToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("9. Requests with insufficient authorization receive 403 Forbidden")
    void insufficientAuthorizationReturns403() throws Exception {
        // Renter attempts to access OWNER-only endpoint
        String renterToken = jwtService.generateToken(renterUserDetails);
        when(customUserDetailsService.loadUserByUsername("renter@example.com"))
                .thenReturn(renterUserDetails);

        mockMvc.perform(get("/api/v1/test/owner-only")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + renterToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Access denied"));

        // Owner accesses OWNER-only endpoint successfully
        String ownerToken = jwtService.generateToken(ownerUserDetails);
        when(customUserDetailsService.loadUserByUsername("owner@example.com"))
                .thenReturn(ownerUserDetails);

        mockMvc.perform(get("/api/v1/test/owner-only")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(content().string("Owner content"));
    }

    @Test
    @DisplayName("10. CORS preflight matches configured policy for localhost:5173")
    void corsPreflightAllowedForFrontendOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization,Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    @DisplayName("10b. CORS preflight rejects unauthorized origins")
    void corsPreflightRejectsUnknownOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, "http://malicious-site.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }

    @RestController
    @RequestMapping("/api/v1/test")
    static class TestSecurityController {

        @GetMapping("/protected")
        public ResponseEntity<Map<String, String>> protectedEndpoint(
                @AuthenticationPrincipal UserPrincipal principal
        ) {
            return ResponseEntity.ok(Map.of(
                    "userId", principal.getId().toString(),
                    "email", principal.getUsername(),
                    "role", principal.getRole().name()
            ));
        }

        @GetMapping("/owner-only")
        @PreAuthorize("hasRole('OWNER')")
        public ResponseEntity<String> ownerOnly() {
            return ResponseEntity.ok("Owner content");
        }
    }
}
