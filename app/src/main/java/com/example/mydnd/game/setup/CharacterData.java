package com.example.mydnd.game.setup;

import com.example.mydnd.db.entity.CharacterEntity;
import com.example.mydnd.db.entity.CharacterStartingItemEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CharacterData {

    private final CharacterEntity character;
    private final List<CharacterStartingItemEntity> startingItems;

    public CharacterData(
            CharacterEntity character,
            List<CharacterStartingItemEntity> startingItems
    ) {
        this.character = character;
        this.startingItems = startingItems == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(startingItems));
    }

    public CharacterEntity getCharacter() {
        return character;
    }

    public List<CharacterStartingItemEntity> getStartingItems() {
        return startingItems;
    }
}
