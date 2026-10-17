package com.veritas.otp.delivery;

import com.veritas.user.User;

public interface OtpDeliveryService {

    void sendPhoneVerificationOtp(User user, String otp);
}