package com.example.mydnd.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "state_changes",
        indices = {
                @Index(value = "campaign_id"),
                @Index(value = "created_at"),
                @Index(value = "status")
        }
)
public class StateChangeEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "campaign_id")
    public long campaignId;

    @NonNull
    @ColumnInfo(name = "type")
    public String type = "";

    @NonNull
    @ColumnInfo(name = "status")
    public String status = "APPLIED";

    @NonNull
    @ColumnInfo(name = "title")
    public String title = "";

    @NonNull
    @ColumnInfo(name = "description")
    public String description = "";

    @ColumnInfo(name = "subject_id")
    public long subjectId;

    @NonNull
    @ColumnInfo(name = "subject_name")
    public String subjectName = "";

    @NonNull
    @ColumnInfo(name = "before_text")
    public String beforeText = "";

    @NonNull
    @ColumnInfo(name = "after_text")
    public String afterText = "";

    @ColumnInfo(name = "before_number")
    public int beforeNumber;

    @ColumnInfo(name = "after_number")
    public int afterNumber;

    @ColumnInfo(name = "can_undo")
    public boolean canUndo;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;
}
