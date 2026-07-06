package com.example.mydnd.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.example.mydnd.db.entity.WorldEventEntity;

import java.util.List;

@Dao
public interface WorldEventDao {

    @Insert
    long insert(WorldEventEntity event);

    @Query(
            "SELECT * FROM world_events "
                    + "WHERE world_timeline_id = :timelineId "
                    + "ORDER BY created_at DESC, id DESC LIMIT :limit"
    )
    List<WorldEventEntity> getRecent(long timelineId, int limit);

    @Query(
            "SELECT COUNT(*) FROM world_events "
                    + "WHERE world_timeline_id = :timelineId AND text = :text"
    )
    int countExact(long timelineId, String text);
}
