package com.example.mydnd.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.example.mydnd.db.entity.SummaryEntity;

import java.util.List;

@Dao
public interface SummaryDao {

    @Insert
    long insert(SummaryEntity summary);

    @Query("SELECT * FROM summaries WHERE campaign_id = :campaignId ORDER BY id DESC  LIMIT 1")
    SummaryEntity getLatestForCampaign(long campaignId);

    @Query("SELECT * FROM summaries WHERE campaign_id = :campaignId ORDER BY id ASC")
    List<SummaryEntity> getForCampaign(long campaignId);

    @Query("DELETE FROM summaries WHERE campaign_id = :campaignId")
    int deleteForCampaign(long campaignId);

}
