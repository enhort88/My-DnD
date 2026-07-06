package com.example.mydnd.game.save;

public class SavedGameSummary {

    private final long campaignId;
    private final String title;
    private final String worldName;
    private final String characterName;
    private final String situationTitle;
    private final long updatedAt;

    public SavedGameSummary(
            long campaignId,
            String title,
            String worldName,
            String characterName,
            String situationTitle,
            long updatedAt
    ) {
        this.campaignId = campaignId;
        this.title = safe(title);
        this.worldName = safe(worldName);
        this.characterName = safe(characterName);
        this.situationTitle = safe(situationTitle);
        this.updatedAt = updatedAt;
    }

    public long getCampaignId() {
        return campaignId;
    }

    public String getTitle() {
        return title;
    }

    public String getWorldName() {
        return worldName;
    }

    public String getCharacterName() {
        return characterName;
    }

    public String getSituationTitle() {
        return situationTitle;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
