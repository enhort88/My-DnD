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


    @Query(
            "SELECT COUNT(*) FROM game_events " +
                    "WHERE campaign_id = :campaignId " +
                    "AND speaker = :speaker"
    )
    int countEventsBySpeaker(
            long campaignId,
            String speaker
    );

    @Query(
            "SELECT * FROM (" +
                    "SELECT * FROM game_events " +
                    "WHERE campaign_id = :campaignId " +
                    "AND speaker = :speaker " +
                    "ORDER BY id DESC LIMIT :limit" +
                    ") ORDER BY id ASC"
    )
    List<GameEventEntity> getRecentEventsBySpeaker(
            long campaignId,
            String speaker,
            int limit
    );

    @Query("DELETE FROM game_events WHERE id = :eventId")
    void deleteById(long eventId);

    @Query(
            "SELECT * FROM (" +
                    "SELECT * FROM game_events " +
                    "WHERE campaign_id = :campaignId " +
                    "ORDER BY id DESC LIMIT :limit" +
                    ") ORDER BY id ASC"
    )
    List<GameEventEntity> getRecentJournalEvents(
            long campaignId,
            int limit
    );

    @Query(
            "SELECT * FROM game_events " +
                    "WHERE campaign_id = :campaignId " +
                    "AND include_in_prompt = 1 " +
                    "ORDER BY id DESC " +
                    "LIMIT :limit"
    )
    List<GameEventEntity> getRecentPromptEvents(
            long campaignId,
            int limit
    );

    @Query(
            "SELECT * FROM game_events " +
                    "WHERE campaign_id = :campaignId " +
                    "AND include_in_prompt = 1 " +
                    "AND id > :afterEventId " +
                    "ORDER BY id ASC " +
                    "LIMIT :limit"
    )
    List<GameEventEntity> getPromptEventsAfterIdAsc(
            long campaignId,
            long afterEventId,
            int limit
    );

    @Query(
            "SELECT * FROM game_events " +
                    "WHERE campaign_id = :campaignId " +
                    "AND include_in_prompt = 1 " +
                    "AND id > :afterEventId " +
                    "ORDER BY id DESC " +
                    "LIMIT :limit"
    )
    List<GameEventEntity> getRecentPromptEventsAfterId(
            long campaignId,
            long afterEventId,
            int limit
    );

    @Query("DELETE FROM game_events WHERE campaign_id = :campaignId")
    int deleteForCampaign(long campaignId);

}