package com.prg.controlplane.exception;

public class ResourceNotFoundException extends RuntimeException {

    private final String code;

    public ResourceNotFoundException(String message, String code) {
        super(message);
        this.code = code;
    }

    public ResourceNotFoundException(String message) {
        this(message, "RESOURCE_NOT_FOUND");
    }

    public String getCode() {
        return code;
    }
}
