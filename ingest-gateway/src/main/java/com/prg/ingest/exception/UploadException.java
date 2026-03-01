package com.prg.ingest.exception;

public class UploadException extends RuntimeException {

    private final String code;

    public UploadException(String message, String code) {
        super(message);
        this.code = code;
    }

    public UploadException(String message) {
        this(message, "UPLOAD_ERROR");
    }

    public String getCode() {
        return code;
    }
}
