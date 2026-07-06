package com.example.mydnd.draft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CharacterDraft {

    private final String name;
    private final String race;
    private final String className;
    private final String age;
    private final String description;
    private final String background;
    private final String personality;
    private final List<String> startingItems;

    public CharacterDraft(
            String name,
            String race,
            String className,
            String age,
            String description,
            String background,
            String personality,
            List<String> startingItems
    ) {
        this.name = safe(name);
        this.race = safe(race);
        this.className = safe(className);
        this.age = safe(age);
        this.description = safe(description);
        this.background = safe(background);
        this.personality = safe(personality);
        this.startingItems = startingItems == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(startingItems));
    }

    public String getName() {
        return name;
    }

    public String getRace() {
        return race;
    }

    public String getClassName() {
        return className;
    }

    public String getAge() {
        return age;
    }

    public String getDescription() {
        return description;
    }

    public String getBackground() {
        return background;
    }

    public String getPersonality() {
        return personality;
    }

    public List<String> getStartingItems() {
        return startingItems;
    }

    public String toDisplayText() {
        StringBuilder text = new StringBuilder();

        text.append(name);
        text.append("\n\nРаса: ").append(race);
        text.append("\nКласс: ").append(className);

        if (!age.isEmpty()) {
            text.append("\nВозраст: ").append(age);
        }

        text.append("\n\n").append(description);
        text.append("\n\nПредыстория:\n").append(background);
        text.append("\n\nХарактер:\n").append(personality);

        if (!startingItems.isEmpty()) {
            text.append("\n\nСтартовые вещи:");

            for (String item : startingItems) {
                text.append("\n• ").append(item);
            }
        }

        return text.toString().trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
