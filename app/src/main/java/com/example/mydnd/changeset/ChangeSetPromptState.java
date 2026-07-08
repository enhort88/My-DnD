package com.example.mydnd.changeset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compact canonical state used by the narrative-first pipeline.
 * Existing entities are exposed to the model by short stable aliases (I1, N1, ...).
 */
public final class ChangeSetPromptState {

    public static final class Ref {
        private final String alias;
        private final long id;
        private final String name;
        private final String meta;

        public Ref(String alias, long id, String name, String meta) {
            this.alias = safe(alias);
            this.id = id;
            this.name = safe(name);
            this.meta = safe(meta);
        }

        public String getAlias() { return alias; }
        public long getId() { return id; }
        public String getName() { return name; }
        public String getMeta() { return meta; }

        public String toPromptLine() {
            if (meta.isEmpty()) {
                return alias + "=" + name;
            }
            return alias + "=" + name + "|" + meta;
        }
    }

    private final String characterName;
    private final String location;
    private final String health;
    private final String money;
    private final List<Ref> inventory;
    private final List<Ref> npcs;
    private final List<Ref> quests;
    private final List<Ref> worldEvents;
    private final List<Ref> abilities;
    private final List<Ref> effects;
    private final Map<String, Ref> refsByAlias;

    private ChangeSetPromptState(Builder builder) {
        characterName = safe(builder.characterName);
        location = safe(builder.location);
        health = safe(builder.health);
        money = safe(builder.money);
        inventory = immutable(builder.inventory);
        npcs = immutable(builder.npcs);
        quests = immutable(builder.quests);
        worldEvents = immutable(builder.worldEvents);
        abilities = immutable(builder.abilities);
        effects = immutable(builder.effects);

        Map<String, Ref> aliases = new LinkedHashMap<>();
        index(aliases, inventory);
        index(aliases, npcs);
        index(aliases, quests);
        index(aliases, worldEvents);
        index(aliases, abilities);
        index(aliases, effects);
        refsByAlias = Collections.unmodifiableMap(aliases);
    }

    public static Builder builder() { return new Builder(); }
    public static ChangeSetPromptState empty() { return builder().build(); }

    public String getCharacterName() { return characterName; }
    public String getLocation() { return location; }
    public String getHealth() { return health; }
    public String getMoney() { return money; }
    public List<Ref> getInventory() { return inventory; }
    public List<Ref> getNpcs() { return npcs; }
    public List<Ref> getQuests() { return quests; }
    public List<Ref> getWorldEvents() { return worldEvents; }
    public List<Ref> getAbilities() { return abilities; }
    public List<Ref> getEffects() { return effects; }

    public Ref findRef(String alias) {
        return refsByAlias.get(normalizeAlias(alias));
    }

    public Ref findRef(String alias, char expectedPrefix) {
        Ref ref = findRef(alias);
        if (ref == null) {
            return null;
        }
        String normalized = normalizeAlias(alias);
        return !normalized.isEmpty() && normalized.charAt(0) == Character.toUpperCase(expectedPrefix)
                ? ref
                : null;
    }

    public boolean isActorName(String value) {
        String candidate = safe(value);
        if (candidate.isEmpty()) {
            return false;
        }
        if (!characterName.isEmpty() && characterName.equalsIgnoreCase(candidate)) {
            return true;
        }
        for (Ref ref : npcs) {
            if (ref.getName().equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static void index(Map<String, Ref> target, List<Ref> refs) {
        for (Ref ref : refs) {
            if (ref == null || ref.getAlias().isEmpty()) {
                continue;
            }
            target.put(normalizeAlias(ref.getAlias()), ref);
        }
    }

    private static String normalizeAlias(String value) {
        return safe(value).toUpperCase(Locale.ROOT);
    }

    private static List<Ref> immutable(List<Ref> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class Builder {
        private String characterName;
        private String location;
        private String health;
        private String money;
        private List<Ref> inventory = Collections.emptyList();
        private List<Ref> npcs = Collections.emptyList();
        private List<Ref> quests = Collections.emptyList();
        private List<Ref> worldEvents = Collections.emptyList();
        private List<Ref> abilities = Collections.emptyList();
        private List<Ref> effects = Collections.emptyList();

        public Builder characterName(String value) { characterName = value; return this; }
        public Builder location(String value) { location = value; return this; }
        public Builder health(String value) { health = value; return this; }
        public Builder money(String value) { money = value; return this; }
        public Builder inventory(List<Ref> value) { inventory = value; return this; }
        public Builder npcs(List<Ref> value) { npcs = value; return this; }
        public Builder quests(List<Ref> value) { quests = value; return this; }
        public Builder worldEvents(List<Ref> value) { worldEvents = value; return this; }
        public Builder abilities(List<Ref> value) { abilities = value; return this; }
        public Builder effects(List<Ref> value) { effects = value; return this; }

        public ChangeSetPromptState build() {
            return new ChangeSetPromptState(this);
        }
    }
}
