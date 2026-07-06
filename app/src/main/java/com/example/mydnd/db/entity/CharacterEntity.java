package com.example.mydnd.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "characters",
        indices = {
                @Index(value = "origin_world_id")
        }
)
public class CharacterEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "origin_world_id")
    public long originWorldId;

    @NonNull
    @ColumnInfo(name = "name")
    public String name = "";

    @NonNull
    @ColumnInfo(name = "race")
    public String race = "";

    @NonNull
    @ColumnInfo(name = "class_name")
    public String className = "";

    @NonNull
    @ColumnInfo(name = "age")
    public String age = "";

    @NonNull
    @ColumnInfo(name = "description")
    public String description = "";

    @NonNull
    @ColumnInfo(name = "background")
    public String background = "";

    @NonNull
    @ColumnInfo(name = "personality")
    public String personality = "";

    @ColumnInfo(name = "strength", defaultValue = "10")
    public int strength = 10;

    @ColumnInfo(name = "dexterity", defaultValue = "10")
    public int dexterity = 10;

    @ColumnInfo(name = "intelligence", defaultValue = "10")
    public int intelligence = 10;

    @ColumnInfo(name = "charisma", defaultValue = "10")
    public int charisma = 10;

    @ColumnInfo(name = "hp", defaultValue = "10")
    public int hp = 10;

    @ColumnInfo(name = "max_hp", defaultValue = "10")
    public int maxHp = 10;

    @NonNull
    @ColumnInfo(name = "generation_prompt")
    public String generationPrompt = "";

    @ColumnInfo(name = "created_at")
    public long createdAt;
}
