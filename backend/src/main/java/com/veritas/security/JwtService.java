
package com.veritas.security;

import com.veritas.security.UserPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtService(
            @Value("${security.jwt.secret-base64}") String secretBase64,
            @Value("${security.jwt.expiration-ms}") long expirationMs
    ) {
        if (secretBase64 == null || secretBase64.trim().isEmpty()) {
            throw new IllegalArgumentException("JWT secret-base64 must not be empty. Please configure SECURITY_JWT_SECRET_BASE64.");
        }
        if (expirationMs <= 0) {
            throw new IllegalArgumentException("JWT expiration-ms must be greater than zero. Please configure SECURITY_JWT_EXPIRATION_MS.");
        }

        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(secretBase64.trim());
        } catch (Exception ex) {
            throw new IllegalArgumentException("JWT secret-base64 is not valid Base64: " + ex.getMessage(), ex);
        }

        if (keyBytes.length < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 256 bits (32 bytes) for HMAC-SHA256. Provided secret was " + (keyBytes.length * 8) + " bits.");
        }

        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMs = expirationMs;
    }

    public String generateToken(UserDetails userDetails) {
        Instant now = Instant.now();

        String role = userDetails.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Authenticated user has no role"
                        )
                );

        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);

        return Jwts.builder()
                .claims(claims)
                .subject(userDetails.getUsername())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(signingKey)
                .compact();
    }

    public Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractUsername(String token) {
        return extractClaims(token).getSubject();
    }

    public boolean isTokenValid(
            String token,
            UserDetails userDetails
    ) {
        try {
            Claims claims = extractClaims(token);
            return claims.getSubject().equals(userDetails.getUsername())
                    && claims.getExpiration().after(new Date());
        } catch (Exception ex) {
            return false;
        }
    }

    public long getExpirationSeconds() {
        return expirationMs / 1000;
    }
}
