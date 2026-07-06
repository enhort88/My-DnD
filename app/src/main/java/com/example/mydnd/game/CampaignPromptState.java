package com.example.mydnd.game;

public class CampaignPromptState {

    private final String currentScene;
    private final String world;
    private final String character;
    private final String worldChanges;
    private final String activeSituations;
    private final String activeNpcs;

    public CampaignPromptState(
            String currentScene,
            String world,
            String character,
            String worldChanges,
            String activeSituations,
            String activeNpcs
    ) {
        this.currentScene = safe(currentScene);
        this.world = safe(world);
        this.character = safe(character);
        this.worldChanges = safe(worldChanges);
        this.activeSituations = safe(activeSituations);
        this.activeNpcs = safe(activeNpcs);
    }

    public static CampaignPromptState empty() {
        return new CampaignPromptState(
                "Сцена не задана.",
                "",
                "",
                "",
                "",
                ""
        );
    }

    public String getCurrentScene() {
        return currentScene;
    }

    public String getWorld() {
        return world;
    }

    public String getCharacter() {
        return character;
    }

    public String getWorldChanges() {
        return worldChanges;
    }

    public String getActiveSituations() {
        return activeSituations;
    }

    public String getActiveNpcs() {
        return activeNpcs;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
