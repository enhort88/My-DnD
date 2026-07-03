package com.example.mydnd.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "memory_facts",
        indices = {
                @Index(value = "campaign_id"),
                @Index(value = "fact_type"),
                @Index(value = "importance")
        }
)
public class MemoryFactEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "campaign_id")
    public long campaignId;

    @NonNull
    @ColumnInfo(name = "fact_type")
    public String factType = "GENERAL";

    @ColumnInfo(name = "subject")
    public String subject;

    @NonNull
    @ColumnInfo(name = "fact_text")
    public String factText = "";

    @ColumnInfo(name = "importance")
    public int importance;

    @ColumnInfo(name = "tags")
    public String tags;

    @ColumnInfo(name = "active")
    public boolean active;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;

    @ColumnInfo(name = "last_used_at")
    public long lastUsedAt;
}