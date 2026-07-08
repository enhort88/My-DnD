package com.example.mydnd.game;

import com.example.mydnd.db.AppDatabase;
import com.example.mydnd.db.entity.CampaignEntity;
import com.example.mydnd.db.entity.CharacterEntity;
import com.example.mydnd.db.entity.NpcEntity;
import com.example.mydnd.db.entity.SituationEntity;
import com.example.mydnd.db.entity.WorldEntity;
import com.example.mydnd.db.entity.WorldEventEntity;
import com.example.mydnd.db.entity.WorldTimelineEntity;

import java.util.List;

public class CampaignPromptRepository {

    private static final int ACTIVE_SITUATIONS_LIMIT = 2;
    private static final int ACTIVE_NPCS_LIMIT = 2;
    private static final int WORLD_EVENTS_LIMIT = 3;

    private final AppDatabase database;

    public CampaignPromptRepository(AppDatabase database) {
        this.database = database;
    }

    public CampaignPromptState build(long campaignId) {
        CampaignEntity campaign = database.campaignDao().getById(campaignId);

        if (campaign == null) {
            return CampaignPromptState.empty();
        }

        WorldEntity world = campaign.worldId > 0L
                ? database.worldDao().getWorld(campaign.worldId)
                : null;

        CharacterEntity character = campaign.characterId > 0L
                ? database.characterDao().getCharacter(campaign.characterId)
                : null;

        WorldTimelineEntity timeline = campaign.worldTimelineId > 0L
                ? database.worldTimelineDao().getById(campaign.worldTimelineId)
                : null;

        List<SituationEntity> situations;
        List<NpcEntity> npcs;
        List<WorldEventEntity> worldEvents;

        if (campaign.worldTimelineId > 0L) {
            situations = database.situationDao().getActiveForContext(
                    campaign.worldTimelineId,
                    campaignId,
                    ACTIVE_SITUATIONS_LIMIT
            );

            npcs = database.npcDao().getActiveForContext(
                    campaign.worldTimelineId,
                    campaignId,
                    ACTIVE_NPCS_LIMIT
            );

            worldEvents = database.worldEventDao().getRecent(
                    campaign.worldTimelineId,
                    WORLD_EVENTS_LIMIT
            );

        } else {
            situations = database.situationDao().getActiveForCampaign(
                    campaignId,
                    ACTIVE_SITUATIONS_LIMIT
            );

            npcs = database.npcDao().getActiveForCampaign(
                    campaignId,
                    ACTIVE_NPCS_LIMIT
            );

            worldEvents = java.util.Collections.emptyList();
        }

        String currentScene = buildCurrentScene(
                campaign,
                situations
        );

        return new CampaignPromptState(
                currentScene,
                buildWorld(world, timeline),
                buildCharacter(character),
                buildWorldEvents(worldEvents),
                buildSituations(situations, campaign.currentSituationId),
                buildNpcs(npcs)
        );
    }

    private String buildCurrentScene(
            CampaignEntity campaign,
            List<SituationEntity> situations
    ) {
        if (campaign.currentSituationId > 0L) {
            SituationEntity current = database.situationDao().getById(
                    campaign.currentSituationId
            );

            if (current != null) {
                return current.title + ". " + current.stateSummary;
            }
        }

        if (situations != null && !situations.isEmpty()) {
            SituationEntity first = situations.get(0);
            return first.title + ". " + first.stateSummary;
        }

        return "Сцена не задана.";
    }

    private String buildWorld(
            WorldEntity world,
            WorldTimelineEntity timeline
    ) {
        if (world == null) {
            return "";
        }

        StringBuilder text = new StringBuilder();
        text.append(world.name);

        if (!world.genre.isEmpty()) {
            text.append("; ").append(world.genre);
        }

        if (timeline != null && !timeline.stateSummary.isEmpty()) {
            text.append("; сейчас: ")
                    .append(limit(timeline.stateSummary, 180));
        }

        return text.toString();
    }

    private String buildCharacter(CharacterEntity character) {
        if (character == null) {
            return "";
        }

        StringBuilder text = new StringBuilder();
        text.append(character.name)
                .append(", ")
                .append(character.race)
                .append(", ")
                .append(character.className);

        CharacterLifeState lifeState = CharacterLifeState.from(
                character.lifeState,
                character.hp
        );

        text.append("; HP ")
                .append(character.hp)
                .append('/')
                .append(character.maxHp);

        if (lifeState != CharacterLifeState.ALIVE) {
            text.append("; СОСТОЯНИЕ ")
                    .append(lifeState.name());

            if (lifeState == CharacterLifeState.DOWNED) {
                text.append("; спасброски ")
                        .append(character.deathSaveSuccesses)
                        .append("/3 успехов, ")
                        .append(character.deathSaveFailures)
                        .append("/3 провалов");
            }
        }

        text.append("; СИЛ ")
                .append(character.strength)
                .append("; ЛОВ ")
                .append(character.dexterity)
                .append("; ИНТ ")
                .append(character.intelligence)
                .append("; ХАР ")
                .append(character.charisma);

        if (!character.personality.isEmpty()) {
            text.append("; ")
                    .append(limit(character.personality, 80));
        }

        return text.toString();
    }

    private String buildWorldEvents(List<WorldEventEntity> events) {
        if (events == null || events.isEmpty()) {
            return "";
        }

        StringBuilder text = new StringBuilder();

        for (WorldEventEntity event : events) {
            if (event == null || event.text == null || event.text.trim().isEmpty()) {
                continue;
            }

            if (text.length() > 0) {
                text.append('\n');
            }

            text.append("- [")
                    .append(event.importance)
                    .append("] ")
                    .append(limit(event.text, 120));
        }

        return text.toString();
    }

    private String buildSituations(
            List<SituationEntity> situations,
            long currentSituationId
    ) {
        if (situations == null || situations.isEmpty()) {
            return "";
        }

        StringBuilder text = new StringBuilder();

        for (SituationEntity situation : situations) {
            if (situation == null || situation.id == currentSituationId) {
                continue;
            }

            if (text.length() > 0) {
                text.append('\n');
            }

            text.append("- ")
                    .append(situation.title)
                    .append(": ")
                    .append(limit(situation.stateSummary, 110));
        }

        return text.toString();
    }

    private String buildNpcs(List<NpcEntity> npcs) {
        if (npcs == null || npcs.isEmpty()) {
            return "";
        }

        StringBuilder text = new StringBuilder();

        for (NpcEntity npc : npcs) {
            if (text.length() > 0) {
                text.append('\n');
            }

            text.append("- ")
                    .append(npc.name)
                    .append(" [HP ")
                    .append(npc.hp)
                    .append('/')
                    .append(npc.maxHp)
                    .append("; отношение ")
                    .append(npc.attitude);

            if (!npc.location.isEmpty()) {
                text.append("; локация: ")
                        .append(limit(npc.location, 60));
            }

            text.append("]: ")
                    .append(limit(npc.stateSummary, 80));

            if (!npc.knowledgeSummary.isEmpty()) {
                text.append("; знает: ")
                        .append(limit(npc.knowledgeSummary, 80));
            }
        }

        return text.toString();
    }

    private String limit(String value, int maxChars) {
        if (value == null) {
            return "";
        }

        String safe = value.trim();

        if (safe.length() <= maxChars) {
            return safe;
        }

        return safe.substring(0, maxChars).trim();
    }
}
