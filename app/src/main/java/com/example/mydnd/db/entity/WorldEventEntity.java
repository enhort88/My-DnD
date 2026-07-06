package com.example.mydnd.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "world_events",
        indices = {
                @Index(value = "world_timeline_id"),
                @Index(value = "created_at")
        }
)
public class WorldEventEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "world_timeline_id")
    public long worldTimelineId;

    @NonNull
    @ColumnInfo(name = "text")
    public String text = "";

    @ColumnInfo(name = "importance", defaultValue = "1")
    public int importance = 1;

    @NonNull
    @ColumnInfo(name = "event_type", defaultValue = "'EXTRACTED'")
    public String eventType = "EXTRACTED";

    @NonNull
    @ColumnInfo(name = "tone", defaultValue = "'NEUTRAL'")
    public String tone = "NEUTRAL";

    @ColumnInfo(name = "created_at")
    public long createdAt;
}
