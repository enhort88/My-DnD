package com.example.mydnd.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.example.mydnd.db.entity.QuestEntity;

import java.util.List;

@Dao
public interface QuestDao {

    @Insert
    long insert(QuestEntity quest);

    @Query(
            "SELECT * FROM quests WHERE campaign_id = :campaignId "
                    + "AND name_key = :nameKey LIMIT 1"
    )
    QuestEntity findByNameKey(long campaignId, String nameKey);

    @Query(
            "SELECT * FROM quests WHERE campaign_id = :campaignId "
                    + "AND status = 'ACTIVE' ORDER BY updated_at DESC LIMIT :limit"
    )
    List<QuestEntity> getActiveForCampaign(long campaignId, int limit);

    @Query(
            "UPDATE quests SET status = :status, summary = :summary, "
                    + "updated_at = :updatedAt WHERE id = :questId"
    )
    int updateState(
            long questId,
            String status,
            String summary,
            long updatedAt
    );

    @Query("DELETE FROM quests WHERE campaign_id = :campaignId")
    int deleteForCampaign(long campaignId);
}
