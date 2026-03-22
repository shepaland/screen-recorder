package com.prg.auth.util;

public final class PasswordValidator {

    private PasswordValidator() {}

    /**
     * Validate password strength: min 8 chars, at least 1 letter and 1 digit.
     * @return null if valid, error message if invalid
     */
    public static String validate(String password) {
        if (password == null || password.length() < 8) {
            return "Password must be at least 8 characters long";
        }
        if (!password.matches(".*[a-zA-Z].*")) {
            return "Password must contain at least one letter";
        }
        if (!password.matches(".*\\d.*")) {
            return "Password must contain at least one digit";
        }
        return null;
    }
}
