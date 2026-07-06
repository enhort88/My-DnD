package com.example.mydnd.game;

import com.example.mydnd.db.AppDatabase;
import com.example.mydnd.db.entity.CharacterEntity;

public class CharacterStateRepository {

    private final AppDatabase database;

    public CharacterStateRepository(AppDatabase database) {
        this.database = database;
    }

    public CharacterEntity get(long characterId) {
        return database.characterDao().getCharacter(characterId);
    }

    public boolean applyDamage(long characterId, int damage) {
        CharacterEntity character = get(characterId);

        if (character == null || damage <= 0) {
            return false;
        }

        int newHp = Math.max(0, character.hp - damage);
        return database.characterDao().updateHp(characterId, newHp) > 0;
    }

    public boolean heal(long characterId, int amount) {
        CharacterEntity character = get(characterId);

        if (character == null || amount <= 0) {
            return false;
        }

        int newHp = Math.min(
                character.maxHp,
                character.hp + amount
        );

        return database.characterDao().updateHp(characterId, newHp) > 0;
    }

    public boolean updateBaseStats(
            long characterId,
            int strength,
            int dexterity,
            int intelligence,
            int charisma
    ) {
        return database.characterDao().updateBaseStats(
                characterId,
                clampStat(strength),
                clampStat(dexterity),
                clampStat(intelligence),
                clampStat(charisma)
        ) > 0;
    }

    public boolean isDead(CharacterEntity character) {
        return character != null && character.hp <= 0;
    }

    private int clampStat(int value) {
        return Math.max(1, Math.min(30, value));
    }
}
