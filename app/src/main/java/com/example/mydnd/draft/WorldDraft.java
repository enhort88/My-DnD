package com.example.mydnd.draft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WorldDraft {

    private final String name;
    private final String genre;
    private final String description;
    private final String rules;
    private final List<RaceDraft> races;

    public WorldDraft(
            String name,
            String genre,
            String description,
            String rules,
            List<RaceDraft> races
    ) {
        this.name = safe(name);
        this.genre = safe(genre);
        this.description = safe(description);
        this.rules = safe(rules);
        this.races = races == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(races));
    }

    public String getName() {
        return name;
    }

    public String getGenre() {
        return genre;
    }

    public String getDescription() {
        return description;
    }

    public String getRules() {
        return rules;
    }

    public List<RaceDraft> getRaces() {
        return races;
    }

    public String toDisplayText() {
        StringBuilder text = new StringBuilder();

        text.append(name);
        text.append("\n\nЖанр: ").append(genre);
        text.append("\n\n").append(description);
        text.append("\n\nПравила мира:\n").append(rules);

        if (!races.isEmpty()) {
            text.append("\n\nРасы:");

            for (RaceDraft race : races) {
                text.append("\n• ")
                        .append(race.getName());

                if (!race.getDescription().isEmpty()) {
                    text.append(" — ")
                            .append(race.getDescription());
                }
            }
        }

        return text.toString().trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static class RaceDraft {

        private final String name;
        private final String description;

        public RaceDraft(
                String name,
                String description
        ) {
            this.name = safe(name);
            this.description = safe(description);
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }
    }
}
