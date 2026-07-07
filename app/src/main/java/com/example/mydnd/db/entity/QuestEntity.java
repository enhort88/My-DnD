package com.example.mydnd.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "quests",
        indices = {
                @Index(value = "campaign_id"),
                @Index(value = "status"),
                @Index(value = {"campaign_id", "name_key"}, unique = true)
        }
)
public class QuestEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "campaign_id")
    public long campaignId;

    @NonNull
    @ColumnInfo(name = "name")
    public String name = "";

    @NonNull
    @ColumnInfo(name = "name_key")
    public String nameKey = "";

    @NonNull
    @ColumnInfo(name = "status")
    public String status = "ACTIVE";

    @NonNull
    @ColumnInfo(name = "summary")
    public String summary = "";

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;
}
