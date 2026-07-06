package com.example.mydnd.game.setup;

import com.example.mydnd.db.AppDatabase;
import com.example.mydnd.db.dao.CharacterDao;
import com.example.mydnd.db.entity.CharacterEntity;
import com.example.mydnd.db.entity.CharacterStartingItemEntity;
import com.example.mydnd.draft.CharacterDraft;

public class CharacterRepository {

    private final CharacterDao characterDao;

    public CharacterRepository(AppDatabase database) {
        characterDao = database.characterDao();
    }

    public long createCharacter(
            long originWorldId,
            CharacterDraft draft,
            String generationPrompt
    ) {
        CharacterEntity entity = new CharacterEntity();
        entity.originWorldId = originWorldId;
        entity.name = draft.getName();
        entity.race = draft.getRace();
        entity.className = draft.getClassName();
        entity.age = draft.getAge();
        entity.description = draft.getDescription();
        entity.background = draft.getBackground();
        entity.personality = draft.getPersonality();
        entity.generationPrompt = safe(generationPrompt);
        entity.createdAt = System.currentTimeMillis();

        long characterId = characterDao.insertCharacter(entity);

        for (String itemName : draft.getStartingItems()) {
            String safeName = safe(itemName);

            if (safeName.isEmpty()) {
                continue;
            }

            CharacterStartingItemEntity item = new CharacterStartingItemEntity();
            item.characterId = characterId;
            item.name = safeName;

            characterDao.insertStartingItem(item);
        }

        return characterId;
    }

    public CharacterData getCharacterData(long characterId) {
        return new CharacterData(
                characterDao.getCharacter(characterId),
                characterDao.getStartingItems(characterId)
        );
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
