package com.example.mydnd.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.example.mydnd.db.entity.WorldTimelineEntity;

import java.util.List;

@Dao
public interface WorldTimelineDao {

    @Insert
    long insert(WorldTimelineEntity timeline);

    @Query("SELECT * FROM world_timelines WHERE id = :timelineId LIMIT 1")
    WorldTimelineEntity getById(long timelineId);

    @Query(
            "SELECT * FROM world_timelines "
                    + "WHERE world_id = :worldId "
                    + "ORDER BY id ASC LIMIT 1"
    )
    WorldTimelineEntity getPrimaryForWorld(long worldId);

    @Query("SELECT * FROM world_timelines ORDER BY updated_at DESC, id DESC")
    List<WorldTimelineEntity> getAllByRecent();

    @Query(
            "UPDATE world_timelines SET updated_at = :updatedAt "
                    + "WHERE id = :timelineId"
    )
    int touch(long timelineId, long updatedAt);

    @Query(
            "UPDATE world_timelines SET "
                    + "world_turn_count = world_turn_count + 1, "
                    + "updated_at = :updatedAt "
                    + "WHERE id = :timelineId"
    )
    int incrementWorldTurn(long timelineId, long updatedAt);

    @Query(
            "UPDATE world_timelines SET state_summary = :stateSummary, updated_at = :updatedAt "
                    + "WHERE id = :timelineId"
    )
    int updateStateSummary(
            long timelineId,
            String stateSummary,
            long updatedAt
    );

    @Query(
            "UPDATE world_timelines SET "
                    + "state_summary = :stateSummary, "
                    + "last_world_summary_event_id = :lastEventId, "
                    + "last_world_summary_turn = :masterTurn, "
                    + "updated_at = :updatedAt "
                    + "WHERE id = :timelineId"
    )
    int updateWorldSummary(
            long timelineId,
            String stateSummary,
            long lastEventId,
            int masterTurn,
            long updatedAt
    );

    @Query(
            "UPDATE world_timelines SET "
                    + "last_world_summary_turn = :masterTurn, "
                    + "updated_at = :updatedAt "
                    + "WHERE id = :timelineId"
    )
    int markWorldSummaryCheckpoint(
            long timelineId,
            int masterTurn,
            long updatedAt
    );

    @Query(
            "UPDATE world_timelines SET "
                    + "next_random_event_turn = :nextTurn, "
                    + "updated_at = :updatedAt "
                    + "WHERE id = :timelineId"
    )
    int updateNextRandomEventTurn(
            long timelineId,
            int nextTurn,
            long updatedAt
    );
}
