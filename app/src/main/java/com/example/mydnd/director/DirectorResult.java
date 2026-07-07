package com.example.mydnd.director;

import java.util.Objects;

/** Final Java-authoritative result of one proposed tool call. */
public final class DirectorResult {

    private final DirectorAction action;
    private final DirectorStatus status;
    private final String code;
    private final String stateAfter;
    private final long stateChangeId;

    public DirectorResult(
            DirectorAction action,
            DirectorStatus status,
            String code,
            String stateAfter,
            long stateChangeId
    ) {
        this.action = Objects.requireNonNull(action, "action");
        this.status = Objects.requireNonNull(status, "status");
        this.code = safe(code);
        this.stateAfter = safe(stateAfter);
        this.stateChangeId = Math.max(0L, stateChangeId);
    }

    public static DirectorResult applied(
            DirectorAction action,
            String code,
            String stateAfter,
            long stateChangeId
    ) {
        return new DirectorResult(
                action,
                DirectorStatus.APPLIED,
                code,
                stateAfter,
                stateChangeId
        );
    }

    public static DirectorResult rejected(
            DirectorAction action,
            String code,
            String stateAfter
    ) {
        return new DirectorResult(
                action,
                DirectorStatus.REJECTED,
                code,
                stateAfter,
                0L
        );
    }

    public static DirectorResult noChange(DirectorAction action) {
        return new DirectorResult(
                action,
                DirectorStatus.NO_CHANGE,
                "NO_CHANGE",
                "",
                0L
        );
    }

    public DirectorAction getAction() {
        return action;
    }

    public DirectorStatus getStatus() {
        return status;
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
