package com.example.mydnd.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "worlds")
public class WorldEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    @ColumnInfo(name = "name")
    public String name = "";

    @NonNull
    @ColumnInfo(name = "genre")
    public String genre = "";

    @NonNull
    @ColumnInfo(name = "description")
    public String description = "";

    @NonNull
    @ColumnInfo(name = "rules")
    public String rules = "";

    @NonNull
    @ColumnInfo(name = "generation_prompt")
    public String generationPrompt = "";

    @ColumnInfo(name = "created_at")
    public long createdAt;
}
