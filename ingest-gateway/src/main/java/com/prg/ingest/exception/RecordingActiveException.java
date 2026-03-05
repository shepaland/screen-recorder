package com.prg.ingest.exception;

/**
 * Thrown when an operation is attempted on an active recording that requires
 * the recording to be in a terminal state (completed, failed, interrupted).
 */
public class RecordingActiveException extends RuntimeException {

    private final String code;

    public RecordingActiveException(String message, String code) {
        super(message);
        this.code = code;
    }

    public RecordingActiveException(String message) {
        this(message, "RECORDING_IS_ACTIVE");
    }

    public String getCode() {
        return code;
    }
}
