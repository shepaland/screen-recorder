package com.prg.auth.exception;

public class DuplicateResourceException extends RuntimeException {

    private final String code;

    public DuplicateResourceException(String message, String code) {
        super(message);
        this.code = code;
    }

    public DuplicateResourceException(String message) {
        this(message, "DUPLICATE_RESOURCE");
    }

    public String getCode() {
        return code;
    }
}
