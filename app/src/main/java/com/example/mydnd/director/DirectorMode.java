package com.example.mydnd.director;

/**
 * Limits what the Director is allowed to do in a given generation pass.
 * PLAYER_ACTION handles only direct consequences of the player's current action.
 * RANDOM_WORLD_EVENT is a rare autonomous world pulse with a much smaller action set.
 */
public enum DirectorMode {
    PLAYER_ACTION(5, 4),
    CHECK_RESULT(2, 1),
    RANDOM_WORLD_EVENT(3, 2);

    private final int maxAttempts;
    private final int maxAppliedActions;

    DirectorMode(int maxAttempts, int maxAppliedActions) {
        this.maxAttempts = maxAttempts;
        this.maxAppliedActions = maxAppliedActions;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public int getMaxAppliedActions() {
        return maxAppliedActions;
    }

    public boolean allows(DirectorActionType type) {
        if (type == null) {
            return false;
        }

        if (this == PLAYER_ACTION) {
            return true;
        }

        if (this == CHECK_RESULT) {
            return type == DirectorActionType.NO_CHANGE
                    || type == DirectorActionType.HEALTH_CHANGE;
        }

        switch (type) {
            case NO_CHANGE:
            case WORLD_EVENT_ADD:
            case NPC_UPSERT:
            case QUEST_START:
            case EFFECT_ADD:
                return true;
            default:
                return false;
        }
    }
}
