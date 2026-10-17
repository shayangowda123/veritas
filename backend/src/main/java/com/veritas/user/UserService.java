package com.veritas.user;

import com.veritas.common.exception.EmailAlreadyExistsException;
import com.veritas.common.exception.InvalidUserRoleException;
import com.veritas.common.exception.PasswordMismatchException;
import com.veritas.otp.OtpService;
import com.veritas.user.dto.RegisterUserRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Locale;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final OtpService otpService;

    public UserService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            OtpService otpService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.otpService = otpService;
    }

    @Transactional
    public User register(RegisterUserRequest request) {

        String email = request.getEmail()
                .trim()
                .toLowerCase(Locale.ROOT);

        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException("Email is already registered");
        }

        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new PasswordMismatchException("Passwords do not match");
        }

        UserRole role;

        try {
            role = UserRole.valueOf(
                    request.getRole().trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException ex) {
            throw new InvalidUserRoleException("Invalid user role");
        }

        User user = new User();

        user.setName(request.getName().trim());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setPhone(request.getPhone());
        user.setRole(role);
        user.setVerified(false);

        OffsetDateTime now = OffsetDateTime.now();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        User savedUser = userRepository.save(user);

        otpService.generatePhoneVerificationOtp(savedUser);

        return savedUser;
    }
}