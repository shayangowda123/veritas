package com.veritas.otp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OtpVerificationRepository
        extends JpaRepository<OtpVerification, UUID> {

    Optional<OtpVerification> findFirstByUserIdAndPurposeOrderByCreatedAtDesc(
            UUID userId,
            OtpPurpose purpose
    );
}