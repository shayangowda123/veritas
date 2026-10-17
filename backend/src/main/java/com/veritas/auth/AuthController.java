
package com.veritas.auth;

import com.veritas.auth.dto.LoginRequest;
import com.veritas.auth.dto.LoginResponse;
import com.veritas.otp.OtpService;
import com.veritas.otp.dto.VerifyOtpRequest;
import com.veritas.user.User;
import com.veritas.user.UserService;
import com.veritas.user.dto.RegisterUserRequest;
import com.veritas.user.dto.RegisterUserResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserService userService;
    private final OtpService otpService;
    private final LoginService loginService;

    public AuthController(
            UserService userService,
            OtpService otpService,
            LoginService loginService
    ) {
        this.userService = userService;
        this.otpService = otpService;
        this.loginService = loginService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterUserResponse> register(
            @Valid @RequestBody RegisterUserRequest request
    ) {
        User user = userService.register(request);

        RegisterUserResponse response = new RegisterUserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole().name(),
                user.isVerified(),
                "User registered successfully. Please verify your phone number."
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request
    ) {
        return ResponseEntity.ok(loginService.login(request));
    }

    @PostMapping("/verify-phone")
    public ResponseEntity<String> verifyPhone(
            @Valid @RequestBody VerifyOtpRequest request
    ) {
        UUID userId;

        try {
            userId = UUID.fromString(request.getUserId());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid user ID");
        }

        otpService.verifyPhoneVerificationOtp(
                userId,
                request.getOtp()
        );

        return ResponseEntity.ok(
                "Phone number verified successfully"
        );
    }
}
