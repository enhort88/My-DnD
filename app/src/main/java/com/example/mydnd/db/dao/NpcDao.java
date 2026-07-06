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

    @Query(
            "SELECT * FROM npcs "
                    + "WHERE campaign_id = :campaignId AND active = 1 "
                    + "ORDER BY updated_at DESC LIMIT :limit"
    )
    List<NpcEntity> getActiveForCampaign(long campaignId, int limit);

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
}
