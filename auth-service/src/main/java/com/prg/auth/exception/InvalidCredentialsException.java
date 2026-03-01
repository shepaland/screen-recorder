package com.prg.auth.exception;

public class InvalidCredentialsException extends RuntimeException {

    private final String code;

    public InvalidCredentialsException(String message, String code) {
        super(message);
        this.code = code;
    }

    public InvalidCredentialsException(String message) {
        this(message, "INVALID_CREDENTIALS");
    }

    public String getCode() {
        return code;
    }
}
