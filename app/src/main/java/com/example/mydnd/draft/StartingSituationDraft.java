package com.example.mydnd.draft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class StartingSituationDraft {

    private final String title;
    private final String stateSummary;
    private final List<NpcDraft> npcs;

    public StartingSituationDraft(
            String title,
            String stateSummary,
            List<NpcDraft> npcs
    ) {
        this.title = safe(title);
        this.stateSummary = safe(stateSummary);
        this.npcs = npcs == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(npcs));
    }

    public String getTitle() {
        return title;
    }

    public String getStateSummary() {
        return stateSummary;
    }

    public List<NpcDraft> getNpcs() {
        return npcs;
    }

    public String toDisplayText() {
        StringBuilder text = new StringBuilder();

        text.append(title);
        text.append("\n\n").append(stateSummary);

        if (!npcs.isEmpty()) {
            text.append("\n\nNPC:");

            for (NpcDraft npc : npcs) {
                text.append("\n• ")
                        .append(npc.getName())
                        .append(" — ")
                        .append(npc.getDescription());

                if (!npc.getStateSummary().isEmpty()) {
                    text.append("\n  Состояние: ")
                            .append(npc.getStateSummary());
                }
            }
        }

        return text.toString().trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static class NpcDraft {

        private final String name;
        private final String description;
        private final String stateSummary;

        public NpcDraft(
                String name,
                String description,
                String stateSummary
        ) {
            this.name = safe(name);
            this.description = safe(description);
            this.stateSummary = safe(stateSummary);
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public String getStateSummary() {
            return stateSummary;
        }
    }
}
