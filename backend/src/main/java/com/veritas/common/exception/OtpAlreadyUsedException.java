package com.veritas.common.exception;

public class OtpAlreadyUsedException extends RuntimeException {

    public OtpAlreadyUsedException(String message) {
        super(message);
    }
}