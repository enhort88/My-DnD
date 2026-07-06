package com.example.mydnd.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "character_starting_items",
        indices = {
                @Index(value = "character_id")
        }
)
public class CharacterStartingItemEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "character_id")
    public long characterId;

    @NonNull
    @ColumnInfo(name = "name")
    public String name = "";
}
