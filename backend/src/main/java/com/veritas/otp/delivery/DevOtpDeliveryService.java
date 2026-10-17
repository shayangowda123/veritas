package com.veritas.otp.delivery;

import com.veritas.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!prod")
public class DevOtpDeliveryService implements OtpDeliveryService {

    private static final Logger log =
            LoggerFactory.getLogger(DevOtpDeliveryService.class);

    @Override
    public void sendPhoneVerificationOtp(User user, String otp) {

        log.warn(
                "DEVELOPMENT OTP | userId={} | phone={} | otp={} | purpose=PHONE_VERIFICATION",
                user.getId(),
                user.getPhone(),
                otp
        );
    }
}