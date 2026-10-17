package com.veritas.common.exception;

public class OtpAttemptsExceededException extends RuntimeException {

    public OtpAttemptsExceededException(String message) {
        super(message);
    }
}