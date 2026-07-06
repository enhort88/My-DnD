package com.example.mydnd.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "world_timelines",
        indices = {
                @Index(value = "world_id"),
                @Index(value = "updated_at")
        }
)
public class WorldTimelineEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "world_id")
    public long worldId;

    @NonNull
    @ColumnInfo(name = "name")
    public String name = "Основная история";

    @NonNull
    @ColumnInfo(name = "state_summary")
    public String stateSummary = "";

    @ColumnInfo(name = "world_turn_count", defaultValue = "0")
    public int worldTurnCount;

    @ColumnInfo(name = "last_world_summary_event_id", defaultValue = "0")
    public long lastWorldSummaryEventId;

    @ColumnInfo(name = "last_world_summary_turn", defaultValue = "0")
    public int lastWorldSummaryTurn;

    @ColumnInfo(name = "next_random_event_turn", defaultValue = "0")
    public int nextRandomEventTurn;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;
}
