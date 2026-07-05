package com.example.mydnd.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "inventory_items",
        indices = {
                @Index(value = "campaign_id"),
                @Index(
                        value = {
                                "campaign_id",
                                "name_key"
                        },
                        unique = true
                )
        }
)
public class InventoryItemEntity {

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

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;
}
