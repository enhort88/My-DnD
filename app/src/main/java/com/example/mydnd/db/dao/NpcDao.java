package com.example.mydnd.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.example.mydnd.db.entity.NpcEntity;

import java.util.List;

@Dao
public interface NpcDao {

    @Insert
    long insert(NpcEntity npc);

    @Query("SELECT * FROM npcs WHERE id = :npcId LIMIT 1")
    NpcEntity getById(long npcId);

    @Query(
            "SELECT * FROM npcs "
                    + "WHERE campaign_id = :campaignId AND active = 1 "
                    + "ORDER BY updated_at DESC LIMIT :limit"
    )
    List<NpcEntity> getActiveForCampaign(long campaignId, int limit);

    @Query(
            "SELECT * FROM npcs WHERE campaign_id = :campaignId "
                    + "AND LOWER(name) = LOWER(:npcName) LIMIT 1"
    )
    NpcEntity findByName(long campaignId, String npcName);

    @Query(
            "SELECT * FROM npcs "
                    + "WHERE campaign_id = :campaignId "
                    + "AND active = 1 "
                    + "AND LOWER(name) = LOWER(:npcName) "
                    + "LIMIT 1"
    )
    NpcEntity findActiveByName(
            long campaignId,
            String npcName
    );

    @Query(
            "SELECT * FROM npcs "
                    + "WHERE world_timeline_id = :timelineId "
                    + "AND campaign_id = :campaignId "
                    + "AND active = 1 "
                    + "ORDER BY updated_at DESC LIMIT :limit"
    )
    List<NpcEntity> getActiveForContext(
            long timelineId,
            long campaignId,
            int limit
    );

    @Query(
            "UPDATE npcs SET hp = :hp, updated_at = :updatedAt "
                    + "WHERE id = :npcId"
    )
    int updateHp(long npcId, int hp, long updatedAt);

    @Query(
            "UPDATE npcs SET attitude = :attitude, updated_at = :updatedAt "
                    + "WHERE id = :npcId"
    )
    int updateAttitude(long npcId, int attitude, long updatedAt);

    @Query(
            "UPDATE npcs SET location = :location, updated_at = :updatedAt "
                    + "WHERE id = :npcId"
    )
    int updateLocation(long npcId, String location, long updatedAt);

    @Query(
            "UPDATE npcs SET knowledge_summary = :knowledge, updated_at = :updatedAt "
                    + "WHERE id = :npcId"
    )
    int updateKnowledge(long npcId, String knowledge, long updatedAt);

    @Query(
            "UPDATE npcs SET description = :description, state_summary = :stateSummary, "
                    + "status = :status, active = :active, location = :location, "
                    + "updated_at = :updatedAt WHERE id = :npcId"
    )
    int updateDirectorState(
            long npcId,
            String description,
            String stateSummary,
            String status,
            boolean active,
            String location,
            long updatedAt
    );

    @Query(
            "UPDATE npcs SET status = :status, active = :active, "
                    + "state_summary = :stateSummary, updated_at = :updatedAt "
                    + "WHERE id = :npcId"
    )
    int updateStatus(
            long npcId,
            String status,
            boolean active,
            String stateSummary,
            long updatedAt
    );

    @Query(
            "UPDATE npcs SET knowledge_summary = :knowledge, "
                    + "attitude = :attitude, updated_at = :updatedAt "
                    + "WHERE id = :npcId"
    )
    int updateMemoryState(
            long npcId,
            String knowledge,
            int attitude,
            long updatedAt
    );

    @Query("DELETE FROM npcs WHERE campaign_id = :campaignId")
    int deleteForCampaign(long campaignId);
}
