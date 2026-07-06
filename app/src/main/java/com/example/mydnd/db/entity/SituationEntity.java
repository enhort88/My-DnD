package com.example.mydnd.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "situations",
        indices = {
                @Index(value = "campaign_id"),
                @Index(value = "status")
        }
)
public class SituationEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "campaign_id")
    public long campaignId;

    @NonNull
    @ColumnInfo(name = "scope")
    public String scope = "WORLD";

    @ColumnInfo(name = "subject_id")
    public long subjectId;

    @NonNull
    @ColumnInfo(name = "title")
    public String title = "";

    @NonNull
    @ColumnInfo(name = "state_summary")
    public String stateSummary = "";

    @NonNull
    @ColumnInfo(name = "status")
    public String status = "ACTIVE";

    @NonNull
    @ColumnInfo(name = "importance")
    public String importance = "MEDIUM";

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;
}
