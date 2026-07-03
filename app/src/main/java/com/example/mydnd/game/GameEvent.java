package com.example.mydnd.game;

public class GameEvent {

    public enum Speaker {
        PLAYER,
        MASTER,
        SYSTEM
    }

    private final Speaker speaker;
    private final String text;
    private final long createdAtMillis;
    private final boolean includeInPrompt;

    public GameEvent(
            Speaker speaker,
            String text,
            long createdAtMillis,
            boolean includeInPrompt
    ) {
        this.speaker = speaker;
        this.text = text;
        this.createdAtMillis = createdAtMillis;
        this.includeInPrompt = includeInPrompt;
    }

    public static GameEvent player(String text) {
        return new GameEvent(
                Speaker.PLAYER,
                text,
                System.currentTimeMillis(),
                true
        );
    }

    public static GameEvent master(String text) {
        return new GameEvent(
                Speaker.MASTER,
                text,
                System.currentTimeMillis(),
                true
        );
    }

    public static GameEvent system(String text) {
        return new GameEvent(
                Speaker.SYSTEM,
                text,
                System.currentTimeMillis(),
                false
        );
    }

    public Speaker getSpeaker() {
        return speaker;
    }

    public String getText() {
        return text;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public boolean isIncludeInPrompt() {
        return includeInPrompt;
    }
}