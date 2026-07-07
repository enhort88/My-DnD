package com.example.mydnd.director;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Only canonical, relevant state that is worth putting into the current turn prompt. */
public final class DirectorPromptState {

    private final String location;
    private final String health;
    private final String money;
    private final List<String> inventory;
    private final List<String> activeNpcs;
    private final List<String> activeQuests;
    private final List<String> worldEvents;
    private final List<String> abilities;
    private final List<String> effects;
    private final String hint;

    private DirectorPromptState(Builder builder) {
        this.location = safe(builder.location);
        this.health = safe(builder.health);
        this.money = safe(builder.money);
        this.inventory = immutable(builder.inventory);
        this.activeNpcs = immutable(builder.activeNpcs);
        this.activeQuests = immutable(builder.activeQuests);
        this.worldEvents = immutable(builder.worldEvents);
        this.abilities = immutable(builder.abilities);
        this.effects = immutable(builder.effects);
        this.hint = safe(builder.hint).isEmpty() ? "NONE" : safe(builder.hint);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static DirectorPromptState empty() {
        return builder().build();
    }

    public String getLocation() { return location; }
    public String getHealth() { return health; }
    public String getMoney() { return money; }
    public List<String> getInventory() { return inventory; }
    public List<String> getActiveNpcs() { return activeNpcs; }
    public List<String> getActiveQuests() { return activeQuests; }
    public List<String> getWorldEvents() { return worldEvents; }
    public List<String> getAbilities() { return abilities; }
    public List<String> getEffects() { return effects; }
    public String getHint() { return hint; }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> immutable(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    public static final class Builder {
        private String location;
        private String health;
        private String money;
        private List<String> inventory = Collections.emptyList();
        private List<String> activeNpcs = Collections.emptyList();
        private List<String> activeQuests = Collections.emptyList();
        private List<String> worldEvents = Collections.emptyList();
        private List<String> abilities = Collections.emptyList();
        private List<String> effects = Collections.emptyList();
        private String hint = "NONE";

        public Builder location(String value) { this.location = value; return this; }
        public Builder health(String value) { this.health = value; return this; }
        public Builder money(String value) { this.money = value; return this; }
        public Builder inventory(List<String> value) { this.inventory = value; return this; }
        public Builder activeNpcs(List<String> value) { this.activeNpcs = value; return this; }
        public Builder activeQuests(List<String> value) { this.activeQuests = value; return this; }
        public Builder worldEvents(List<String> value) { this.worldEvents = value; return this; }
        public Builder abilities(List<String> value) { this.abilities = value; return this; }
        public Builder effects(List<String> value) { this.effects = value; return this; }
        public Builder hint(String value) { this.hint = value; return this; }

        public DirectorPromptState build() {
            return new DirectorPromptState(this);
        }
    }
}
