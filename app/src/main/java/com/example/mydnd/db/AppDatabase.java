package com.example.mydnd.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.migration.Migration;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.example.mydnd.db.dao.CampaignDao;
import com.example.mydnd.db.dao.GameEventDao;
import com.example.mydnd.db.dao.MemoryFactDao;
import com.example.mydnd.db.dao.SummaryDao;
import com.example.mydnd.db.entity.CampaignEntity;
import com.example.mydnd.db.entity.GameEventEntity;
import com.example.mydnd.db.entity.MemoryFactEntity;
import com.example.mydnd.db.entity.SummaryEntity;

@Database(
        entities = {
                CampaignEntity.class,
                GameEventEntity.class,
                SummaryEntity.class,
                MemoryFactEntity.class
        },
        version = 2,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase instance;

    public abstract CampaignDao campaignDao();

    public abstract GameEventDao gameEventDao();

    public abstract SummaryDao summaryDao();

    public abstract MemoryFactDao memoryFactDao();

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

    public static AppDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    "pocket_dnd.db"
                            )
                            .addMigrations(MIGRATION_1_2)
                            .build();
                }
            }
        }

        return instance;
    }
}