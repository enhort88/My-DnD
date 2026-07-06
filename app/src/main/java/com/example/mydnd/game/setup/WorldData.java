package com.example.mydnd.game.setup;

import com.example.mydnd.db.entity.WorldEntity;
import com.example.mydnd.db.entity.WorldRaceEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WorldData {

    private final WorldEntity world;
    private final List<WorldRaceEntity> races;

    public WorldData(
            WorldEntity world,
            List<WorldRaceEntity> races
    ) {
        this.world = world;
        this.races = races == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(races));
    }

    public WorldEntity getWorld() {
        return world;
    }

    public List<WorldRaceEntity> getRaces() {
        return races;
    }
}
