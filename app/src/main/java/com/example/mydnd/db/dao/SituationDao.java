package com.example.mydnd.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.example.mydnd.db.entity.SituationEntity;

import java.util.List;

@Dao
public interface SituationDao {

    @Insert
    long insert(SituationEntity situation);

    @Query("SELECT * FROM situations WHERE id = :situationId LIMIT 1")
    SituationEntity getById(long situationId);

    @Query(
            "SELECT * FROM situations "
                    + "WHERE campaign_id = :campaignId AND status = 'ACTIVE' "
                    + "ORDER BY updated_at DESC LIMIT :limit"
    )
    List<SituationEntity> getActiveForCampaign(long campaignId, int limit);

    @Query(
            "SELECT * FROM situations "
                    + "WHERE world_timeline_id = :timelineId "
                    + "AND status = 'ACTIVE' "
                    + "AND (scope != 'CAMPAIGN' OR subject_id = :campaignId) "
                    + "ORDER BY updated_at DESC LIMIT :limit"
    )
    List<SituationEntity> getActiveForContext(
            long timelineId,
            long campaignId,
            int limit
    );
}
