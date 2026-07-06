package com.example.mydnd.game.setup;

import android.database.Cursor;

import androidx.sqlite.db.SupportSQLiteDatabase;

import com.example.mydnd.db.AppDatabase;
import com.example.mydnd.db.dao.WorldDao;
import com.example.mydnd.db.dao.WorldEventDao;
import com.example.mydnd.db.dao.WorldTimelineDao;
import com.example.mydnd.db.entity.WorldEntity;
import com.example.mydnd.db.entity.WorldRaceEntity;
import com.example.mydnd.db.entity.WorldTimelineEntity;
import com.example.mydnd.draft.WorldDraft;

import java.util.ArrayList;
import java.util.List;

public class WorldRepository {


    public enum DeleteLivingWorldResult {
        DELETED,
        HAS_CAMPAIGNS,
        NOT_FOUND
    }

    private static final int SELECTION_EVENT_LIMIT = 3;

    private final AppDatabase database;
    private final WorldDao worldDao;
    private final WorldTimelineDao timelineDao;
    private final WorldEventDao worldEventDao;

    public WorldRepository(AppDatabase database) {
        this.database = database;
        worldDao = database.worldDao();
        timelineDao = database.worldTimelineDao();
        worldEventDao = database.worldEventDao();
    }

    public LivingWorldData createWorld(
            WorldDraft draft,
            String generationPrompt
    ) {
        final long[] worldIdHolder = {0L};
        final long[] timelineIdHolder = {0L};

        database.runInTransaction(() -> {
            long now = System.currentTimeMillis();

            WorldEntity entity = new WorldEntity();
            entity.name = draft.getName();
            entity.genre = draft.getGenre();
            entity.description = draft.getDescription();
            entity.rules = draft.getRules();
            entity.generationPrompt = safe(generationPrompt);
            entity.createdAt = now;

            long worldId = worldDao.insertWorld(entity);
            worldIdHolder[0] = worldId;

            for (WorldDraft.RaceDraft raceDraft : draft.getRaces()) {
                if (raceDraft.getName().isEmpty()) {
                    continue;
                }

                WorldRaceEntity race = new WorldRaceEntity();
                race.worldId = worldId;
                race.name = raceDraft.getName();
                race.description = raceDraft.getDescription();

                worldDao.insertRace(race);
            }

            WorldTimelineEntity timeline = new WorldTimelineEntity();
            timeline.worldId = worldId;
            timeline.name = "Основная история";
            timeline.stateSummary = limit(draft.getDescription(), 350);
            timeline.createdAt = now;
            timeline.updatedAt = now;

            timelineIdHolder[0] = timelineDao.insert(timeline);
        });

        return getLivingWorldData(timelineIdHolder[0]);
    }

    public WorldData getWorldData(long worldId) {
        return new WorldData(
                worldDao.getWorld(worldId),
                worldDao.getRaces(worldId)
        );
    }

    public LivingWorldData getLivingWorldData(long timelineId) {
        WorldTimelineEntity timeline = timelineDao.getById(timelineId);

        if (timeline == null) {
            return null;
        }

        return new LivingWorldData(
                getWorldData(timeline.worldId),
                timeline,
                worldEventDao.getRecent(
                        timeline.id,
                        SELECTION_EVENT_LIMIT
                )
        );
    }

    public LivingWorldData getPrimaryLivingWorld(long worldId) {
        WorldTimelineEntity timeline = timelineDao.getPrimaryForWorld(worldId);

        return timeline == null
                ? null
                : getLivingWorldData(timeline.id);
    }

    public List<LivingWorldData> getAllLivingWorlds() {
        List<LivingWorldData> result = new ArrayList<>();

        for (WorldTimelineEntity timeline : timelineDao.getAllByRecent()) {
            LivingWorldData data = getLivingWorldData(timeline.id);

            if (data != null
                    && data.getWorldData() != null
                    && data.getWorldData().getWorld() != null) {

                result.add(data);
            }
        }

        return result;
    }

    public DeleteLivingWorldResult deleteLivingWorld(
            long timelineId
    ) {
        if (timelineId <= 0L) {
            return DeleteLivingWorldResult.NOT_FOUND;
        }

        final DeleteLivingWorldResult[] result = {
                DeleteLivingWorldResult.NOT_FOUND
        };

        database.runInTransaction(() -> {
            SupportSQLiteDatabase sqlite =
                    database.getOpenHelper().getWritableDatabase();

            long worldId = 0L;

            try (Cursor cursor = sqlite.query(
                    "SELECT world_id FROM world_timelines WHERE id = ? LIMIT 1",
                    new Object[]{timelineId}
            )) {
                if (cursor.moveToFirst()) {
                    worldId = cursor.getLong(0);
                }
            }

            if (worldId <= 0L) {
                result[0] = DeleteLivingWorldResult.NOT_FOUND;
                return;
            }

            long campaignCount = 0L;

            try (Cursor cursor = sqlite.query(
                    "SELECT COUNT(*) FROM campaigns "
                            + "WHERE world_timeline_id = ? OR "
                            + "(world_timeline_id = 0 AND world_id = ?)",
                    new Object[]{timelineId, worldId}
            )) {
                if (cursor.moveToFirst()) {
                    campaignCount = cursor.getLong(0);
                }
            }

            if (campaignCount > 0L) {
                result[0] = DeleteLivingWorldResult.HAS_CAMPAIGNS;
                return;
            }

            Object[] timelineArgs = new Object[]{timelineId};

            sqlite.execSQL(
                    "DELETE FROM world_events WHERE world_timeline_id = ?",
                    timelineArgs
            );

            sqlite.execSQL(
                    "DELETE FROM npcs WHERE world_timeline_id = ?",
                    timelineArgs
            );

            sqlite.execSQL(
                    "DELETE FROM situations WHERE world_timeline_id = ?",
                    timelineArgs
            );

            sqlite.execSQL(
                    "DELETE FROM world_timelines WHERE id = ?",
                    timelineArgs
            );

            long remainingTimelines = 0L;

            try (Cursor cursor = sqlite.query(
                    "SELECT COUNT(*) FROM world_timelines WHERE world_id = ?",
                    new Object[]{worldId}
            )) {
                if (cursor.moveToFirst()) {
                    remainingTimelines = cursor.getLong(0);
                }
            }

            if (remainingTimelines == 0L) {
                sqlite.execSQL(
                        "DELETE FROM character_starting_items "
                                + "WHERE character_id IN ("
                                + "SELECT id FROM characters "
                                + "WHERE origin_world_id = ? "
                                + "AND id NOT IN ("
                                + "SELECT character_id FROM campaigns "
                                + "WHERE character_id > 0"
                                + ")"
                                + ")",
                        new Object[]{worldId}
                );

                sqlite.execSQL(
                        "DELETE FROM characters "
                                + "WHERE origin_world_id = ? "
                                + "AND id NOT IN ("
                                + "SELECT character_id FROM campaigns "
                                + "WHERE character_id > 0"
                                + ")",
                        new Object[]{worldId}
                );

                sqlite.execSQL(
                        "DELETE FROM world_races WHERE world_id = ?",
                        new Object[]{worldId}
                );

                sqlite.execSQL(
                        "DELETE FROM worlds WHERE id = ?",
                        new Object[]{worldId}
                );
            }

            result[0] = DeleteLivingWorldResult.DELETED;
        });

        return result[0];
    }


    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String limit(String value, int maxChars) {
        String safeValue = safe(value);

        if (safeValue.length() <= maxChars) {
            return safeValue;
        }

        return safeValue.substring(0, maxChars).trim();
    }
}
