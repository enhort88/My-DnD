package com.example.mydnd.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "npcs",
        indices = {
                @Index(value = "campaign_id")
        }
)
public class NpcEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "campaign_id")
    public long campaignId;

    @NonNull
    @ColumnInfo(name = "name")
    public String name = "";

    @NonNull
    @ColumnInfo(name = "description")
    public String description = "";

    @NonNull
    @ColumnInfo(name = "state_summary")
    public String stateSummary = "";

    @ColumnInfo(name = "active")
    public boolean active;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;
}
