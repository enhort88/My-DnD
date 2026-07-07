package com.example.mydnd.game.save;

import com.example.mydnd.db.AppDatabase;
import com.example.mydnd.db.entity.CampaignEntity;
import com.example.mydnd.db.entity.CharacterEntity;
import com.example.mydnd.db.entity.SituationEntity;
import com.example.mydnd.db.entity.WorldEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SavedGameRepository {

    public enum DeleteSavedGameResult {
        DELETED,
        NOT_FOUND
    }

    private final AppDatabase database;

    public SavedGameRepository(AppDatabase database) {
        this.database = database;
    }

    public List<SavedGameSummary> getSavedGames() {
        List<CampaignEntity> campaigns =
                database.campaignDao().getAllCampaignsByRecent();

        if (campaigns == null || campaigns.isEmpty()) {
            return Collections.emptyList();
        }

        List<SavedGameSummary> result = new ArrayList<>();

        for (CampaignEntity campaign : campaigns) {
            WorldEntity world = campaign.worldId > 0L
                    ? database.worldDao().getWorld(campaign.worldId)
                    : null;

            CharacterEntity character = campaign.characterId > 0L
                    ? database.characterDao().getCharacter(campaign.characterId)
                    : null;

            SituationEntity situation = campaign.currentSituationId > 0L
                    ? database.situationDao().getById(campaign.currentSituationId)
                    : null;

            result.add(
                    new SavedGameSummary(
                            campaign.id,
                            campaign.title,
                            world == null ? "" : world.name,
                            character == null ? "" : character.name,
                            situation == null ? "" : situation.title,
                            campaign.updatedAt > 0L
                                    ? campaign.updatedAt
                                    : campaign.createdAt
                    )
            );
        }

        return result;
    }

    /**
     * Deletes only one hero's save/campaign data.
     *
     * Living world data is intentionally preserved:
     * - world / races;
     * - world timeline;
     * - world events.
     *
     * The character is also preserved so it can be reused later.
     */
    public DeleteSavedGameResult deleteSavedGame(long campaignId) {
        CampaignEntity campaign =
                database.campaignDao().getById(campaignId);

        if (campaign == null) {
            return DeleteSavedGameResult.NOT_FOUND;
        }

        database.runInTransaction(() -> {
            database.gameEventDao().deleteForCampaign(campaignId);
            database.inventoryItemDao().deleteForCampaign(campaignId);
            database.summaryDao().deleteForCampaign(campaignId);
            database.memoryFactDao().deleteForCampaign(campaignId);
            database.stateChangeDao().deleteForCampaign(campaignId);

            // These rows are currently created for one campaign even though
            // they also carry world_timeline_id for context lookup.
            database.npcDao().deleteForCampaign(campaignId);
            database.situationDao().deleteForCampaign(campaignId);

            database.campaignDao().deleteById(campaignId);
        });

        return DeleteSavedGameResult.DELETED;
    }
}
