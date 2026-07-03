package com.example.mydnd.db.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "game_events")
public class GameEventEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "campaign_id")
    public long campaignId;

    @ColumnInfo(name = "speaker")
    public String speaker;

    @ColumnInfo(name = "text")
    public String text;

    @ColumnInfo(name = "include_in_prompt")
    public boolean includeInPrompt;

    @ColumnInfo(name = "created_at")
    public long createdAt;
}