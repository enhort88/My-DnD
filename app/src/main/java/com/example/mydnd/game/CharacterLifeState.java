package com.example.mydnd.game;

import java.util.Locale;

/** Canonical Java-owned life state for the player character. */
public enum CharacterLifeState {
    ALIVE,
    DOWNED,
    STABLE,
    DEAD;

    public static CharacterLifeState from(String raw, int hp) {
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                CharacterLifeState parsed = valueOf(
                        raw.trim().toUpperCase(Locale.ROOT)
                );

                if (hp > 0 && parsed != DEAD) {
                    return ALIVE;
                }

                if (hp <= 0 && parsed == ALIVE) {
                    return DOWNED;
                }

                return parsed;
            } catch (IllegalArgumentException ignored) {
                // Fall back to HP for old or malformed rows.
            }
        }

        return hp > 0 ? ALIVE : DOWNED;
    }

    public boolean needsDeathSave() {
        return this == DOWNED;
    }

    public boolean blocksNormalActions() {
        return this != ALIVE;
    }
}
