package com.example.mydnd.game.setup;

import com.example.mydnd.db.entity.WorldEventEntity;
import com.example.mydnd.db.entity.WorldTimelineEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LivingWorldData {

    private final WorldData worldData;
    private final WorldTimelineEntity timeline;
    private final List<WorldEventEntity> recentEvents;

    public LivingWorldData(
            WorldData worldData,
            WorldTimelineEntity timeline,
            List<WorldEventEntity> recentEvents
    ) {
        this.worldData = worldData;
        this.timeline = timeline;
        this.recentEvents = recentEvents == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(recentEvents));
    }

    public WorldData getWorldData() {
        return worldData;
    }

    public WorldTimelineEntity getTimeline() {
        return timeline;
    }

    public List<WorldEventEntity> getRecentEvents() {
        return recentEvents;
    }
}
