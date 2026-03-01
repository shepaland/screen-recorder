package com.prg.auth.exception;

public class RateLimitExceededException extends RuntimeException {

    private final String code;

    public RateLimitExceededException(String message, String code) {
        super(message);
        this.code = code;
    }

    public RateLimitExceededException(String message) {
        this(message, "TOO_MANY_REQUESTS");
    }

    public String getCode() {
        return code;
    }
}
