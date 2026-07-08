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

    @Query(
            "UPDATE characters SET hp = :hp WHERE id = :characterId"
    )
    int updateHp(long characterId, int hp);

    @Query("UPDATE characters SET money = :money WHERE id = :characterId")
    int updateMoney(long characterId, int money);

    @Query(
            "UPDATE characters SET hp = :hp, life_state = :lifeState, "
                    + "death_save_successes = :deathSaveSuccesses, "
                    + "death_save_failures = :deathSaveFailures "
                    + "WHERE id = :characterId"
    )
    int updateHealthState(
            long characterId,
            int hp,
            String lifeState,
            int deathSaveSuccesses,
            int deathSaveFailures
    );

    @Query(
            "UPDATE characters SET strength = :strength, dexterity = :dexterity, "
                    + "intelligence = :intelligence, charisma = :charisma "
                    + "WHERE id = :characterId"
    )
    int updateBaseStats(
            long characterId,
            int strength,
            int dexterity,
            int intelligence,
            int charisma
    );
}
