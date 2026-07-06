package com.example.mydnd.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "npcs",
        indices = {
                @Index(value = "campaign_id"),
                @Index(value = "world_timeline_id")
        }
)
public class NpcEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "campaign_id")
    public long campaignId;

    @ColumnInfo(name = "world_timeline_id", defaultValue = "0")
    public long worldTimelineId;

    @NonNull
    @ColumnInfo(name = "name")
    public String name = "";

    @NonNull
    @ColumnInfo(name = "description")
    public String description = "";

    @NonNull
    @ColumnInfo(name = "state_summary")
    public String stateSummary = "";

    @ColumnInfo(name = "hp", defaultValue = "10")
    public int hp = 10;

    @ColumnInfo(name = "max_hp", defaultValue = "10")
    public int maxHp = 10;

    @ColumnInfo(name = "attitude", defaultValue = "0")
    public int attitude;

    @NonNull
    @ColumnInfo(name = "location", defaultValue = "''")
    public String location = "";

    @NonNull
    @ColumnInfo(name = "knowledge_summary", defaultValue = "''")
    public String knowledgeSummary = "";

    @ColumnInfo(name = "active")
    public boolean active;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;
}
