package com.veritas.otp;
import com.veritas.common.exception.OtpAttemptsExceededException;
import com.veritas.common.exception.OtpExpiredException;
import com.veritas.user.User;
import java.util.UUID;
import com.veritas.user.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.veritas.common.exception.InvalidOtpException;
import com.veritas.common.exception.OtpAlreadyUsedException;
import com.veritas.common.exception.OtpAttemptsExceededException;
import com.veritas.common.exception.OtpExpiredException;
import com.veritas.common.exception.OtpNotFoundException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import com.veritas.otp.delivery.OtpDeliveryService;
import com.veritas.otp.delivery.OtpDeliveryService;
@Service
public class OtpService {

    private static final int OTP_LENGTH = 6;
    private static final int OTP_EXPIRATION_MINUTES = 5;
    private static final int MAX_ATTEMPTS = 5;
    private final SecureRandom secureRandom;
    private final PasswordEncoder passwordEncoder;
    private final OtpVerificationRepository otpVerificationRepository;
    private final OtpDeliveryService otpDeliveryService;
    public OtpService(
            PasswordEncoder passwordEncoder,
            OtpVerificationRepository otpVerificationRepository,
            OtpDeliveryService otpDeliveryService
    ) {
        this.secureRandom = new SecureRandom();
        this.passwordEncoder = passwordEncoder;
        this.otpVerificationRepository = otpVerificationRepository;
        this.otpDeliveryService = otpDeliveryService;
    }

    @Transactional
    public void verifyPhoneVerificationOtp(UUID userId, String otp) {

        OtpVerification verification =
                otpVerificationRepository
                        .findFirstByUserIdAndPurposeOrderByCreatedAtDesc(
                                userId,
                                OtpPurpose.PHONE_VERIFICATION
                        )
                        .orElseThrow(() ->
                                new OtpNotFoundException("No OTP found")
                        );

        if (verification.getVerifiedAt() != null) {
            throw new OtpAlreadyUsedException("OTP has already been used");
        }

        if (OffsetDateTime.now().isAfter(verification.getExpiresAt())) {
            throw new OtpExpiredException("OTP has expired");
        }

        if (verification.getAttempts() >= MAX_ATTEMPTS) {
            throw new OtpAttemptsExceededException("Maximum OTP attempts exceeded");
        }

        boolean matches = passwordEncoder.matches(
                otp,
                verification.getOtpHash()
        );

        if (!matches) {
            verification.setAttempts(verification.getAttempts() + 1);
            otpVerificationRepository.save(verification);

            throw new InvalidOtpException("Invalid OTP");
        }

        User user = verification.getUser();

        user.setVerified(true);
        user.setUpdatedAt(OffsetDateTime.now());

        verification.setVerifiedAt(OffsetDateTime.now());

        otpVerificationRepository.save(verification);
    }

    @Transactional
    public String generatePhoneVerificationOtp(User user) {

        String otp = generateOtp();

        OtpVerification verification = new OtpVerification();

        verification.setUser(user);
        verification.setOtpHash(passwordEncoder.encode(otp));
        verification.setPurpose(OtpPurpose.PHONE_VERIFICATION);
        verification.setExpiresAt(
                OffsetDateTime.now().plusMinutes(OTP_EXPIRATION_MINUTES)
        );
        verification.setAttempts(0);
        verification.setCreatedAt(OffsetDateTime.now());

        otpVerificationRepository.save(verification);
        otpDeliveryService.sendPhoneVerificationOtp(user, otp);
        return otp;
    }



    private String generateOtp() {

        int upperBound = 1_000_000;

        int otpNumber = secureRandom.nextInt(upperBound);

        return String.format("%06d", otpNumber);
    }
}