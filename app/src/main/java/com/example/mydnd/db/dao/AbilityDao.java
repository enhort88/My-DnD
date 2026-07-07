package com.example.mydnd.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.example.mydnd.db.entity.AbilityEntity;

import java.util.List;

@Dao
public interface AbilityDao {

    @Insert
    long insert(AbilityEntity ability);

    @Query(
            "SELECT * FROM abilities WHERE campaign_id = :campaignId "
                    + "AND name_key = :nameKey LIMIT 1"
    )
    AbilityEntity findByNameKey(long campaignId, String nameKey);

    @Query(
            "SELECT * FROM abilities WHERE campaign_id = :campaignId "
                    + "AND active = 1 ORDER BY updated_at DESC LIMIT :limit"
    )
    List<AbilityEntity> getActiveForCampaign(long campaignId, int limit);

    @Query(
            "UPDATE abilities SET category = :category, details = :details, "
                    + "active = 1, updated_at = :updatedAt WHERE id = :abilityId"
    )
    int updateAndActivate(
            long abilityId,
            String category,
            String details,
            long updatedAt
    );

    @Query(
            "UPDATE abilities SET active = 0, updated_at = :updatedAt "
                    + "WHERE id = :abilityId"
    )
    int deactivate(long abilityId, long updatedAt);

    @Query("DELETE FROM abilities WHERE campaign_id = :campaignId")
    int deleteForCampaign(long campaignId);
}
