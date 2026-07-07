package com.example.mydnd.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.example.mydnd.db.dao.AbilityDao;
import com.example.mydnd.db.dao.CampaignDao;
import com.example.mydnd.db.dao.CharacterDao;
import com.example.mydnd.db.dao.DirectorActionAuditDao;
import com.example.mydnd.db.dao.EffectDao;
import com.example.mydnd.db.dao.GameEventDao;
import com.example.mydnd.db.dao.InventoryItemDao;
import com.example.mydnd.db.dao.MemoryFactDao;
import com.example.mydnd.db.dao.NpcDao;
import com.example.mydnd.db.dao.QuestDao;
import com.example.mydnd.db.dao.SituationDao;
import com.example.mydnd.db.dao.SummaryDao;
import com.example.mydnd.db.dao.WorldDao;
import com.example.mydnd.db.dao.WorldEventDao;
import com.example.mydnd.db.dao.WorldTimelineDao;
import com.example.mydnd.db.dao.StateChangeDao;
import com.example.mydnd.db.entity.AbilityEntity;
import com.example.mydnd.db.entity.CampaignEntity;
import com.example.mydnd.db.entity.CharacterEntity;
import com.example.mydnd.db.entity.CharacterStartingItemEntity;
import com.example.mydnd.db.entity.DirectorActionAuditEntity;
import com.example.mydnd.db.entity.EffectEntity;
import com.example.mydnd.db.entity.GameEventEntity;
import com.example.mydnd.db.entity.InventoryItemEntity;
import com.example.mydnd.db.entity.MemoryFactEntity;
import com.example.mydnd.db.entity.NpcEntity;
import com.example.mydnd.db.entity.QuestEntity;
import com.example.mydnd.db.entity.SituationEntity;
import com.example.mydnd.db.entity.SummaryEntity;
import com.example.mydnd.db.entity.WorldEntity;
import com.example.mydnd.db.entity.WorldEventEntity;
import com.example.mydnd.db.entity.WorldTimelineEntity;
import com.example.mydnd.db.entity.StateChangeEntity;
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
                SituationEntity.class,
                WorldTimelineEntity.class,
                WorldEventEntity.class,
                StateChangeEntity.class,
                QuestEntity.class,
                AbilityEntity.class,
                EffectEntity.class,
                DirectorActionAuditEntity.class
        },
        version = 8,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase instance;

    public abstract AbilityDao abilityDao();

    public abstract CampaignDao campaignDao();

    public abstract DirectorActionAuditDao directorActionAuditDao();

    public abstract EffectDao effectDao();

    public abstract GameEventDao gameEventDao();

    public abstract SummaryDao summaryDao();

    public abstract MemoryFactDao memoryFactDao();

    public abstract InventoryItemDao inventoryItemDao();

    public abstract WorldDao worldDao();

    public abstract WorldTimelineDao worldTimelineDao();

    public abstract WorldEventDao worldEventDao();

    public abstract CharacterDao characterDao();

    public abstract NpcDao npcDao();

    public abstract QuestDao questDao();

    public abstract SituationDao situationDao();

    public abstract StateChangeDao stateChangeDao();

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

    private static final Migration MIGRATION_4_5 =
            new Migration(4, 5) {
                @Override
                public void migrate(@NonNull SupportSQLiteDatabase database) {

                    database.execSQL(
                            "ALTER TABLE campaigns ADD COLUMN world_timeline_id INTEGER NOT NULL DEFAULT 0"
                    );

                    database.execSQL(
                            "ALTER TABLE npcs ADD COLUMN world_timeline_id INTEGER NOT NULL DEFAULT 0"
                    );

                    database.execSQL(
                            "ALTER TABLE situations ADD COLUMN world_timeline_id INTEGER NOT NULL DEFAULT 0"
                    );

                    database.execSQL(
                            "CREATE TABLE IF NOT EXISTS world_timelines (" +
                                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "world_id INTEGER NOT NULL, " +
                                    "name TEXT NOT NULL, " +
                                    "state_summary TEXT NOT NULL, " +
                                    "created_at INTEGER NOT NULL, " +
                                    "updated_at INTEGER NOT NULL" +
                                    ")"
                    );

                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_world_timelines_world_id " +
                                    "ON world_timelines(world_id)"
                    );

                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_world_timelines_updated_at " +
                                    "ON world_timelines(updated_at)"
                    );

                    database.execSQL(
                            "CREATE TABLE IF NOT EXISTS world_events (" +
                                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "world_timeline_id INTEGER NOT NULL, " +
                                    "text TEXT NOT NULL, " +
                                    "created_at INTEGER NOT NULL" +
                                    ")"
                    );

                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_world_events_world_timeline_id " +
                                    "ON world_events(world_timeline_id)"
                    );

                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_world_events_created_at " +
                                    "ON world_events(created_at)"
                    );

                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_npcs_world_timeline_id " +
                                    "ON npcs(world_timeline_id)"
                    );

                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_situations_world_timeline_id " +
                                    "ON situations(world_timeline_id)"
                    );

                    database.execSQL(
                            "INSERT INTO world_timelines " +
                                    "(world_id, name, state_summary, created_at, updated_at) " +
                                    "SELECT w.id, 'Основная история', substr(w.description, 1, 350), " +
                                    "w.created_at, w.created_at FROM worlds w " +
                                    "WHERE NOT EXISTS (" +
                                    "SELECT 1 FROM world_timelines wt WHERE wt.world_id = w.id" +
                                    ")"
                    );

                    database.execSQL(
                            "UPDATE campaigns SET world_timeline_id = COALESCE((" +
                                    "SELECT wt.id FROM world_timelines wt " +
                                    "WHERE wt.world_id = campaigns.world_id " +
                                    "ORDER BY wt.id ASC LIMIT 1" +
                                    "), 0)"
                    );

                    database.execSQL(
                            "UPDATE npcs SET world_timeline_id = COALESCE((" +
                                    "SELECT c.world_timeline_id FROM campaigns c " +
                                    "WHERE c.id = npcs.campaign_id" +
                                    "), 0)"
                    );

                    database.execSQL(
                            "UPDATE situations SET world_timeline_id = COALESCE((" +
                                    "SELECT c.world_timeline_id FROM campaigns c " +
                                    "WHERE c.id = situations.campaign_id" +
                                    "), 0)"
                    );
                }
            };


    private static final Migration MIGRATION_5_6 =
            new Migration(5, 6) {
                @Override
                public void migrate(@NonNull SupportSQLiteDatabase database) {

                    database.execSQL(
                            "ALTER TABLE world_events ADD COLUMN importance INTEGER NOT NULL DEFAULT 1"
                    );

                    database.execSQL(
                            "ALTER TABLE world_events ADD COLUMN event_type TEXT NOT NULL DEFAULT 'EXTRACTED'"
                    );

                    database.execSQL(
                            "ALTER TABLE world_events ADD COLUMN tone TEXT NOT NULL DEFAULT 'NEUTRAL'"
                    );

                    database.execSQL(
                            "ALTER TABLE world_timelines ADD COLUMN world_turn_count INTEGER NOT NULL DEFAULT 0"
                    );

                    database.execSQL(
                            "ALTER TABLE world_timelines ADD COLUMN last_world_summary_event_id INTEGER NOT NULL DEFAULT 0"
                    );

                    database.execSQL(
                            "ALTER TABLE world_timelines ADD COLUMN last_world_summary_turn INTEGER NOT NULL DEFAULT 0"
                    );

                    database.execSQL(
                            "ALTER TABLE world_timelines ADD COLUMN next_random_event_turn INTEGER NOT NULL DEFAULT 0"
                    );

                    database.execSQL(
                            "ALTER TABLE npcs ADD COLUMN hp INTEGER NOT NULL DEFAULT 10"
                    );

                    database.execSQL(
                            "ALTER TABLE npcs ADD COLUMN max_hp INTEGER NOT NULL DEFAULT 10"
                    );

                    database.execSQL(
                            "ALTER TABLE npcs ADD COLUMN attitude INTEGER NOT NULL DEFAULT 0"
                    );

                    database.execSQL(
                            "ALTER TABLE npcs ADD COLUMN location TEXT NOT NULL DEFAULT ''"
                    );

                    database.execSQL(
                            "ALTER TABLE npcs ADD COLUMN knowledge_summary TEXT NOT NULL DEFAULT ''"
                    );

                    database.execSQL(
                            "ALTER TABLE characters ADD COLUMN strength INTEGER NOT NULL DEFAULT 10"
                    );

                    database.execSQL(
                            "ALTER TABLE characters ADD COLUMN dexterity INTEGER NOT NULL DEFAULT 10"
                    );

                    database.execSQL(
                            "ALTER TABLE characters ADD COLUMN intelligence INTEGER NOT NULL DEFAULT 10"
                    );

                    database.execSQL(
                            "ALTER TABLE characters ADD COLUMN charisma INTEGER NOT NULL DEFAULT 10"
                    );

                    database.execSQL(
                            "ALTER TABLE characters ADD COLUMN hp INTEGER NOT NULL DEFAULT 10"
                    );

                    database.execSQL(
                            "ALTER TABLE characters ADD COLUMN max_hp INTEGER NOT NULL DEFAULT 10"
                    );


                    database.execSQL(
                            "UPDATE world_timelines SET world_turn_count = COALESCE((" +
                                    "SELECT COUNT(*) FROM game_events ge " +
                                    "JOIN campaigns c ON c.id = ge.campaign_id " +
                                    "WHERE c.world_timeline_id = world_timelines.id " +
                                    "AND ge.speaker = 'MASTER'" +
                                    "), 0)"
                    );
                }
            };

    private static final Migration MIGRATION_6_7 =
            new Migration(6, 7) {
                @Override
                public void migrate(@NonNull SupportSQLiteDatabase database) {
                    database.execSQL(
                            "CREATE TABLE IF NOT EXISTS state_changes (" +
                                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "campaign_id INTEGER NOT NULL, " +
                                    "type TEXT NOT NULL, " +
                                    "status TEXT NOT NULL, " +
                                    "title TEXT NOT NULL, " +
                                    "description TEXT NOT NULL, " +
                                    "subject_id INTEGER NOT NULL, " +
                                    "subject_name TEXT NOT NULL, " +
                                    "before_text TEXT NOT NULL, " +
                                    "after_text TEXT NOT NULL, " +
                                    "before_number INTEGER NOT NULL, " +
                                    "after_number INTEGER NOT NULL, " +
                                    "can_undo INTEGER NOT NULL, " +
                                    "created_at INTEGER NOT NULL, " +
                                    "updated_at INTEGER NOT NULL" +
                                    ")"
                    );

                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_state_changes_campaign_id " +
                                    "ON state_changes(campaign_id)"
                    );

                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_state_changes_created_at " +
                                    "ON state_changes(created_at)"
                    );

                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_state_changes_status " +
                                    "ON state_changes(status)"
                    );
                }
            };

    private static final Migration MIGRATION_7_8 =
            new Migration(7, 8) {
                @Override
                public void migrate(@NonNull SupportSQLiteDatabase database) {
                    database.execSQL(
                            "ALTER TABLE campaigns ADD COLUMN current_location TEXT NOT NULL DEFAULT ''"
                    );

                    database.execSQL(
                            "ALTER TABLE characters ADD COLUMN money INTEGER NOT NULL DEFAULT 0"
                    );

                    database.execSQL(
                            "ALTER TABLE npcs ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE'"
                    );

                    database.execSQL(
                            "UPDATE npcs SET status = CASE WHEN active = 1 THEN 'ACTIVE' ELSE 'INACTIVE' END"
                    );

                    database.execSQL(
                            "ALTER TABLE world_events ADD COLUMN name TEXT NOT NULL DEFAULT ''"
                    );

                    database.execSQL(
                            "ALTER TABLE world_events ADD COLUMN name_key TEXT NOT NULL DEFAULT ''"
                    );

                    database.execSQL(
                            "ALTER TABLE world_events ADD COLUMN details TEXT NOT NULL DEFAULT ''"
                    );

                    database.execSQL(
                            "ALTER TABLE world_events ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE'"
                    );

                    database.execSQL(
                            "ALTER TABLE world_events ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0"
                    );

                    database.execSQL(
                            "UPDATE world_events SET updated_at = created_at WHERE updated_at = 0"
                    );

                    database.execSQL(
                            "CREATE TABLE IF NOT EXISTS quests (" +
                                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "campaign_id INTEGER NOT NULL, " +
                                    "name TEXT NOT NULL, " +
                                    "name_key TEXT NOT NULL, " +
                                    "status TEXT NOT NULL, " +
                                    "summary TEXT NOT NULL, " +
                                    "created_at INTEGER NOT NULL, " +
                                    "updated_at INTEGER NOT NULL" +
                                    ")"
                    );

                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_quests_campaign_id ON quests(campaign_id)"
                    );
                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_quests_status ON quests(status)"
                    );
                    database.execSQL(
                            "CREATE UNIQUE INDEX IF NOT EXISTS index_quests_campaign_id_name_key " +
                                    "ON quests(campaign_id, name_key)"
                    );

                    database.execSQL(
                            "CREATE TABLE IF NOT EXISTS abilities (" +
                                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "campaign_id INTEGER NOT NULL, " +
                                    "character_id INTEGER NOT NULL, " +
                                    "name TEXT NOT NULL, " +
                                    "name_key TEXT NOT NULL, " +
                                    "category TEXT NOT NULL, " +
                                    "details TEXT NOT NULL, " +
                                    "active INTEGER NOT NULL, " +
                                    "created_at INTEGER NOT NULL, " +
                                    "updated_at INTEGER NOT NULL" +
                                    ")"
                    );

                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_abilities_campaign_id ON abilities(campaign_id)"
                    );
                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_abilities_character_id ON abilities(character_id)"
                    );
                    database.execSQL(
                            "CREATE UNIQUE INDEX IF NOT EXISTS index_abilities_campaign_id_name_key " +
                                    "ON abilities(campaign_id, name_key)"
                    );

                    database.execSQL(
                            "CREATE TABLE IF NOT EXISTS effects (" +
                                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "campaign_id INTEGER NOT NULL, " +
                                    "name TEXT NOT NULL, " +
                                    "name_key TEXT NOT NULL, " +
                                    "details TEXT NOT NULL, " +
                                    "active INTEGER NOT NULL, " +
                                    "created_at INTEGER NOT NULL, " +
                                    "updated_at INTEGER NOT NULL" +
                                    ")"
                    );

                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_effects_campaign_id ON effects(campaign_id)"
                    );
                    database.execSQL(
                            "CREATE UNIQUE INDEX IF NOT EXISTS index_effects_campaign_id_name_key " +
                                    "ON effects(campaign_id, name_key)"
                    );

                    database.execSQL(
                            "CREATE TABLE IF NOT EXISTS director_action_audit (" +
                                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "campaign_id INTEGER NOT NULL, " +
                                    "type TEXT NOT NULL, " +
                                    "name TEXT NOT NULL, " +
                                    "value TEXT NOT NULL, " +
                                    "details TEXT NOT NULL, " +
                                    "status TEXT NOT NULL, " +
                                    "code TEXT NOT NULL, " +
                                    "state_after TEXT NOT NULL, " +
                                    "state_change_id INTEGER NOT NULL, " +
                                    "created_at INTEGER NOT NULL" +
                                    ")"
                    );

                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_director_action_audit_campaign_id " +
                                    "ON director_action_audit(campaign_id)"
                    );
                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_director_action_audit_created_at " +
                                    "ON director_action_audit(created_at)"
                    );
                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_director_action_audit_status " +
                                    "ON director_action_audit(status)"
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
                                    MIGRATION_3_4,
                                    MIGRATION_4_5,
                                    MIGRATION_5_6,
                                    MIGRATION_6_7,
                                    MIGRATION_7_8
                            )
                            .build();
                }
            }
        }

        return instance;
    }
}
