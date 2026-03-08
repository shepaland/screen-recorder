package com.prg.auth.exception;

public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String message, String code) {
        super(message);
        this.code = code;
    }

    public ConflictException(String message) {
        this(message, "CONFLICT");
    }

    public String getCode() {
        return code;
    }
}
