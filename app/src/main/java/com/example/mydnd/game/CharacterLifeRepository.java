package com.example.mydnd.game;

import com.example.mydnd.db.AppDatabase;
import com.example.mydnd.db.entity.CampaignEntity;
import com.example.mydnd.db.entity.CharacterEntity;
import com.example.mydnd.db.entity.StateChangeEntity;

/**
 * Java-owned death-save rules. The LLM may describe the outcome, but it never
 * decides whether the character is alive, downed, stable or dead.
 */
public final class CharacterLifeRepository {

    private final AppDatabase database;
    private final StateChangeRepository stateChangeRepository;

    public CharacterLifeRepository(
            AppDatabase database,
            StateChangeRepository stateChangeRepository
    ) {
        this.database = database;
        this.stateChangeRepository = stateChangeRepository;
    }

    public CharacterEntity getForCampaign(long campaignId) {
        CampaignEntity campaign = database.campaignDao().getById(campaignId);
        if (campaign == null || campaign.characterId <= 0L) {
            return null;
        }
        return database.characterDao().getCharacter(campaign.characterId);
    }

    public DeathSaveOutcome resolveDeathSave(long campaignId, int naturalRoll) {
        if (naturalRoll < 1 || naturalRoll > 20) {
            return DeathSaveOutcome.rejected("INVALID_ROLL");
        }

        try {
            return database.runInTransaction(
                    () -> resolveDeathSaveInside(campaignId, naturalRoll)
            );
        } catch (RuntimeException error) {
            return DeathSaveOutcome.rejected("DEATH_SAVE_TRANSACTION_FAILED");
        }
    }

    private DeathSaveOutcome resolveDeathSaveInside(
            long campaignId,
            int naturalRoll
    ) {
        CharacterEntity character = getForCampaign(campaignId);
        if (character == null) {
            return DeathSaveOutcome.rejected("CHARACTER_NOT_FOUND");
        }

        CharacterLifeState beforeState = CharacterLifeState.from(
                character.lifeState,
                character.hp
        );

        if (beforeState != CharacterLifeState.DOWNED || character.hp > 0) {
            return DeathSaveOutcome.rejected("DEATH_SAVE_NOT_REQUIRED");
        }

        int successes = clampSaveCount(character.deathSaveSuccesses);
        int failures = clampSaveCount(character.deathSaveFailures);
        int hp = 0;
        CharacterLifeState afterState = CharacterLifeState.DOWNED;
        String type;
        String title;
        String message;

        if (naturalRoll == 20) {
            hp = Math.min(1, Math.max(1, character.maxHp));
            successes = 0;
            failures = 0;
            afterState = CharacterLifeState.ALIVE;
            type = StateChangeRepository.TYPE_CHARACTER_REVIVED;
            title = "КРИТИЧЕСКИЙ УСПЕХ";
            message = "Натуральная 20 · персонаж приходит в себя с 1 HP";
        } else {
            if (naturalRoll == 1) {
                failures += 2;
            } else if (naturalRoll >= 10) {
                successes += 1;
            } else {
                failures += 1;
            }

            successes = clampSaveCount(successes);
            failures = clampSaveCount(failures);

            if (failures >= 3) {
                afterState = CharacterLifeState.DEAD;
                type = StateChangeRepository.TYPE_CHARACTER_DEAD;
                title = "ПЕРСОНАЖ ПОГИБ";
                message = "Три провала спасбросков смерти";
            } else if (successes >= 3) {
                afterState = CharacterLifeState.STABLE;
                type = StateChangeRepository.TYPE_CHARACTER_STABLE;
                title = "ПЕРСОНАЖ СТАБИЛЕН";
                message = "Три успешных спасброска смерти · персонаж без сознания";
            } else if (naturalRoll >= 10) {
                type = StateChangeRepository.TYPE_DEATH_SAVE_SUCCESS;
                title = "СПАСБРОСОК СМЕРТИ: УСПЕХ";
                message = "d20=" + naturalRoll;
            } else {
                type = StateChangeRepository.TYPE_DEATH_SAVE_FAILURE;
                title = "СПАСБРОСОК СМЕРТИ: ПРОВАЛ";
                message = naturalRoll == 1
                        ? "Натуральная 1 · два провала"
                        : "d20=" + naturalRoll;
            }
        }

        int updated = database.characterDao().updateHealthState(
                character.id,
                hp,
                afterState.name(),
                successes,
                failures
        );

        if (updated != 1) {
            return DeathSaveOutcome.rejected("DEATH_SAVE_UPDATE_FAILED");
        }

        String afterText = afterState.name()
                + " | успехи " + successes + "/3"
                + " | провалы " + failures + "/3";

        StateChangeEntity change = stateChangeRepository.recordSystemChange(
                campaignId,
                type,
                title,
                character.name + " · " + message
                        + " · успехи " + successes + "/3"
                        + " · провалы " + failures + "/3",
                character.id,
                character.name,
                beforeState.name(),
                afterText,
                character.hp,
                hp
        );

        if (change == null) {
            throw new IllegalStateException("DEATH_SAVE_CARD_FAILED");
        }

        return DeathSaveOutcome.applied(
                naturalRoll,
                afterState,
                successes,
                failures,
                hp,
                change.id
        );
    }

    private int clampSaveCount(int value) {
        return Math.max(0, Math.min(3, value));
    }

    public static final class DeathSaveOutcome {
        private final boolean applied;
        private final String code;
        private final int roll;
        private final CharacterLifeState state;
        private final int successes;
        private final int failures;
        private final int hp;
        private final long stateChangeId;

        private DeathSaveOutcome(
                boolean applied,
                String code,
                int roll,
                CharacterLifeState state,
                int successes,
                int failures,
                int hp,
                long stateChangeId
        ) {
            this.applied = applied;
            this.code = code;
            this.roll = roll;
            this.state = state;
            this.successes = successes;
            this.failures = failures;
            this.hp = hp;
            this.stateChangeId = stateChangeId;
        }

        public static DeathSaveOutcome rejected(String code) {
            return new DeathSaveOutcome(
                    false,
                    code == null ? "REJECTED" : code,
                    0,
                    CharacterLifeState.ALIVE,
                    0,
                    0,
                    0,
                    0L
            );
        }

        public static DeathSaveOutcome applied(
                int roll,
                CharacterLifeState state,
                int successes,
                int failures,
                int hp,
                long stateChangeId
        ) {
            return new DeathSaveOutcome(
                    true,
                    "APPLIED",
                    roll,
                    state,
                    successes,
                    failures,
                    hp,
                    stateChangeId
            );
        }

        public boolean isApplied() { return applied; }
        public String getCode() { return code; }
        public int getRoll() { return roll; }
        public CharacterLifeState getState() { return state; }
        public int getSuccesses() { return successes; }
        public int getFailures() { return failures; }
        public int getHp() { return hp; }
        public long getStateChangeId() { return stateChangeId; }
    }
}
