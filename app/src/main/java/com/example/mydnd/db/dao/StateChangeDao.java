package com.example.mydnd.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.example.mydnd.db.entity.StateChangeEntity;

import java.util.List;

@Dao
public interface StateChangeDao {

    @Insert
    long insert(StateChangeEntity change);

    @Query("SELECT * FROM state_changes WHERE id = :changeId LIMIT 1")
    StateChangeEntity getById(long changeId);

    @Query(
            "SELECT * FROM state_changes "
                    + "WHERE campaign_id = :campaignId "
                    + "ORDER BY created_at ASC, id ASC"
    )
    List<StateChangeEntity> getForCampaign(long campaignId);

    @Query(
            "SELECT COUNT(*) FROM state_changes "
                    + "WHERE campaign_id = :campaignId "
                    + "AND subject_name = :subjectName "
                    + "AND (created_at > :createdAt "
                    + "OR (created_at = :createdAt AND id > :changeId)) "
                    + "AND status = 'APPLIED'"
    )
    int countLaterAppliedForSubject(
            long campaignId,
            String subjectName,
            long createdAt,
            long changeId
    );

    @Query(
            "UPDATE state_changes SET status = :status, updated_at = :updatedAt "
                    + "WHERE id = :changeId"
    )
    int updateStatus(
            long changeId,
            String status,
            long updatedAt
    );

    @Query(
            "UPDATE state_changes SET status = :status, "
                    + "after_number = :result, after_text = :resultText, "
                    + "updated_at = :updatedAt WHERE id = :changeId"
    )
    int resolveDiceCheck(
            long changeId,
            String status,
            int result,
            String resultText,
            long updatedAt
    );

    @Query("DELETE FROM state_changes WHERE campaign_id = :campaignId")
    int deleteForCampaign(long campaignId);
}
