package com.example.mydnd.director;

/** Result returned by the canonical state storage layer (the "Room"). */
public final class DirectorStoreResult {

    private final boolean applied;
    private final String code;
    private final String stateAfter;
    private final long stateChangeId;

    private DirectorStoreResult(
            boolean applied,
            String code,
            String stateAfter,
            long stateChangeId
    ) {
        this.applied = applied;
        this.code = safe(code);
        this.stateAfter = safe(stateAfter);
        this.stateChangeId = Math.max(0L, stateChangeId);
    }

    public static DirectorStoreResult applied(String code, String stateAfter) {
        return applied(code, stateAfter, 0L);
    }

    public static DirectorStoreResult applied(
            String code,
            String stateAfter,
            long stateChangeId
    ) {
        return new DirectorStoreResult(true, code, stateAfter, stateChangeId);
    }

    public static DirectorStoreResult rejected(String code, String stateAfter) {
        return new DirectorStoreResult(false, code, stateAfter, 0L);
    }

    public boolean isApplied() {
        return applied;
    }

    public String getCode() {
        return code;
    }

    public String getStateAfter() {
        return stateAfter;
    }

    public long getStateChangeId() {
        return stateChangeId;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
