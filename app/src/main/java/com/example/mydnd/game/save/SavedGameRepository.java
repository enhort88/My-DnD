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
}
