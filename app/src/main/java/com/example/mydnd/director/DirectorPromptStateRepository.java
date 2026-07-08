package com.example.mydnd.director;

import com.example.mydnd.db.AppDatabase;
import com.example.mydnd.db.entity.AbilityEntity;
import com.example.mydnd.db.entity.CampaignEntity;
import com.example.mydnd.db.entity.CharacterEntity;
import com.example.mydnd.db.entity.EffectEntity;
import com.example.mydnd.db.entity.NpcEntity;
import com.example.mydnd.db.entity.QuestEntity;
import com.example.mydnd.db.entity.WorldEventEntity;
import com.example.mydnd.game.CharacterLifeState;
import com.example.mydnd.game.InventoryRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Builds the small canonical state block used by the Director before each turn. */
public final class DirectorPromptStateRepository {

    private static final int NPC_LIMIT = 8;
    private static final int QUEST_LIMIT = 8;
    private static final int WORLD_LIMIT = 8;
    private static final int ABILITY_LIMIT = 12;
    private static final int EFFECT_LIMIT = 8;

    private final AppDatabase database;
    private final InventoryRepository inventoryRepository;

    public DirectorPromptStateRepository(
            AppDatabase database,
            InventoryRepository inventoryRepository
    ) {
        this.database = database;
        this.inventoryRepository = inventoryRepository;
    }

    public DirectorPromptState build(long campaignId, String hint) {
        CampaignEntity campaign = database.campaignDao().getById(campaignId);
        if (campaign == null) {
            return DirectorPromptState.builder().hint(hint).build();
        }

        CharacterEntity character = campaign.characterId > 0L
                ? database.characterDao().getCharacter(campaign.characterId)
                : null;

        List<NpcEntity> npcs = safeList(database.npcDao().getActiveForCampaign(campaignId, NPC_LIMIT));
        List<QuestEntity> quests = safeList(database.questDao().getActiveForCampaign(campaignId, QUEST_LIMIT));
        List<AbilityEntity> abilities = safeList(database.abilityDao().getActiveForCampaign(campaignId, ABILITY_LIMIT));
        List<EffectEntity> effects = safeList(database.effectDao().getActiveForCampaign(campaignId, EFFECT_LIMIT));
        List<WorldEventEntity> worldEvents = campaign.worldTimelineId > 0L
                ? safeList(database.worldEventDao().getActive(campaign.worldTimelineId, WORLD_LIMIT))
                : Collections.emptyList();

        List<String> inventory = inventoryRepository.getItemNames(campaignId);

        return DirectorPromptState.builder()
                .location(clean(campaign.currentLocation))
                .health(character == null ? "UNKNOWN" : formatHealth(character))
                .money(character == null ? "UNKNOWN" : String.valueOf(character.money))
                .inventory(inventory)
                .activeNpcs(formatNpcs(npcs))
                .activeQuests(formatQuests(quests))
                .worldEvents(formatWorldEvents(worldEvents, campaign.title))
                .abilities(formatAbilities(abilities))
                .effects(formatEffects(effects, character, npcs))
                .hint(hint)
                .build();
    }


    private String formatHealth(CharacterEntity character) {
        CharacterLifeState state = CharacterLifeState.from(
                character.lifeState,
                character.hp
        );

        StringBuilder result = new StringBuilder()
                .append(character.hp)
                .append('/')
                .append(character.maxHp)
                .append(" | ")
                .append(state.name());

        if (state == CharacterLifeState.DOWNED) {
            result.append(" | saves=")
                    .append(character.deathSaveSuccesses)
                    .append("/3,")
                    .append(character.deathSaveFailures)
                    .append("/3");
        }

        return result.toString();
    }

    private List<String> formatNpcs(List<NpcEntity> values) {
        List<String> result = new ArrayList<>();
        for (NpcEntity npc : values) {
            if (npc == null || clean(npc.name).isEmpty()) {
                continue;
            }
            StringBuilder line = new StringBuilder(npc.name)
                    .append(" | ")
                    .append(clean(npc.status).isEmpty() ? "ACTIVE" : clean(npc.status))
                    .append(" | HP ")
                    .append(npc.hp)
                    .append('/')
                    .append(npc.maxHp);
            if (!clean(npc.location).isEmpty()) {
                line.append(" | ").append(npc.location);
            }
            String summary = firstNonBlank(npc.stateSummary, npc.description, npc.knowledgeSummary);
            if (!summary.isEmpty()) {
                line.append(" | ").append(limit(summary, 90));
            }
            result.add(line.toString());
        }
        return result;
    }

    private List<String> formatQuests(List<QuestEntity> values) {
        List<String> result = new ArrayList<>();
        for (QuestEntity quest : values) {
            if (quest == null) {
                continue;
            }
            result.add(quest.name + (clean(quest.summary).isEmpty()
                    ? ""
                    : " | " + limit(quest.summary, 180)));
        }
        return result;
    }

    private List<String> formatWorldEvents(
            List<WorldEventEntity> values,
            String campaignTitle
    ) {
        List<String> result = new ArrayList<>();
        String title = clean(campaignTitle);

        for (WorldEventEntity event : values) {
            if (event == null) {
                continue;
            }
            String name = clean(event.name).isEmpty() ? clean(event.text) : clean(event.name);
            if (name.isEmpty() || (!title.isEmpty() && name.equalsIgnoreCase(title))) {
                continue;
            }
            String details = clean(event.details);
            result.add(name + (details.isEmpty() ? "" : " | " + limit(details, 180)));
        }
        return result;
    }

    private List<String> formatAbilities(List<AbilityEntity> values) {
        List<String> result = new ArrayList<>();
        for (AbilityEntity ability : values) {
            if (ability == null) {
                continue;
            }
            result.add(ability.name + " | " + ability.category
                    + (clean(ability.details).isEmpty() ? "" : " | " + limit(ability.details, 160)));
        }
        return result;
    }

    private List<String> formatEffects(
            List<EffectEntity> values,
            CharacterEntity character,
            List<NpcEntity> npcs
    ) {
        List<String> result = new ArrayList<>();
        for (EffectEntity effect : values) {
            if (effect == null) {
                continue;
            }

            String name = clean(effect.name);
            if (name.isEmpty() || isActorName(name, character, npcs)) {
                continue;
            }

            result.add(name
                    + (clean(effect.details).isEmpty() ? "" : " | " + limit(effect.details, 100)));
        }
        return result;
    }

    private boolean isActorName(
            String name,
            CharacterEntity character,
            List<NpcEntity> npcs
    ) {
        if (character != null
                && name.equalsIgnoreCase(clean(character.name))) {
            return true;
        }

        for (NpcEntity npc : npcs) {
            if (npc != null
                    && name.equalsIgnoreCase(clean(npc.name))) {
                return true;
            }
        }
        return false;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String clean = clean(value);
            if (!clean.isEmpty()) {
                return clean;
            }
        }
        return "";
    }

    private String limit(String value, int max) {
        String clean = clean(value);
        return clean.length() <= max ? clean : clean.substring(0, max).trim();
    }

    private String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? Collections.emptyList() : values;
    }
}
