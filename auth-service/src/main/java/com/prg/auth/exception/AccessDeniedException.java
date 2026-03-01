package com.prg.auth.exception;

public class AccessDeniedException extends RuntimeException {

    private final String code;

    public AccessDeniedException(String message, String code) {
        super(message);
        this.code = code;
    }

    public AccessDeniedException(String message) {
        this(message, "ACCESS_DENIED");
    }

    public String getCode() {
        return code;
    }
}
