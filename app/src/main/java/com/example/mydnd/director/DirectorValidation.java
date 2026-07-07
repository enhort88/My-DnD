package com.example.mydnd.director;

/** Validation outcome before any persistent mutation is allowed. */
public final class DirectorValidation {

    private final boolean valid;
    private final String code;

    private DirectorValidation(boolean valid, String code) {
        this.valid = valid;
        this.code = code == null ? "" : code;
    }

    public static DirectorValidation valid() {
        return new DirectorValidation(true, "OK");
    }

    public static DirectorValidation invalid(String code) {
        return new DirectorValidation(false, code);
    }

    public boolean isValid() {
        return valid;
    }

    public String getCode() {
        return code;
    }
}
