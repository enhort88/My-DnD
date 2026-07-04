package com.example.mydnd.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.example.mydnd.db.entity.MemoryFactEntity;

import java.util.List;

@Dao
public interface MemoryFactDao {

    @Insert
    long insert(MemoryFactEntity fact);

    @Update
    void update(MemoryFactEntity fact);

    @Query("SELECT * FROM memory_facts WHERE campaign_id = :campaignId AND active = 1 ORDER BY importance DESC, updated_at DESC LIMIT :limit")
    List<MemoryFactEntity> getActiveFacts(
            long campaignId,
            int limit
    );

    @Query("SELECT * FROM memory_facts WHERE campaign_id = :campaignId AND active = 1 AND fact_type = :factType  ORDER BY importance DESC, updated_at DESC LIMIT :limit")
    List<MemoryFactEntity> getActiveFactsByType(
            long campaignId,
            String factType,
            int limit
    );

    @Query("UPDATE memory_facts SET last_used_at = :usedAt WHERE id = :factId")
    void markUsed(long factId, long usedAt);

    @Query("UPDATE memory_facts SET active = 0, updated_at = :updatedAt WHERE id = :factId")
    void deactivate(long factId, long updatedAt);

    @Query(
            "SELECT COUNT(*) FROM memory_facts " +
                    "WHERE campaign_id = :campaignId " +
                    "AND active = 1 " +
                    "AND fact_text = :factText"
    )
    int countActiveExactFact(
            long campaignId,
            String factText
    );
}