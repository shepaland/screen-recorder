package com.prg.auth.exception;

public class DeviceDeactivatedException extends RuntimeException {

    private final String code;

    public DeviceDeactivatedException(String message, String code) {
        super(message);
        this.code = code;
    }

    public DeviceDeactivatedException(String message) {
        this(message, "DEVICE_DEACTIVATED");
    }

    public String getCode() {
        return code;
    }
}
