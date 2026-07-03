package com.example.mydnd.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.example.mydnd.db.entity.CampaignEntity;

@Dao
public interface CampaignDao {

    @Insert
    long insert(CampaignEntity campaign);

    @Query("SELECT * FROM campaigns ORDER BY id DESC LIMIT 1")
    CampaignEntity getLastCampaign();
}