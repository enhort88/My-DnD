package com.example.mydnd.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.example.mydnd.db.entity.DirectorActionAuditEntity;

@Dao
public interface DirectorActionAuditDao {

    @Insert
    long insert(DirectorActionAuditEntity audit);

    @Query("DELETE FROM director_action_audit WHERE campaign_id = :campaignId")
    int deleteForCampaign(long campaignId);
}
