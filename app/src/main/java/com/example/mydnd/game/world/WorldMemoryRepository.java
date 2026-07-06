package com.example.mydnd.game.world;

import com.example.mydnd.db.AppDatabase;
import com.example.mydnd.db.entity.CampaignEntity;
import com.example.mydnd.db.entity.WorldEventEntity;
import com.example.mydnd.db.entity.WorldTimelineEntity;

public class WorldMemoryRepository {

    private static final int MAX_EVENT_CHARS = 180;
    private static final int MAX_STATE_SUMMARY_CHARS = 600;

    private final AppDatabase database;

    public WorldMemoryRepository(AppDatabase database) {
        this.database = database;
    }

    public boolean rememberForCampaign(
            long campaignId,
            String rawText
    ) {
        String text = normalize(rawText);

        if (text.isEmpty()) {
            return false;
        }

        CampaignEntity campaign = database.campaignDao().getById(campaignId);

        if (campaign == null || campaign.worldTimelineId <= 0L) {
            return false;
        }

        long timelineId = campaign.worldTimelineId;

        if (database.worldEventDao().countExact(timelineId, text) > 0) {
            return false;
        }

        long now = System.currentTimeMillis();

        WorldEventEntity event = new WorldEventEntity();
        event.worldTimelineId = timelineId;
        event.text = text;
        event.createdAt = now;

        database.worldEventDao().insert(event);

        WorldTimelineEntity timeline =
                database.worldTimelineDao().getById(timelineId);

        String previousState = timeline == null
                ? ""
                : normalizeState(timeline.stateSummary);

        String newState = text;

        if (!previousState.isEmpty()) {
            newState += " " + previousState;
        }

        if (newState.length() > MAX_STATE_SUMMARY_CHARS) {
            newState = newState
                    .substring(0, MAX_STATE_SUMMARY_CHARS)
                    .trim();
        }

        database.worldTimelineDao().updateStateSummary(
                timelineId,
                newState,
                now
        );

        return true;
    }

    private String normalizeState(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .trim()
                .replaceAll("\\s+", " ");
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }

        String result = value
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .trim()
                .replaceAll("\\s+", " ");

        if (result.length() <= MAX_EVENT_CHARS) {
            return result;
        }

        return result.substring(0, MAX_EVENT_CHARS).trim();
    }
}
