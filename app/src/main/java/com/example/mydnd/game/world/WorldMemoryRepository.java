package com.example.mydnd.game.world;

import com.example.mydnd.db.AppDatabase;
import com.example.mydnd.db.entity.CampaignEntity;
import com.example.mydnd.db.entity.WorldEventEntity;

public class WorldMemoryRepository {

    public static final String TYPE_EXTRACTED = "EXTRACTED";
    public static final String TYPE_RANDOM = "RANDOM";

    public static final String TONE_POSITIVE = "POSITIVE";
    public static final String TONE_NEUTRAL = "NEUTRAL";
    public static final String TONE_NEGATIVE = "NEGATIVE";

    private static final int MAX_EVENT_CHARS = 220;

    private final AppDatabase database;

    public WorldMemoryRepository(AppDatabase database) {
        this.database = database;
    }

    public boolean rememberForCampaign(
            long campaignId,
            String rawText
    ) {
        return rememberForCampaign(
                campaignId,
                rawText,
                1
        );
    }

    public boolean rememberForCampaign(
            long campaignId,
            String rawText,
            int importance
    ) {
        return remember(
                campaignId,
                rawText,
                importance,
                TYPE_EXTRACTED,
                TONE_NEUTRAL
        );
    }

    public boolean rememberRandomForCampaign(
            long campaignId,
            String rawText,
            int importance,
            String tone
    ) {
        return remember(
                campaignId,
                rawText,
                importance,
                TYPE_RANDOM,
                normalizeTone(tone)
        );
    }

    private boolean remember(
            long campaignId,
            String rawText,
            int importance,
            String eventType,
            String tone
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
        event.importance = clampImportance(importance);
        event.eventType = eventType;
        event.tone = tone;
        event.createdAt = now;

        database.worldEventDao().insert(event);
        database.worldTimelineDao().touch(timelineId, now);

        return true;
    }

    private int clampImportance(int importance) {
        return Math.max(1, Math.min(3, importance));
    }

    private String normalizeTone(String tone) {
        if (TONE_POSITIVE.equals(tone)
                || TONE_NEGATIVE.equals(tone)) {
            return tone;
        }

        return TONE_NEUTRAL;
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
