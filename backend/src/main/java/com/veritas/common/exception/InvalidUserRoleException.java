package com.veritas.common.exception;

public class InvalidUserRoleException extends RuntimeException {

    public InvalidUserRoleException(String message) {
        super(message);
    }
}