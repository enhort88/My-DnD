package com.example.mydnd.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.example.mydnd.db.entity.WorldEntity;
import com.example.mydnd.db.entity.WorldRaceEntity;

import java.util.List;

@Dao
public interface WorldDao {

    @Insert
    long insertWorld(WorldEntity world);

    @Insert
    long insertRace(WorldRaceEntity race);

    @Query("SELECT * FROM worlds WHERE id = :worldId LIMIT 1")
    WorldEntity getWorld(long worldId);

    @Query("SELECT * FROM world_races WHERE world_id = :worldId ORDER BY id ASC")
    List<WorldRaceEntity> getRaces(long worldId);

    @Query("SELECT * FROM worlds ORDER BY id DESC")
    List<WorldEntity> getAllWorlds();
}
