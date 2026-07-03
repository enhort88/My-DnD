package com.example.mydnd.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.example.mydnd.db.entity.GameEventEntity;

import java.util.List;

@Dao
public interface GameEventDao {

    @Insert
    long insert(GameEventEntity event);

    @Query("SELECT * FROM game_events WHERE campaign_id = :campaignId ORDER BY id ASC")
    List<GameEventEntity> getEventsForCampaign(long campaignId);

    @Query("DELETE FROM game_events WHERE id = :eventId")
    void deleteById(long eventId);
}