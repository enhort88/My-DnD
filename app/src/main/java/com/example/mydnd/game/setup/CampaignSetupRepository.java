package com.example.mydnd.game.setup;

import com.example.mydnd.db.AppDatabase;
import com.example.mydnd.db.entity.CampaignEntity;
import com.example.mydnd.db.entity.CharacterStartingItemEntity;
import com.example.mydnd.db.entity.GameEventEntity;
import com.example.mydnd.db.entity.InventoryItemEntity;
import com.example.mydnd.db.entity.NpcEntity;
import com.example.mydnd.db.entity.SituationEntity;
import com.example.mydnd.db.entity.WorldEntity;
import com.example.mydnd.draft.StartingSituationDraft;

import java.util.Locale;

public class CampaignSetupRepository {

    private final AppDatabase database;

    public CampaignSetupRepository(AppDatabase database) {
        this.database = database;
    }

    public long createCampaign(
            LivingWorldData livingWorld,
            CharacterData characterData,
            StartingSituationDraft situationDraft
    ) {
        final long[] result = {0L};

        database.runInTransaction(() -> {
            long now = System.currentTimeMillis();

            WorldEntity world = livingWorld.getWorldData().getWorld();
            long timelineId = livingWorld.getTimeline().id;

            CampaignEntity campaign = new CampaignEntity();
            campaign.title = characterData.getCharacter().name
                    + " — "
                    + world.name;
            campaign.worldId = world.id;
            campaign.worldTimelineId = timelineId;
            campaign.characterId = characterData.getCharacter().id;
            campaign.currentSituationId = 0L;
            campaign.createdAt = now;
            campaign.updatedAt = now;

            long campaignId = database.campaignDao().insert(campaign);

            SituationEntity situation = new SituationEntity();
            situation.campaignId = campaignId;
            situation.worldTimelineId = timelineId;
            situation.scope = "CAMPAIGN";
            situation.subjectId = campaignId;
            situation.title = situationDraft.getTitle();
            situation.stateSummary = situationDraft.getStateSummary();
            situation.status = "ACTIVE";
            situation.importance = "HIGH";
            situation.createdAt = now;
            situation.updatedAt = now;

            long situationId = database.situationDao().insert(situation);

            database.campaignDao().updateCurrentSituation(
                    campaignId,
                    situationId,
                    now
            );

            for (StartingSituationDraft.NpcDraft npcDraft : situationDraft.getNpcs()) {
                if (npcDraft.getName().isEmpty()) {
                    continue;
                }

                NpcEntity npc = new NpcEntity();
                npc.campaignId = campaignId;
                npc.worldTimelineId = timelineId;
                npc.name = npcDraft.getName();
                npc.description = npcDraft.getDescription();
                npc.stateSummary = npcDraft.getStateSummary();
                npc.hp = 10;
                npc.maxHp = 10;
                npc.attitude = 0;
                npc.location = situationDraft.getTitle();
                npc.knowledgeSummary = "";
                npc.active = true;
                npc.createdAt = now;
                npc.updatedAt = now;

                database.npcDao().insert(npc);
            }

            for (CharacterStartingItemEntity startingItem : characterData.getStartingItems()) {
                String itemName = normalizeDisplayName(startingItem.name);

                if (itemName.isEmpty()) {
                    continue;
                }

                InventoryItemEntity inventoryItem = new InventoryItemEntity();
                inventoryItem.campaignId = campaignId;
                inventoryItem.name = itemName;
                inventoryItem.nameKey = normalizeNameKey(itemName);
                inventoryItem.createdAt = now;
                inventoryItem.updatedAt = now;

                database.inventoryItemDao().insert(inventoryItem);
            }

            GameEventEntity firstScene = new GameEventEntity();
            firstScene.campaignId = campaignId;
            firstScene.speaker = "MASTER";
            firstScene.text = situationDraft.getStateSummary();
            firstScene.includeInPrompt = true;
            firstScene.createdAt = now;

            database.gameEventDao().insert(firstScene);

            database.worldTimelineDao().touch(
                    timelineId,
                    now
            );

            result[0] = campaignId;
        });

        return result[0];
    }

    private String normalizeDisplayName(String value) {
        if (value == null) {
            return "";
        }

        return value
                .trim()
                .replaceAll("\\s+", " ");
    }

    private String normalizeNameKey(String value) {
        return normalizeDisplayName(value)
                .toLowerCase(Locale.ROOT);
    }
}
