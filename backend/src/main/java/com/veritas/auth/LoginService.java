
package com.veritas.auth;

import com.veritas.auth.dto.LoginRequest;
import com.veritas.auth.dto.LoginResponse;
import com.veritas.security.JwtService;
import com.veritas.security.UserPrincipal;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class LoginService {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public LoginService(
            AuthenticationManager authenticationManager,
            JwtService jwtService
    ) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    public LoginResponse login(LoginRequest request) {

        String email = request.getEmail()
                .trim()
                .toLowerCase(Locale.ROOT);

        Authentication authentication =
                authenticationManager.authenticate(
                        new UsernamePasswordAuthenticationToken(
                                email,
                                request.getPassword()
                        )
                );

        UserPrincipal principal =
                (UserPrincipal) authentication.getPrincipal();

        String token = jwtService.generateToken(principal);

        return new LoginResponse(
                token,
                "Bearer",
                jwtService.getExpirationSeconds(),
                principal.getId(),
                principal.getUsername(),
                principal.getRole().name()
        );
    }
}
