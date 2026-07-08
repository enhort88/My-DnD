package com.example.mydnd.director;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Strict Java-side validation. The LLM proposes; Java decides. */
public final class DirectorActionValidator {

    private static final int MAX_NAME = 120;
    private static final int MAX_VALUE = 80;
    private static final int MAX_DETAILS = 300;

    private static final Set<String> CHECK_STATS = setOf("STR", "DEX", "INT", "CHA");
    private static final Set<String> NPC_MEMORY_TONES = setOf("GOOD", "BAD", "NEUTRAL");
    private static final Set<String> NPC_STATUSES = setOf(
            "ACTIVE", "KNOWN", "INACTIVE", "DEAD", "MISSING", "HOSTILE", "ALLY"
    );
    private static final Set<String> ABILITY_CATEGORIES = setOf(
            "SKILL", "SPELL", "TRAIT", "POWER"
    );

    public DirectorValidation validate(DirectorAction action) {
        if (action == null) {
            return DirectorValidation.invalid("ACTION_NULL");
        }

        if (action.getName().length() > MAX_NAME) {
            return DirectorValidation.invalid("NAME_TOO_LONG");
        }
        if (action.getValue().length() > MAX_VALUE) {
            return DirectorValidation.invalid("VALUE_TOO_LONG");
        }
        if (action.getDetails().length() > MAX_DETAILS) {
            return DirectorValidation.invalid("DETAILS_TOO_LONG");
        }

        switch (action.getType()) {
            case NO_CHANGE:
                return isBlank(action.getName())
                        && isBlank(action.getValue())
                        && isBlank(action.getDetails())
                        ? DirectorValidation.valid()
                        : DirectorValidation.invalid("DONE_FIELDS_MUST_BE_EMPTY");

            case CHECK_REQUEST:
                return validateCheck(action);

            case HEALTH_CHANGE:
                return validateHealthDelta(action);

            case MONEY_CHANGE:
                return validateSignedDelta(action, "PLAYER", -1_000_000, 1_000_000);

            case NPC_MEMORY:
                if (isBlank(action.getName())) {
                    return DirectorValidation.invalid("NPC_NAME_REQUIRED");
                }
                if (!NPC_MEMORY_TONES.contains(upper(action.getValue()))) {
                    return DirectorValidation.invalid("NPC_MEMORY_TONE_INVALID");
                }
                if (isBlank(action.getDetails())) {
                    return DirectorValidation.invalid("NPC_MEMORY_FACT_REQUIRED");
                }
                return DirectorValidation.valid();

            case NPC_STATUS:
                if (isBlank(action.getName())) {
                    return DirectorValidation.invalid("NPC_NAME_REQUIRED");
                }
                if (!NPC_STATUSES.contains(upper(action.getValue()))) {
                    return DirectorValidation.invalid("NPC_STATUS_INVALID");
                }
                return DirectorValidation.valid();

            case ABILITY_ADD:
            case ABILITY_UPDATE:
                if (isBlank(action.getName())) {
                    return DirectorValidation.invalid("ABILITY_NAME_REQUIRED");
                }
                if (!ABILITY_CATEGORIES.contains(upper(action.getValue()))) {
                    return DirectorValidation.invalid("ABILITY_CATEGORY_INVALID");
                }
                return DirectorValidation.valid();

            case WORLD_EVENT_ADD:
                if (isBlank(action.getName())) {
                    return DirectorValidation.invalid("NAME_REQUIRED");
                }
                if (!isBlank(action.getValue()) && !isImportance(action.getValue())) {
                    return DirectorValidation.invalid("WORLD_IMPORTANCE_INVALID");
                }
                return DirectorValidation.valid();

            case WORLD_EVENT_UPDATE:
                if (isBlank(action.getName())) {
                    return DirectorValidation.invalid("NAME_REQUIRED");
                }
                if (isBlank(action.getDetails())) {
                    return DirectorValidation.invalid("WORLD_EVENT_DETAILS_REQUIRED");
                }
                if (!isBlank(action.getValue()) && !isImportance(action.getValue())) {
                    return DirectorValidation.invalid("WORLD_IMPORTANCE_INVALID");
                }
                return DirectorValidation.valid();

            case QUEST_UPDATE:
                if (!isBlank(action.getValue())) {
                    return DirectorValidation.invalid("UNUSED_VALUE_MUST_BE_EMPTY");
                }
                if (isBlank(action.getName())) {
                    return DirectorValidation.invalid("NAME_REQUIRED");
                }
                if (isBlank(action.getDetails())) {
                    return DirectorValidation.invalid("QUEST_DETAILS_REQUIRED");
                }
                return DirectorValidation.valid();

            case INVENTORY_ADD:
            case INVENTORY_REMOVE:
            case NPC_UPSERT:
            case WORLD_EVENT_RESOLVE:
            case QUEST_START:
            case QUEST_COMPLETE:
            case QUEST_FAIL:
            case ABILITY_REMOVE:
            case EFFECT_ADD:
            case EFFECT_REMOVE:
            case LOCATION_SET:
                if (!isBlank(action.getValue())) {
                    return DirectorValidation.invalid("UNUSED_VALUE_MUST_BE_EMPTY");
                }
                return requireName(action);

            default:
                return DirectorValidation.invalid("UNSUPPORTED_ACTION_TYPE");
        }
    }

    private DirectorValidation validateCheck(DirectorAction action) {
        if (!CHECK_STATS.contains(upper(action.getName()))) {
            return DirectorValidation.invalid("CHECK_STAT_INVALID");
        }

        int dc;
        try {
            dc = Integer.parseInt(action.getValue());
        } catch (NumberFormatException error) {
            return DirectorValidation.invalid("CHECK_DC_INVALID");
        }

        if (dc < 5 || dc > 25) {
            return DirectorValidation.invalid("CHECK_DC_OUT_OF_RANGE");
        }

        if (isBlank(action.getDetails())) {
            return DirectorValidation.invalid("CHECK_REASON_REQUIRED");
        }

        return DirectorValidation.valid();
    }


    private DirectorValidation validateHealthDelta(DirectorAction action) {
        if (isBlank(action.getName())) {
            return DirectorValidation.invalid("HP_TARGET_REQUIRED");
        }

        int delta;
        try {
            delta = Integer.parseInt(action.getValue());
        } catch (NumberFormatException error) {
            return DirectorValidation.invalid("DELTA_INVALID");
        }

        if (delta == 0 || delta < -1000 || delta > 1000) {
            return DirectorValidation.invalid("DELTA_OUT_OF_RANGE");
        }

        if (isBlank(action.getDetails())) {
            return DirectorValidation.invalid("CHANGE_REASON_REQUIRED");
        }

        return DirectorValidation.valid();
    }

    private DirectorValidation validateSignedDelta(
            DirectorAction action,
            String requiredName,
            int min,
            int max
    ) {
        if (!requiredName.equalsIgnoreCase(action.getName())) {
            return DirectorValidation.invalid("TARGET_INVALID");
        }

        int delta;
        try {
            delta = Integer.parseInt(action.getValue());
        } catch (NumberFormatException error) {
            return DirectorValidation.invalid("DELTA_INVALID");
        }

        if (delta == 0 || delta < min || delta > max) {
            return DirectorValidation.invalid("DELTA_OUT_OF_RANGE");
        }

        if (isBlank(action.getDetails())) {
            return DirectorValidation.invalid("CHANGE_REASON_REQUIRED");
        }

        return DirectorValidation.valid();
    }


    private boolean isImportance(String value) {
        try {
            int importance = Integer.parseInt(value.trim());
            return importance >= 1 && importance <= 3;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    private DirectorValidation requireName(DirectorAction action) {
        return isBlank(action.getName())
                ? DirectorValidation.invalid("NAME_REQUIRED")
                : DirectorValidation.valid();
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static Set<String> setOf(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }
}
