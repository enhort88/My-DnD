package com.example.mydnd.changeset;

import com.example.mydnd.db.AppDatabase;
import com.example.mydnd.db.entity.AbilityEntity;
import com.example.mydnd.db.entity.CampaignEntity;
import com.example.mydnd.db.entity.CharacterEntity;
import com.example.mydnd.db.entity.EffectEntity;
import com.example.mydnd.db.entity.InventoryItemEntity;
import com.example.mydnd.db.entity.NpcEntity;
import com.example.mydnd.db.entity.QuestEntity;
import com.example.mydnd.db.entity.WorldEventEntity;
import com.example.mydnd.game.CharacterLifeState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Builds the compact alias state for one narrative-first turn. */
public final class ChangeSetPromptStateRepository {

    private static final int INVENTORY_LIMIT = 18;
    private static final int NPC_LIMIT = 6;
    private static final int QUEST_LIMIT = 6;
    private static final int WORLD_LIMIT = 6;
    private static final int ABILITY_LIMIT = 8;
    private static final int EFFECT_LIMIT = 6;

    private final AppDatabase database;

    public ChangeSetPromptStateRepository(AppDatabase database) {
        this.database = database;
    }

    public ChangeSetPromptState build(long campaignId) {
        CampaignEntity campaign = database.campaignDao().getById(campaignId);
        if (campaign == null) {
            return ChangeSetPromptState.empty();
        }

        CharacterEntity character = campaign.characterId > 0L
                ? database.characterDao().getCharacter(campaign.characterId)
                : null;

        List<InventoryItemEntity> inventory = safe(database.inventoryItemDao().getItemsForCampaign(campaignId));
        List<NpcEntity> npcs = safe(database.npcDao().getActiveForCampaign(campaignId, NPC_LIMIT));
        List<QuestEntity> quests = safe(database.questDao().getActiveForCampaign(campaignId, QUEST_LIMIT));
        List<AbilityEntity> abilities = safe(database.abilityDao().getActiveForCampaign(campaignId, ABILITY_LIMIT));
        List<EffectEntity> effects = safe(database.effectDao().getActiveForCampaign(campaignId, EFFECT_LIMIT));
        List<WorldEventEntity> world = campaign.worldTimelineId > 0L
                ? safe(database.worldEventDao().getActive(campaign.worldTimelineId, WORLD_LIMIT))
                : Collections.emptyList();

        return ChangeSetPromptState.builder()
                .characterName(character == null ? "" : character.name)
                .location(clean(campaign.currentLocation))
                .health(character == null ? "UNKNOWN" : health(character))
                .money(character == null ? "UNKNOWN" : String.valueOf(character.money))
                .inventory(inventoryRefs(inventory))
                .npcs(npcRefs(npcs))
                .quests(questRefs(quests))
                .worldEvents(worldRefs(world, campaign.title))
                .abilities(abilityRefs(abilities))
                .effects(effectRefs(effects, character, npcs))
                .build();
    }

    private List<ChangeSetPromptState.Ref> inventoryRefs(List<InventoryItemEntity> values) {
        List<ChangeSetPromptState.Ref> result = new ArrayList<>();
        int count = 0;
        for (InventoryItemEntity item : values) {
            if (item == null || clean(item.name).isEmpty() || count >= INVENTORY_LIMIT) {
                continue;
            }
            count++;
            result.add(new ChangeSetPromptState.Ref("I" + count, item.id, clean(item.name), ""));
        }
        return result;
    }

    private List<ChangeSetPromptState.Ref> npcRefs(List<NpcEntity> values) {
        List<ChangeSetPromptState.Ref> result = new ArrayList<>();
        int count = 0;
        for (NpcEntity npc : values) {
            if (npc == null || clean(npc.name).isEmpty()) {
                continue;
            }
            count++;
            String status = clean(npc.status).isEmpty() ? "ACTIVE" : clean(npc.status);
            String summary = firstNonBlank(npc.stateSummary, npc.description);
            String meta = status + ",HP=" + npc.hp + "/" + npc.maxHp;
            if (!summary.isEmpty()) {
                meta += "," + limit(summary, 60);
            }
            result.add(new ChangeSetPromptState.Ref(
                    "N" + count,
                    npc.id,
                    clean(npc.name),
                    meta
            ));
        }
        return result;
    }

    private List<ChangeSetPromptState.Ref> questRefs(List<QuestEntity> values) {
        List<ChangeSetPromptState.Ref> result = new ArrayList<>();
        int count = 0;
        for (QuestEntity quest : values) {
            if (quest == null || clean(quest.name).isEmpty()) {
                continue;
            }
            count++;
            result.add(new ChangeSetPromptState.Ref("Q" + count, quest.id, clean(quest.name), clean(quest.status)));
        }
        return result;
    }

    private List<ChangeSetPromptState.Ref> worldRefs(List<WorldEventEntity> values, String campaignTitle) {
        List<ChangeSetPromptState.Ref> result = new ArrayList<>();
        String title = clean(campaignTitle);
        int count = 0;
        for (WorldEventEntity event : values) {
            if (event == null) {
                continue;
            }
            String name = clean(event.name).isEmpty() ? clean(event.text) : clean(event.name);
            if (name.isEmpty() || (!title.isEmpty() && name.equalsIgnoreCase(title))) {
                continue;
            }
            count++;
            result.add(new ChangeSetPromptState.Ref("W" + count, event.id, name, "imp=" + event.importance));
        }
        return result;
    }

    private List<ChangeSetPromptState.Ref> abilityRefs(List<AbilityEntity> values) {
        List<ChangeSetPromptState.Ref> result = new ArrayList<>();
        int count = 0;
        for (AbilityEntity ability : values) {
            if (ability == null || clean(ability.name).isEmpty()) {
                continue;
            }
            count++;
            result.add(new ChangeSetPromptState.Ref("A" + count, ability.id, clean(ability.name), clean(ability.category)));
        }
        return result;
    }

    private List<ChangeSetPromptState.Ref> effectRefs(
            List<EffectEntity> values,
            CharacterEntity character,
            List<NpcEntity> npcs
    ) {
        List<ChangeSetPromptState.Ref> result = new ArrayList<>();
        int count = 0;
        for (EffectEntity effect : values) {
            if (effect == null || clean(effect.name).isEmpty() || isActorName(effect.name, character, npcs)) {
                continue;
            }
            count++;
            result.add(new ChangeSetPromptState.Ref("E" + count, effect.id, clean(effect.name), ""));
        }
        return result;
    }

    private String health(CharacterEntity character) {
        CharacterLifeState state = CharacterLifeState.from(character.lifeState, character.hp);
        String value = character.hp + "/" + character.maxHp + "|" + state.name();
        if (state == CharacterLifeState.DOWNED) {
            value += "|saves=" + character.deathSaveSuccesses + "/3," + character.deathSaveFailures + "/3";
        }
        return value;
    }

    private boolean isActorName(String name, CharacterEntity character, List<NpcEntity> npcs) {
        String cleanName = clean(name);
        if (character != null && cleanName.equalsIgnoreCase(clean(character.name))) {
            return true;
        }
        for (NpcEntity npc : npcs) {
            if (npc != null && cleanName.equalsIgnoreCase(clean(npc.name))) {
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

    private <T> List<T> safe(List<T> values) {
        return values == null ? Collections.emptyList() : values;
    }
}
