package com.example.mydnd.db.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "campaigns")
public class CampaignEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "title")
    public String title;

    @ColumnInfo(name = "world_id", defaultValue = "0")
    public long worldId;

    @ColumnInfo(name = "character_id", defaultValue = "0")
    public long characterId;

    @ColumnInfo(name = "current_situation_id", defaultValue = "0")
    public long currentSituationId;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;
}
