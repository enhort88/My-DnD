package com.example.mydnd.game;

import com.example.mydnd.db.AppDatabase;
import com.example.mydnd.db.entity.CampaignEntity;
import com.example.mydnd.db.entity.CharacterEntity;
import com.example.mydnd.db.entity.NpcEntity;
import com.example.mydnd.db.entity.SituationEntity;
import com.example.mydnd.db.entity.WorldEntity;

import java.util.List;

public class CampaignPromptRepository {

    private static final int ACTIVE_SITUATIONS_LIMIT = 3;
    private static final int ACTIVE_NPCS_LIMIT = 4;

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

        List<SituationEntity> situations =
                database.situationDao().getActiveForCampaign(
                        campaignId,
                        ACTIVE_SITUATIONS_LIMIT
                );

        List<NpcEntity> npcs =
                database.npcDao().getActiveForCampaign(
                        campaignId,
                        ACTIVE_NPCS_LIMIT
                );

        String currentScene = buildCurrentScene(
                campaign,
                situations
        );

        return new CampaignPromptState(
                currentScene,
                buildWorld(world),
                buildCharacter(character),
                buildSituations(situations),
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

    private String buildWorld(WorldEntity world) {
        if (world == null) {
            return "";
        }

        StringBuilder text = new StringBuilder();
        text.append(world.name);

        if (!world.genre.isEmpty()) {
            text.append("; жанр: ").append(world.genre);
        }

        if (!world.rules.isEmpty()) {
            text.append("; правила: ").append(world.rules);
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

        if (!character.personality.isEmpty()) {
            text.append("; характер: ")
                    .append(character.personality);
        }

        return text.toString();
    }

    private String buildSituations(List<SituationEntity> situations) {
        if (situations == null || situations.isEmpty()) {
            return "";
        }

        StringBuilder text = new StringBuilder();

        for (SituationEntity situation : situations) {
            if (text.length() > 0) {
                text.append('\n');
            }

            text.append("- ")
                    .append(situation.title)
                    .append(": ")
                    .append(situation.stateSummary);
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
                    .append(": ")
                    .append(npc.stateSummary);
        }

        return text.toString();
    }
}
