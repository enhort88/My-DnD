package com.example.mydnd.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "summaries",
        indices = {
                @Index(value = "campaign_id"),
                @Index(value = "created_at")
        }
)
public class SummaryEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "campaign_id")
    public long campaignId;

    @NonNull
    @ColumnInfo(name = "text")
    public String text = "";

    @ColumnInfo(name = "from_event_id")
    public long fromEventId;

    @ColumnInfo(name = "to_event_id")
    public long toEventId;

    @ColumnInfo(name = "created_at")
    public long createdAt;
}