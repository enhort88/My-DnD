package com.example.mydnd.game.setup;

import com.example.mydnd.db.AppDatabase;
import com.example.mydnd.db.dao.WorldDao;
import com.example.mydnd.db.entity.WorldEntity;
import com.example.mydnd.db.entity.WorldRaceEntity;
import com.example.mydnd.draft.WorldDraft;

public class WorldRepository {

    private final WorldDao worldDao;

    public WorldRepository(AppDatabase database) {
        worldDao = database.worldDao();
    }

    public long createWorld(
            WorldDraft draft,
            String generationPrompt
    ) {
        long now = System.currentTimeMillis();

        WorldEntity entity = new WorldEntity();
        entity.name = draft.getName();
        entity.genre = draft.getGenre();
        entity.description = draft.getDescription();
        entity.rules = draft.getRules();
        entity.generationPrompt = safe(generationPrompt);
        entity.createdAt = now;

        long worldId = worldDao.insertWorld(entity);

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

        return worldId;
    }

    public WorldData getWorldData(long worldId) {
        return new WorldData(
                worldDao.getWorld(worldId),
                worldDao.getRaces(worldId)
        );
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
