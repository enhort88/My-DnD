package com.example.mydnd.changeset;

import com.example.mydnd.director.DirectorActionType;

/** One compact operation extracted from the Master's already-written narrative. */
public final class ChangeSetOperation {
    private final DirectorActionType type;
    private final String target;
    private final String value;

    public ChangeSetOperation(DirectorActionType type, String target, String value) {
        this.type = type;
        this.target = safe(target);
        this.value = safe(value);
    }

    public DirectorActionType getType() { return type; }
    public String getTarget() { return target; }
    public String getValue() { return value; }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public String toString() {
        return type.getToolCode() + "(" + target + (value.isEmpty() ? "" : "," + value) + ")";
    }
}
