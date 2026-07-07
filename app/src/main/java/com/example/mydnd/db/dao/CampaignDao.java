package com.example.mydnd.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.example.mydnd.db.entity.CampaignEntity;

import java.util.List;

@Dao
public interface CampaignDao {

    @Insert
    long insert(CampaignEntity campaign);

    @Query("SELECT * FROM campaigns ORDER BY updated_at DESC, id DESC LIMIT 1")
    CampaignEntity getLastCampaign();

    @Query("SELECT * FROM campaigns ORDER BY updated_at DESC, id DESC")
    List<CampaignEntity> getAllCampaignsByRecent();

    @Query("SELECT * FROM campaigns WHERE id = :campaignId LIMIT 1")
    CampaignEntity getById(long campaignId);

    @Query(
            "UPDATE campaigns SET current_situation_id = :situationId, updated_at = :updatedAt "
                    + "WHERE id = :campaignId"
    )
    int updateCurrentSituation(
            long campaignId,
            long situationId,
            long updatedAt
    );

    @Query(
            "UPDATE campaigns SET current_location = :location, updated_at = :updatedAt "
                    + "WHERE id = :campaignId"
    )
    int updateCurrentLocation(
            long campaignId,
            String location,
            long updatedAt
    );

    @Query(
            "UPDATE campaigns SET updated_at = :updatedAt "
                    + "WHERE id = :campaignId"
    )
    int touchCampaign(
            long campaignId,
            long updatedAt
    );

    @Query("DELETE FROM campaigns WHERE id = :campaignId")
    int deleteById(long campaignId);

}
