package com.example.mydnd.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.example.mydnd.db.entity.CharacterEntity;
import com.example.mydnd.db.entity.CharacterStartingItemEntity;

import java.util.List;

@Dao
public interface CharacterDao {

    @Insert
    long insertCharacter(CharacterEntity character);

    @Insert
    long insertStartingItem(CharacterStartingItemEntity item);

    @Query("SELECT * FROM characters WHERE id = :characterId LIMIT 1")
    CharacterEntity getCharacter(long characterId);

    @Query("SELECT * FROM character_starting_items WHERE character_id = :characterId ORDER BY id ASC")
    List<CharacterStartingItemEntity> getStartingItems(long characterId);

    @Query("SELECT * FROM characters ORDER BY id DESC")
    List<CharacterEntity> getAllCharacters();
}
