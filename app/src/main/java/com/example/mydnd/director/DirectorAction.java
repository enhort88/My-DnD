package com.example.mydnd.director;

import java.util.Objects;

/** One normalized director_action tool call. */
public final class DirectorAction {

    private final DirectorActionType type;
    private final String name;
    private final String value;
    private final String details;

    public DirectorAction(
            DirectorActionType type,
            String name,
            String value,
            String details
    ) {
        this.type = Objects.requireNonNull(type, "type");
        this.name = safe(name);
        this.value = safe(value);
        this.details = safe(details);
    }

    public DirectorActionType getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }

    public String getDetails() {
        return details;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public String toString() {
        return "DirectorAction{" +
                "type=" + type +
                ", name='" + name + '\'' +
                ", value='" + value + '\'' +
                ", details='" + details + '\'' +
                '}';
    }
}
