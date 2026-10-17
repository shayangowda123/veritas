package com.veritas.security;

import com.veritas.user.User;
import com.veritas.user.UserRole;
import io.jsonwebtoken.io.Decoders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    // Valid 256-bit (32 bytes) Base64 secret
    private static final String VALID_SECRET_BASE64 =
            Base64.getEncoder().encodeToString("12345678901234567890123456789012".getBytes());
    private static final long EXPIRATION_MS = 3600000L; // 1 hour

    private JwtService jwtService;
    private UserDetails verifiedUserDetails;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(VALID_SECRET_BASE64, EXPIRATION_MS);

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setName("Test User");
        user.setEmail("renter@example.com");
        user.setPasswordHash("hashedPassword");
        user.setRole(UserRole.RENTER);
        user.setVerified(true);

        verifiedUserDetails = new UserPrincipal(user);
    }

    @Test
    @DisplayName("Constructor throws when secret is null or blank")
    void constructorThrowsOnMissingSecret() {
        assertThrows(IllegalArgumentException.class, () -> new JwtService(null, EXPIRATION_MS));
        assertThrows(IllegalArgumentException.class, () -> new JwtService("   ", EXPIRATION_MS));
    }

    @Test
    @DisplayName("Constructor throws when secret is not valid Base64")
    void constructorThrowsOnMalformedBase64() {
        assertThrows(IllegalArgumentException.class, () -> new JwtService("not_valid_base64!@#$", EXPIRATION_MS));
    }

    @Test
    @DisplayName("Constructor throws when secret is less than 256 bits (32 bytes)")
    void constructorThrowsOnWeakSecret() {
        String weakSecret = Base64.getEncoder().encodeToString("too-short".getBytes());
        assertThrows(IllegalArgumentException.class, () -> new JwtService(weakSecret, EXPIRATION_MS));
    }

    @Test
    @DisplayName("Constructor throws when expiration is non-positive")
    void constructorThrowsOnNonPositiveExpiration() {
        assertThrows(IllegalArgumentException.class, () -> new JwtService(VALID_SECRET_BASE64, 0));
        assertThrows(IllegalArgumentException.class, () -> new JwtService(VALID_SECRET_BASE64, -100));
    }

    @Test
    @DisplayName("Generate token creates valid token containing username and role")
    void generateTokenSuccess() {
        String token = jwtService.generateToken(verifiedUserDetails);

        assertNotNull(token);
        assertFalse(token.isBlank());
        assertEquals("renter@example.com", jwtService.extractUsername(token));
        assertEquals("ROLE_RENTER", jwtService.extractClaims(token).get("role"));
        assertTrue(jwtService.isTokenValid(token, verifiedUserDetails));
    }

    @Test
    @DisplayName("isTokenValid returns false for expired token")
    void isTokenValidReturnsFalseForExpiredToken() {
        // Create service with 1ms expiration
        JwtService shortLivedService = new JwtService(VALID_SECRET_BASE64, 1L);
        String token = shortLivedService.generateToken(verifiedUserDetails);

        // Sleep 10ms to ensure expiration
        try {
            Thread.sleep(10);
        } catch (InterruptedException ignored) {}

        assertFalse(shortLivedService.isTokenValid(token, verifiedUserDetails));
    }

    @Test
    @DisplayName("isTokenValid returns false for different username")
    void isTokenValidReturnsFalseForDifferentUsername() {
        String token = jwtService.generateToken(verifiedUserDetails);

        User anotherUser = new User();
        anotherUser.setId(UUID.randomUUID());
        anotherUser.setEmail("different@example.com");
        anotherUser.setRole(UserRole.OWNER);
        anotherUser.setVerified(true);
        UserDetails anotherPrincipal = new UserPrincipal(anotherUser);

        assertFalse(jwtService.isTokenValid(token, anotherPrincipal));
    }

    @Test
    @DisplayName("isTokenValid returns false for malformed or incorrectly signed token")
    void isTokenValidReturnsFalseForMalformedToken() {
        assertFalse(jwtService.isTokenValid("malformed.token.value", verifiedUserDetails));

        // Token signed with a different key
        String differentSecret = Base64.getEncoder().encodeToString("differentSecretKey32CharactersLong!".getBytes());
        JwtService differentService = new JwtService(differentSecret, EXPIRATION_MS);
        String foreignToken = differentService.generateToken(verifiedUserDetails);

        assertFalse(jwtService.isTokenValid(foreignToken, verifiedUserDetails));
    }

    @Test
    @DisplayName("getExpirationSeconds returns expiration in seconds")
    void getExpirationSecondsCalculatesCorrectly() {
        assertEquals(3600, jwtService.getExpirationSeconds());
    }
}
