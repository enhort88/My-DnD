package com.example.mydnd.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.example.mydnd.db.entity.EffectEntity;

import java.util.List;

@Dao
public interface EffectDao {

    @Insert
    long insert(EffectEntity effect);

    @Query(
            "SELECT * FROM effects WHERE campaign_id = :campaignId "
                    + "AND name_key = :nameKey LIMIT 1"
    )
    EffectEntity findByNameKey(long campaignId, String nameKey);

    @Query(
            "SELECT * FROM effects WHERE campaign_id = :campaignId "
                    + "AND active = 1 ORDER BY updated_at DESC LIMIT :limit"
    )
    List<EffectEntity> getActiveForCampaign(long campaignId, int limit);

    @Query(
            "UPDATE effects SET details = :details, active = 1, "
                    + "updated_at = :updatedAt WHERE id = :effectId"
    )
    int updateAndActivate(
            long effectId,
            String details,
            long updatedAt
    );

    @Query(
            "UPDATE effects SET active = 0, updated_at = :updatedAt "
                    + "WHERE id = :effectId"
    )
    int deactivate(long effectId, long updatedAt);

    @Query("DELETE FROM effects WHERE campaign_id = :campaignId")
    int deleteForCampaign(long campaignId);
}
