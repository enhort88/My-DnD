package com.example.mydnd.director;

import java.util.Locale;

/**
 * Every structural game-state change that the local LLM may propose.
 * Tool codes are deliberately shorter than Java enum names to save prompt and generation tokens.
 */
public enum DirectorActionType {
    NO_CHANGE("DONE"),
    CHECK_REQUEST("CHECK"),

    INVENTORY_ADD("INV_ADD"),
    INVENTORY_REMOVE("INV_REMOVE"),
    HEALTH_CHANGE("HP"),
    MONEY_CHANGE("MONEY"),

    NPC_UPSERT("NPC_UPSERT"),
    NPC_MEMORY("NPC_MEMORY"),
    NPC_STATUS("NPC_STATUS"),

    WORLD_EVENT_ADD("WORLD_ADD"),
    WORLD_EVENT_UPDATE("WORLD_UPDATE"),
    WORLD_EVENT_RESOLVE("WORLD_RESOLVE"),

    QUEST_START("QUEST_START"),
    QUEST_UPDATE("QUEST_UPDATE"),
    QUEST_COMPLETE("QUEST_COMPLETE"),
    QUEST_FAIL("QUEST_FAIL"),

    ABILITY_ADD("ABILITY_ADD"),
    ABILITY_UPDATE("ABILITY_UPDATE"),
    ABILITY_REMOVE("ABILITY_REMOVE"),

    EFFECT_ADD("EFFECT_ADD"),
    EFFECT_REMOVE("EFFECT_REMOVE"),
    LOCATION_SET("LOCATION");

    private final String toolCode;

    DirectorActionType(String toolCode) {
        this.toolCode = toolCode;
    }

    public String getToolCode() {
        return toolCode;
    }

    public static DirectorActionType fromToolValue(String rawValue) {
        if (rawValue == null) {
            throw new IllegalArgumentException("Action type is null");
        }

        String normalized = rawValue.trim().toUpperCase(Locale.ROOT);

        for (DirectorActionType type : values()) {
            if (type.toolCode.equals(normalized) || type.name().equals(normalized)) {
                return type;
            }
        }

        throw new IllegalArgumentException("Unknown action type: " + rawValue);
    }
}
