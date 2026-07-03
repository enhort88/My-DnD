package com.example.mydnd.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.example.mydnd.db.dao.CampaignDao;
import com.example.mydnd.db.dao.GameEventDao;
import com.example.mydnd.db.entity.CampaignEntity;
import com.example.mydnd.db.entity.GameEventEntity;

@Database(
        entities = {
                CampaignEntity.class,
                GameEventEntity.class
        },
        version = 1,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase instance;

    public abstract CampaignDao campaignDao();

    public abstract GameEventDao gameEventDao();

    public static AppDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            "pocket_dnd.db"
                    ).build();
                }
            }
        }

        return instance;
    }
}