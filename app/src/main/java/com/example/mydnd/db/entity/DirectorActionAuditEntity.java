package com.example.mydnd.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "director_action_audit",
        indices = {
                @Index(value = "campaign_id"),
                @Index(value = "created_at"),
                @Index(value = "status")
        }
)
public class DirectorActionAuditEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "campaign_id")
    public long campaignId;

    @NonNull
    @ColumnInfo(name = "type")
    public String type = "";

    @NonNull
    @ColumnInfo(name = "name")
    public String name = "";

    @NonNull
    @ColumnInfo(name = "value")
    public String value = "";

    @NonNull
    @ColumnInfo(name = "details")
    public String details = "";

    @NonNull
    @ColumnInfo(name = "status")
    public String status = "";

    @NonNull
    @ColumnInfo(name = "code")
    public String code = "";

    @NonNull
    @ColumnInfo(name = "state_after")
    public String stateAfter = "";

    @ColumnInfo(name = "state_change_id")
    public long stateChangeId;

    @ColumnInfo(name = "created_at")
    public long createdAt;
}
