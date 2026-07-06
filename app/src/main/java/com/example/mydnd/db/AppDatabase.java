package com.example.mydnd.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.example.mydnd.db.dao.CampaignDao;
import com.example.mydnd.db.dao.CharacterDao;
import com.example.mydnd.db.dao.GameEventDao;
import com.example.mydnd.db.dao.InventoryItemDao;
import com.example.mydnd.db.dao.MemoryFactDao;
import com.example.mydnd.db.dao.NpcDao;
import com.example.mydnd.db.dao.SituationDao;
import com.example.mydnd.db.dao.SummaryDao;
import com.example.mydnd.db.dao.WorldDao;
import com.example.mydnd.db.entity.CampaignEntity;
import com.example.mydnd.db.entity.CharacterEntity;
import com.example.mydnd.db.entity.CharacterStartingItemEntity;
import com.example.mydnd.db.entity.GameEventEntity;
import com.example.mydnd.db.entity.InventoryItemEntity;
import com.example.mydnd.db.entity.MemoryFactEntity;
import com.example.mydnd.db.entity.NpcEntity;
import com.example.mydnd.db.entity.SituationEntity;
import com.example.mydnd.db.entity.SummaryEntity;
import com.example.mydnd.db.entity.WorldEntity;
import com.example.mydnd.db.entity.WorldRaceEntity;

@Database(
        entities = {
                CampaignEntity.class,
                GameEventEntity.class,
                SummaryEntity.class,
                MemoryFactEntity.class,
                InventoryItemEntity.class,
                WorldEntity.class,
                WorldRaceEntity.class,
                CharacterEntity.class,
                CharacterStartingItemEntity.class,
                NpcEntity.class,
                SituationEntity.class
        },
        version = 4,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase instance;

    public abstract CampaignDao campaignDao();

    public abstract GameEventDao gameEventDao();

    public abstract SummaryDao summaryDao();

    public abstract MemoryFactDao memoryFactDao();

    public abstract InventoryItemDao inventoryItemDao();

    public abstract WorldDao worldDao();

    public abstract CharacterDao characterDao();

    public abstract NpcDao npcDao();

    public abstract SituationDao situationDao();

    private static final Migration MIGRATION_1_2 =
            new Migration(1, 2) {
                @Override
                public void migrate(@NonNull SupportSQLiteDatabase database) {

                    database.execSQL(
                            "CREATE TABLE IF NOT EXISTS summaries (" +
                                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "campaign_id INTEGER NOT NULL, " +
                                    "text TEXT NOT NULL, " +
                                    "from_event_id INTEGER NOT NULL, " +
                                    "to_event_id INTEGER NOT NULL, " +
                                    "created_at INTEGER NOT NULL" +
                                    ")"
                    );

                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_summaries_campaign_id " +
                                    "ON summaries(campaign_id)"
                    );

                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_summaries_created_at " +
                                    "ON summaries(created_at)"
                    );

                    database.execSQL(
                            "CREATE TABLE IF NOT EXISTS memory_facts (" +
                                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "campaign_id INTEGER NOT NULL, " +
                                    "fact_type TEXT NOT NULL, " +
                                    "subject TEXT, " +
                                    "fact_text TEXT NOT NULL, " +
                                    "importance INTEGER NOT NULL, " +
                                    "tags TEXT, " +
                                    "active INTEGER NOT NULL, " +
                                    "created_at INTEGER NOT NULL, " +
                                    "updated_at INTEGER NOT NULL, " +
                                    "last_used_at INTEGER NOT NULL" +
                                    ")"
                    );

                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_memory_facts_campaign_id " +
                                    "ON memory_facts(campaign_id)"
                    );

                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_memory_facts_fact_type " +
                                    "ON memory_facts(fact_type)"
                    );

                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_memory_facts_importance " +
                                    "ON memory_facts(importance)"
                    );
                }
            };

    private static final Migration MIGRATION_2_3 =
            new Migration(2, 3) {
                @Override
                public void migrate(@NonNull SupportSQLiteDatabase database) {

                    database.execSQL(
                            "CREATE TABLE IF NOT EXISTS inventory_items (" +
                                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "campaign_id INTEGER NOT NULL, " +
                                    "name TEXT NOT NULL, " +
                                    "name_key TEXT NOT NULL, " +
                                    "created_at INTEGER NOT NULL, " +
                                    "updated_at INTEGER NOT NULL" +
                                    ")"
                    );

                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_inventory_items_campaign_id " +
                                    "ON inventory_items(campaign_id)"
                    );

                    database.execSQL(
                            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                                    "index_inventory_items_campaign_id_name_key " +
                                    "ON inventory_items(campaign_id, name_key)"
                    );
                }
            };

    private static final Migration MIGRATION_3_4 =
            new Migration(3, 4) {
                @Override
                public void migrate(@NonNull SupportSQLiteDatabase database) {

                    database.execSQL(
                            "ALTER TABLE campaigns ADD COLUMN world_id INTEGER NOT NULL DEFAULT 0"
                    );

                    database.execSQL(
                            "ALTER TABLE campaigns ADD COLUMN character_id INTEGER NOT NULL DEFAULT 0"
                    );

                    database.execSQL(
                            "ALTER TABLE campaigns ADD COLUMN current_situation_id INTEGER NOT NULL DEFAULT 0"
                    );

                    database.execSQL(
                            "CREATE TABLE IF NOT EXISTS worlds (" +
                                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "name TEXT NOT NULL, " +
                                    "genre TEXT NOT NULL, " +
                                    "description TEXT NOT NULL, " +
                                    "rules TEXT NOT NULL, " +
                                    "generation_prompt TEXT NOT NULL, " +
                                    "created_at INTEGER NOT NULL" +
                                    ")"
                    );

                    database.execSQL(
                            "CREATE TABLE IF NOT EXISTS world_races (" +
                                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "world_id INTEGER NOT NULL, " +
                                    "name TEXT NOT NULL, " +
                                    "description TEXT NOT NULL" +
                                    ")"
                    );

                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_world_races_world_id " +
                                    "ON world_races(world_id)"
                    );

                    database.execSQL(
                            "CREATE TABLE IF NOT EXISTS characters (" +
                                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "origin_world_id INTEGER NOT NULL, " +
                                    "name TEXT NOT NULL, " +
                                    "race TEXT NOT NULL, " +
                                    "class_name TEXT NOT NULL, " +
                                    "age TEXT NOT NULL, " +
                                    "description TEXT NOT NULL, " +
                                    "background TEXT NOT NULL, " +
                                    "personality TEXT NOT NULL, " +
                                    "generation_prompt TEXT NOT NULL, " +
                                    "created_at INTEGER NOT NULL" +
                                    ")"
                    );

                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_characters_origin_world_id " +
                                    "ON characters(origin_world_id)"
                    );

                    database.execSQL(
                            "CREATE TABLE IF NOT EXISTS character_starting_items (" +
                                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "character_id INTEGER NOT NULL, " +
                                    "name TEXT NOT NULL" +
                                    ")"
                    );

                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_character_starting_items_character_id " +
                                    "ON character_starting_items(character_id)"
                    );

                    database.execSQL(
                            "CREATE TABLE IF NOT EXISTS npcs (" +
                                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "campaign_id INTEGER NOT NULL, " +
                                    "name TEXT NOT NULL, " +
                                    "description TEXT NOT NULL, " +
                                    "state_summary TEXT NOT NULL, " +
                                    "active INTEGER NOT NULL, " +
                                    "created_at INTEGER NOT NULL, " +
                                    "updated_at INTEGER NOT NULL" +
                                    ")"
                    );

                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_npcs_campaign_id " +
                                    "ON npcs(campaign_id)"
                    );

                    database.execSQL(
                            "CREATE TABLE IF NOT EXISTS situations (" +
                                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "campaign_id INTEGER NOT NULL, " +
                                    "scope TEXT NOT NULL, " +
                                    "subject_id INTEGER NOT NULL, " +
                                    "title TEXT NOT NULL, " +
                                    "state_summary TEXT NOT NULL, " +
                                    "status TEXT NOT NULL, " +
                                    "importance TEXT NOT NULL, " +
                                    "created_at INTEGER NOT NULL, " +
                                    "updated_at INTEGER NOT NULL" +
                                    ")"
                    );

                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_situations_campaign_id " +
                                    "ON situations(campaign_id)"
                    );

                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_situations_status " +
                                    "ON situations(status)"
                    );
                }
            };

    public static AppDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    "pocket_dnd.db"
                            )
                            .addMigrations(
                                    MIGRATION_1_2,
                                    MIGRATION_2_3,
                                    MIGRATION_3_4
                            )
                            .build();
                }
            }
        }

        return instance;
    }
}
