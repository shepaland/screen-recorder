package com.prg.auth.exception;

public class TokenExpiredException extends RuntimeException {

    private final String code;

    public TokenExpiredException(String message, String code) {
        super(message);
        this.code = code;
    }

    public TokenExpiredException(String message) {
        this(message, "REFRESH_TOKEN_EXPIRED");
    }

    public String getCode() {
        return code;
    }
}
