package com.example.mydnd.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "world_races",
        indices = {
                @Index(value = "world_id")
        }
)
public class WorldRaceEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "world_id")
    public long worldId;

    @NonNull
    @ColumnInfo(name = "name")
    public String name = "";

    @NonNull
    @ColumnInfo(name = "description")
    public String description = "";
}
