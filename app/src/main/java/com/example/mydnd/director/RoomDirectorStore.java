package com.example.mydnd.director;

import com.example.mydnd.db.AppDatabase;
import com.example.mydnd.db.entity.AbilityEntity;
import com.example.mydnd.db.entity.CampaignEntity;
import com.example.mydnd.db.entity.CharacterEntity;
import com.example.mydnd.db.entity.DirectorActionAuditEntity;
import com.example.mydnd.db.entity.EffectEntity;
import com.example.mydnd.db.entity.NpcEntity;
import com.example.mydnd.db.entity.QuestEntity;
import com.example.mydnd.db.entity.StateChangeEntity;
import com.example.mydnd.db.entity.WorldEventEntity;
import com.example.mydnd.game.InventoryRepository;
import com.example.mydnd.game.StateChangeRepository;

import java.util.List;
import java.util.Locale;

/** Room-backed canonical executor for all Director state actions. */
public final class RoomDirectorStore implements DirectorStore {

    private final AppDatabase database;
    private final InventoryRepository inventoryRepository;
    private final StateChangeRepository stateChangeRepository;

    public RoomDirectorStore(
            AppDatabase database,
            InventoryRepository inventoryRepository,
            StateChangeRepository stateChangeRepository
    ) {
        this.database = database;
        this.inventoryRepository = inventoryRepository;
        this.stateChangeRepository = stateChangeRepository;
    }

    @Override
    public DirectorStoreResult apply(long campaignId, DirectorAction action) {
        if (campaignId <= 0L) {
            return rejected("INVALID_CAMPAIGN");
        }

        try {
            return database.runInTransaction(() -> {
                DirectorStoreResult result = applyInside(campaignId, action);

                /*
                 * Even a logical rejection rolls the transaction back. Most
                 * rejected actions do not mutate anything, but this rule also
                 * protects against a late failure after a partial DAO update.
                 */
                if (!result.isApplied()) {
                    throw new RejectedAction(result);
                }

                return result;
            });
        } catch (RejectedAction rejectedAction) {
            return rejectedAction.result;
        }
    }

    private DirectorStoreResult applyInside(
            long campaignId,
            DirectorAction action
    ) {
        switch (action.getType()) {
            case INVENTORY_ADD:
                return inventory(campaignId, action, InventoryRepository.TOOL_ADD_ITEM);
            case INVENTORY_REMOVE:
                return inventory(campaignId, action, InventoryRepository.TOOL_REMOVE_ITEM);
            case HEALTH_CHANGE:
                return health(campaignId, action);
            case MONEY_CHANGE:
                return money(campaignId, action);
            case CHECK_REQUEST:
                return check(campaignId, action);
            case NPC_UPSERT:
                return npcUpsert(campaignId, action);
            case NPC_MEMORY:
                return npcMemory(campaignId, action);
            case NPC_STATUS:
                return npcStatus(campaignId, action);
            case WORLD_EVENT_ADD:
                return worldAdd(campaignId, action);
            case WORLD_EVENT_UPDATE:
                return worldUpdate(campaignId, action);
            case WORLD_EVENT_RESOLVE:
                return worldResolve(campaignId, action);
            case QUEST_START:
                return questStart(campaignId, action);
            case QUEST_UPDATE:
                return questUpdate(campaignId, action);
            case QUEST_COMPLETE:
                return questFinish(campaignId, action, "COMPLETED");
            case QUEST_FAIL:
                return questFinish(campaignId, action, "FAILED");
            case ABILITY_ADD:
                return abilityAdd(campaignId, action);
            case ABILITY_UPDATE:
                return abilityUpdate(campaignId, action);
            case ABILITY_REMOVE:
                return abilityRemove(campaignId, action);
            case EFFECT_ADD:
                return effectAdd(campaignId, action);
            case EFFECT_REMOVE:
                return effectRemove(campaignId, action);
            case LOCATION_SET:
                return location(campaignId, action);
            default:
                return rejected("UNSUPPORTED_ACTION_TYPE");
        }
    }

    @Override
    public void log(long campaignId, DirectorAction action, DirectorResult result) {
        DirectorActionAuditEntity audit = new DirectorActionAuditEntity();
        audit.campaignId = campaignId;
        audit.type = action.getType().getToolCode();
        audit.name = action.getName();
        audit.value = action.getValue();
        audit.details = action.getDetails();
        audit.status = result.getStatus().name();
        audit.code = result.getCode();
        audit.stateAfter = result.getStateAfter();
        audit.stateChangeId = result.getStateChangeId();
        audit.createdAt = System.currentTimeMillis();
        database.directorActionAuditDao().insert(audit);
    }

    private DirectorStoreResult inventory(
            long campaignId,
            DirectorAction action,
            String toolName
    ) {
        InventoryRepository.ApplyResult result = inventoryRepository.applyToolCall(
                campaignId,
                toolName,
                action.getName()
        );

        if (!result.isApplied()) {
            return DirectorStoreResult.rejected(
                    result.getCode(),
                    inventoryState(campaignId)
            );
        }

        StateChangeEntity change = stateChangeRepository.recordInventoryChange(
                campaignId,
                toolName,
                result.getItemName()
        );

        return appliedWithCard(
                result.getCode(),
                inventoryState(campaignId),
                change
        );
    }

    private DirectorStoreResult health(long campaignId, DirectorAction action) {
        int delta = parseInt(action.getValue(), 0);

        if ("PLAYER".equalsIgnoreCase(action.getName())) {
            CampaignEntity campaign = campaign(campaignId);
            CharacterEntity character = character(campaign);
            if (character == null) {
                return rejected("CHARACTER_NOT_FOUND");
            }

            int before = character.hp;
            int after = clamp(before + delta, 0, Math.max(0, character.maxHp));
            if (after == before) {
                return DirectorStoreResult.rejected("HP_UNCHANGED", hpState(character));
            }

            if (database.characterDao().updateHp(character.id, after) != 1) {
                return rejected("HP_UPDATE_FAILED");
            }

            StateChangeEntity change = healthCard(
                    campaignId,
                    character.id,
                    character.name,
                    before,
                    after,
                    character.maxHp,
                    action.getDetails()
            );
            return appliedWithCard(
                    "HP_CHANGED",
                    "PLAYER | " + after + "/" + character.maxHp,
                    change
            );
        }

        NpcEntity npc = database.npcDao().findByName(campaignId, action.getName());
        if (npc == null) {
            return rejected("NPC_NOT_FOUND");
        }

        int before = npc.hp;
        int after = clamp(before + delta, 0, Math.max(0, npc.maxHp));
        if (after == before) {
            return DirectorStoreResult.rejected(
                    "HP_UNCHANGED",
                    npc.name + " | " + npc.hp + "/" + npc.maxHp
            );
        }

        if (database.npcDao().updateHp(
                npc.id,
                after,
                System.currentTimeMillis()
        ) != 1) {
            return rejected("NPC_HP_UPDATE_FAILED");
        }

        StateChangeEntity change = healthCard(
                campaignId,
                npc.id,
                npc.name,
                before,
                after,
                npc.maxHp,
                action.getDetails()
        );
        return appliedWithCard(
                "NPC_HP_CHANGED",
                npc.name + " | " + after + "/" + npc.maxHp,
                change
        );
    }

    private StateChangeEntity healthCard(
            long campaignId,
            long subjectId,
            String subjectName,
            int before,
            int after,
            int maxHp,
            String reason
    ) {
        String type = after < before
                ? StateChangeRepository.TYPE_HEALTH_DAMAGE
                : StateChangeRepository.TYPE_HEALTH_HEAL;
        String title = after < before
                ? "ПОЛУЧЕН УРОН"
                : "ЗДОРОВЬЕ ВОССТАНОВЛЕНО";

        return record(
                campaignId,
                type,
                title,
                subjectName + " · " + signed(after - before) + " HP · " + reason
                        + " · " + after + "/" + maxHp,
                subjectId,
                subjectName,
                "",
                "",
                before,
                after
        );
    }


    private DirectorStoreResult money(long campaignId, DirectorAction action) {
        CampaignEntity campaign = campaign(campaignId);
        CharacterEntity character = character(campaign);
        if (character == null) {
            return rejected("CHARACTER_NOT_FOUND");
        }

        int delta = parseInt(action.getValue(), 0);
        long candidate = (long) character.money + delta;
        if (candidate < 0L) {
            return DirectorStoreResult.rejected("NOT_ENOUGH_MONEY", String.valueOf(character.money));
        }
        if (candidate > Integer.MAX_VALUE) {
            return rejected("MONEY_OVERFLOW");
        }

        int before = character.money;
        int after = (int) candidate;
        if (database.characterDao().updateMoney(character.id, after) != 1) {
            return rejected("MONEY_UPDATE_FAILED");
        }

        StateChangeEntity change = record(
                campaignId,
                delta > 0 ? StateChangeRepository.TYPE_MONEY_GAIN : StateChangeRepository.TYPE_MONEY_SPEND,
                delta > 0 ? "ДЕНЬГИ ПОЛУЧЕНЫ" : "ДЕНЬГИ ПОТРАЧЕНЫ",
                signed(delta) + " · " + action.getDetails(),
                character.id,
                character.name,
                "",
                "",
                before,
                after
        );

        return appliedWithCard("MONEY_CHANGED", String.valueOf(after), change);
    }

    private DirectorStoreResult check(long campaignId, DirectorAction action) {
        StateChangeEntity change = stateChangeRepository.createDiceCheck(
                campaignId,
                action.getName(),
                parseInt(action.getValue(), 10),
                action.getDetails()
        );
        if (change == null) {
            return rejected("CHECK_CREATE_FAILED");
        }
        return appliedWithCard(
                "CHECK_REQUESTED",
                action.getName().toUpperCase(Locale.ROOT) + " DC " + action.getValue(),
                change
        );
    }

    private DirectorStoreResult npcUpsert(long campaignId, DirectorAction action) {
        CampaignEntity campaign = campaign(campaignId);
        if (campaign == null) {
            return rejected("CAMPAIGN_NOT_FOUND");
        }

        long now = System.currentTimeMillis();
        NpcEntity existing = database.npcDao().findByName(campaignId, action.getName());
        boolean created = existing == null;
        String beforeSummary = existing == null ? "" : clean(existing.stateSummary);
        NpcEntity npc;

        if (created) {
            npc = new NpcEntity();
            npc.campaignId = campaignId;
            npc.worldTimelineId = campaign.worldTimelineId;
            npc.name = clean(action.getName());
            npc.description = clean(action.getDetails());
            npc.stateSummary = clean(action.getDetails());
            npc.location = clean(campaign.currentLocation);
            npc.status = "ACTIVE";
            npc.active = true;
            npc.createdAt = now;
            npc.updatedAt = now;
            long id = database.npcDao().insert(npc);
            if (id <= 0L) {
                return rejected("NPC_INSERT_FAILED");
            }
            npc.id = id;
        } else {
            npc = existing;
            String details = clean(action.getDetails());
            String description = details.isEmpty() ? npc.description : details;
            String summary = details.isEmpty() ? npc.stateSummary : details;
            String location = clean(npc.location).isEmpty()
                    ? clean(campaign.currentLocation)
                    : npc.location;
            int updated = database.npcDao().updateDirectorState(
                    npc.id,
                    description,
                    summary,
                    "ACTIVE",
                    true,
                    location,
                    now
            );
            if (updated != 1) {
                return rejected("NPC_UPDATE_FAILED");
            }
            npc.description = description;
            npc.stateSummary = summary;
            npc.status = "ACTIVE";
            npc.active = true;
            npc.location = location;
        }

        StateChangeEntity change = record(
                campaignId,
                StateChangeRepository.TYPE_NPC_UPSERT,
                created ? "ПОЯВИЛСЯ NPC" : "NPC ОБНОВЛЁН",
                clean(action.getDetails()).isEmpty() ? npc.name : action.getDetails(),
                npc.id,
                npc.name,
                beforeSummary,
                clean(npc.stateSummary),
                0,
                0
        );

        return appliedWithCard(
                created ? "NPC_CREATED" : "NPC_UPDATED",
                npc.name + " | " + npc.status,
                change
        );
    }

    private DirectorStoreResult npcMemory(long campaignId, DirectorAction action) {
        StateChangeEntity change = stateChangeRepository.rememberNpc(
                campaignId,
                action.getName(),
                action.getValue(),
                action.getDetails()
        );
        if (change == null) {
            return rejected("NPC_MEMORY_REJECTED");
        }
        NpcEntity npc = database.npcDao().getById(change.subjectId);
        String state = npc == null
                ? action.getName()
                : npc.name + " | attitude=" + npc.attitude;
        return appliedWithCard("NPC_MEMORY_SAVED", state, change);
    }

    private DirectorStoreResult npcStatus(long campaignId, DirectorAction action) {
        NpcEntity npc = database.npcDao().findByName(campaignId, action.getName());
        if (npc == null) {
            return rejected("NPC_NOT_FOUND");
        }

        String before = clean(npc.status);
        String status = action.getValue().toUpperCase(Locale.ROOT);
        boolean active = !("INACTIVE".equals(status)
                || "DEAD".equals(status)
                || "MISSING".equals(status));
        String summary = clean(action.getDetails()).isEmpty()
                ? npc.stateSummary
                : action.getDetails();
        if (before.equals(status) && npc.active == active && clean(action.getDetails()).isEmpty()) {
            return DirectorStoreResult.rejected("NPC_STATUS_UNCHANGED", before);
        }

        if (database.npcDao().updateStatus(
                npc.id,
                status,
                active,
                summary,
                System.currentTimeMillis()
        ) != 1) {
            return rejected("NPC_STATUS_UPDATE_FAILED");
        }

        StateChangeEntity change = record(
                campaignId,
                StateChangeRepository.TYPE_NPC_STATUS,
                "СТАТУС NPC ИЗМЕНЁН",
                npc.name + ": " + before + " → " + status
                        + suffixReason(action.getDetails()),
                npc.id,
                npc.name,
                before,
                status,
                0,
                0
        );
        return appliedWithCard("NPC_STATUS_CHANGED", npc.name + " | " + status, change);
    }

    private DirectorStoreResult worldAdd(long campaignId, DirectorAction action) {
        CampaignEntity campaign = campaign(campaignId);
        if (campaign == null || campaign.worldTimelineId <= 0L) {
            return rejected("WORLD_TIMELINE_NOT_FOUND");
        }

        String key = key(action.getName());
        WorldEventEntity existing = database.worldEventDao().findActiveByNameKey(
                campaign.worldTimelineId,
                key
        );
        if (existing != null) {
            return DirectorStoreResult.rejected("WORLD_EVENT_ALREADY_ACTIVE", worldState(existing));
        }

        long now = System.currentTimeMillis();
        WorldEventEntity event = new WorldEventEntity();
        event.worldTimelineId = campaign.worldTimelineId;
        event.name = clean(action.getName());
        event.nameKey = key;
        event.details = clean(action.getDetails());
        event.text = composeNameDetails(event.name, event.details);
        event.importance = parseImportance(action.getValue());
        event.eventType = "DIRECTOR";
        event.tone = "NEUTRAL";
        event.status = "ACTIVE";
        event.createdAt = now;
        event.updatedAt = now;

        long id = database.worldEventDao().insert(event);
        if (id <= 0L) {
            return rejected("WORLD_EVENT_INSERT_FAILED");
        }
        event.id = id;
        touchWorld(campaign, now);

        StateChangeEntity change = record(
                campaignId,
                StateChangeRepository.TYPE_WORLD_EVENT,
                "МИР ИЗМЕНИЛСЯ",
                event.text,
                event.id,
                event.name,
                "",
                event.details,
                0,
                event.importance
        );
        return appliedWithCard("WORLD_EVENT_ADDED", worldState(event), change);
    }

    private DirectorStoreResult worldUpdate(long campaignId, DirectorAction action) {
        CampaignEntity campaign = campaign(campaignId);
        WorldEventEntity event = activeWorldEvent(campaign, action.getName());
        if (event == null) {
            return rejected("WORLD_EVENT_NOT_FOUND");
        }

        String before = event.details;
        int importance = clean(action.getValue()).isEmpty()
                ? event.importance
                : parseImportance(action.getValue());
        String details = clean(action.getDetails());
        long now = System.currentTimeMillis();
        if (database.worldEventDao().updateDirectorEvent(
                event.id,
                composeNameDetails(event.name, details),
                details,
                importance,
                now
        ) != 1) {
            return rejected("WORLD_EVENT_UPDATE_FAILED");
        }
        event.details = details;
        event.importance = importance;
        touchWorld(campaign, now);

        StateChangeEntity change = record(
                campaignId,
                StateChangeRepository.TYPE_WORLD_EVENT_UPDATE,
                "СОБЫТИЕ МИРА ИЗМЕНИЛОСЬ",
                event.name + suffixReason(details),
                event.id,
                event.name,
                before,
                details,
                0,
                importance
        );
        return appliedWithCard("WORLD_EVENT_UPDATED", worldState(event), change);
    }

    private DirectorStoreResult worldResolve(long campaignId, DirectorAction action) {
        CampaignEntity campaign = campaign(campaignId);
        WorldEventEntity event = activeWorldEvent(campaign, action.getName());
        if (event == null) {
            return rejected("WORLD_EVENT_NOT_FOUND");
        }

        long now = System.currentTimeMillis();
        if (database.worldEventDao().updateStatus(event.id, "RESOLVED", now) != 1) {
            return rejected("WORLD_EVENT_RESOLVE_FAILED");
        }
        touchWorld(campaign, now);

        StateChangeEntity change = record(
                campaignId,
                StateChangeRepository.TYPE_WORLD_EVENT_RESOLVE,
                "СОБЫТИЕ МИРА ЗАВЕРШЕНО",
                event.name + suffixReason(action.getDetails()),
                event.id,
                event.name,
                "ACTIVE",
                "RESOLVED",
                0,
                0
        );
        return appliedWithCard("WORLD_EVENT_RESOLVED", event.name + " | RESOLVED", change);
    }

    private DirectorStoreResult questStart(long campaignId, DirectorAction action) {
        String name = clean(action.getName());
        String key = key(name);
        QuestEntity quest = database.questDao().findByNameKey(campaignId, key);
        long now = System.currentTimeMillis();
        boolean created = quest == null;

        if (quest != null && "ACTIVE".equals(quest.status)) {
            return DirectorStoreResult.rejected("QUEST_ALREADY_ACTIVE", questState(quest));
        }

        if (created) {
            quest = new QuestEntity();
            quest.campaignId = campaignId;
            quest.name = name;
            quest.nameKey = key;
            quest.status = "ACTIVE";
            quest.summary = clean(action.getDetails());
            quest.createdAt = now;
            quest.updatedAt = now;
            long id = database.questDao().insert(quest);
            if (id <= 0L) {
                return rejected("QUEST_INSERT_FAILED");
            }
            quest.id = id;
        } else {
            if (database.questDao().updateState(
                    quest.id,
                    "ACTIVE",
                    clean(action.getDetails()),
                    now
            ) != 1) {
                return rejected("QUEST_RESTART_FAILED");
            }
            quest.status = "ACTIVE";
            quest.summary = clean(action.getDetails());
        }

        StateChangeEntity change = record(
                campaignId,
                StateChangeRepository.TYPE_QUEST_START,
                "НАЧАТ КВЕСТ",
                composeNameDetails(quest.name, quest.summary),
                quest.id,
                quest.name,
                "",
                quest.summary,
                0,
                0
        );
        return appliedWithCard(created ? "QUEST_STARTED" : "QUEST_RESTARTED", questState(quest), change);
    }

    private DirectorStoreResult questUpdate(long campaignId, DirectorAction action) {
        QuestEntity quest = database.questDao().findByNameKey(campaignId, key(action.getName()));
        if (quest == null || !"ACTIVE".equals(quest.status)) {
            return rejected("ACTIVE_QUEST_NOT_FOUND");
        }
        String before = quest.summary;
        String after = clean(action.getDetails());
        if (before.equals(after)) {
            return DirectorStoreResult.rejected("QUEST_UNCHANGED", questState(quest));
        }
        if (database.questDao().updateState(
                quest.id,
                "ACTIVE",
                after,
                System.currentTimeMillis()
        ) != 1) {
            return rejected("QUEST_UPDATE_FAILED");
        }
        quest.summary = after;

        StateChangeEntity change = record(
                campaignId,
                StateChangeRepository.TYPE_QUEST_UPDATE,
                "КВЕСТ ОБНОВЛЁН",
                composeNameDetails(quest.name, after),
                quest.id,
                quest.name,
                before,
                after,
                0,
                0
        );
        return appliedWithCard("QUEST_UPDATED", questState(quest), change);
    }

    private DirectorStoreResult questFinish(
            long campaignId,
            DirectorAction action,
            String status
    ) {
        QuestEntity quest = database.questDao().findByNameKey(campaignId, key(action.getName()));
        if (quest == null || !"ACTIVE".equals(quest.status)) {
            return rejected("ACTIVE_QUEST_NOT_FOUND");
        }
        String summary = clean(action.getDetails()).isEmpty()
                ? quest.summary
                : action.getDetails();
        if (database.questDao().updateState(
                quest.id,
                status,
                summary,
                System.currentTimeMillis()
        ) != 1) {
            return rejected("QUEST_FINISH_FAILED");
        }
        String type = "COMPLETED".equals(status)
                ? StateChangeRepository.TYPE_QUEST_COMPLETE
                : StateChangeRepository.TYPE_QUEST_FAIL;
        String title = "COMPLETED".equals(status)
                ? "КВЕСТ ЗАВЕРШЁН"
                : "КВЕСТ ПРОВАЛЕН";

        StateChangeEntity change = record(
                campaignId,
                type,
                title,
                composeNameDetails(quest.name, summary),
                quest.id,
                quest.name,
                "ACTIVE",
                status,
                0,
                0
        );
        return appliedWithCard("QUEST_" + status, quest.name + " | " + status, change);
    }

    private DirectorStoreResult abilityAdd(long campaignId, DirectorAction action) {
        CampaignEntity campaign = campaign(campaignId);
        CharacterEntity character = character(campaign);
        if (character == null) {
            return rejected("CHARACTER_NOT_FOUND");
        }

        String name = clean(action.getName());
        String key = key(name);
        AbilityEntity ability = database.abilityDao().findByNameKey(campaignId, key);
        long now = System.currentTimeMillis();
        boolean created = ability == null;

        if (ability != null && ability.active) {
            return DirectorStoreResult.rejected("ABILITY_ALREADY_ACTIVE", abilityState(ability));
        }

        if (created) {
            ability = new AbilityEntity();
            ability.campaignId = campaignId;
            ability.characterId = character.id;
            ability.name = name;
            ability.nameKey = key;
            ability.category = action.getValue().toUpperCase(Locale.ROOT);
            ability.details = clean(action.getDetails());
            ability.active = true;
            ability.createdAt = now;
            ability.updatedAt = now;
            long id = database.abilityDao().insert(ability);
            if (id <= 0L) {
                return rejected("ABILITY_INSERT_FAILED");
            }
            ability.id = id;
        } else {
            if (database.abilityDao().updateAndActivate(
                    ability.id,
                    action.getValue().toUpperCase(Locale.ROOT),
                    clean(action.getDetails()),
                    now
            ) != 1) {
                return rejected("ABILITY_REACTIVATE_FAILED");
            }
            ability.category = action.getValue().toUpperCase(Locale.ROOT);
            ability.details = clean(action.getDetails());
            ability.active = true;
        }

        StateChangeEntity change = record(
                campaignId,
                StateChangeRepository.TYPE_ABILITY_ADD,
                "ПОЛУЧЕНА СПОСОБНОСТЬ",
                composeNameDetails(ability.name, ability.details),
                ability.id,
                ability.name,
                "",
                ability.category,
                0,
                0
        );
        return appliedWithCard(created ? "ABILITY_ADDED" : "ABILITY_REACTIVATED", abilityState(ability), change);
    }

    private DirectorStoreResult abilityUpdate(long campaignId, DirectorAction action) {
        AbilityEntity ability = database.abilityDao().findByNameKey(campaignId, key(action.getName()));
        if (ability == null || !ability.active) {
            return rejected("ACTIVE_ABILITY_NOT_FOUND");
        }
        String before = abilityState(ability);
        String category = action.getValue().toUpperCase(Locale.ROOT);
        String details = clean(action.getDetails());
        if (database.abilityDao().updateAndActivate(
                ability.id,
                category,
                details,
                System.currentTimeMillis()
        ) != 1) {
            return rejected("ABILITY_UPDATE_FAILED");
        }
        ability.category = category;
        ability.details = details;

        StateChangeEntity change = record(
                campaignId,
                StateChangeRepository.TYPE_ABILITY_UPDATE,
                "СПОСОБНОСТЬ ИЗМЕНЕНА",
                composeNameDetails(ability.name, details),
                ability.id,
                ability.name,
                before,
                abilityState(ability),
                0,
                0
        );
        return appliedWithCard("ABILITY_UPDATED", abilityState(ability), change);
    }

    private DirectorStoreResult abilityRemove(long campaignId, DirectorAction action) {
        AbilityEntity ability = database.abilityDao().findByNameKey(campaignId, key(action.getName()));
        if (ability == null || !ability.active) {
            return rejected("ACTIVE_ABILITY_NOT_FOUND");
        }
        if (database.abilityDao().deactivate(ability.id, System.currentTimeMillis()) != 1) {
            return rejected("ABILITY_REMOVE_FAILED");
        }

        StateChangeEntity change = record(
                campaignId,
                StateChangeRepository.TYPE_ABILITY_REMOVE,
                "СПОСОБНОСТЬ УТРАЧЕНА",
                ability.name + suffixReason(action.getDetails()),
                ability.id,
                ability.name,
                "ACTIVE",
                "INACTIVE",
                0,
                0
        );
        return appliedWithCard("ABILITY_REMOVED", ability.name + " | INACTIVE", change);
    }

    private DirectorStoreResult effectAdd(long campaignId, DirectorAction action) {
        String name = clean(action.getName());
        String key = key(name);
        EffectEntity effect = database.effectDao().findByNameKey(campaignId, key);
        long now = System.currentTimeMillis();
        boolean created = effect == null;

        if (effect != null && effect.active) {
            return DirectorStoreResult.rejected("EFFECT_ALREADY_ACTIVE", effectState(effect));
        }

        if (created) {
            effect = new EffectEntity();
            effect.campaignId = campaignId;
            effect.name = name;
            effect.nameKey = key;
            effect.details = clean(action.getDetails());
            effect.active = true;
            effect.createdAt = now;
            effect.updatedAt = now;
            long id = database.effectDao().insert(effect);
            if (id <= 0L) {
                return rejected("EFFECT_INSERT_FAILED");
            }
            effect.id = id;
        } else {
            if (database.effectDao().updateAndActivate(
                    effect.id,
                    clean(action.getDetails()),
                    now
            ) != 1) {
                return rejected("EFFECT_REACTIVATE_FAILED");
            }
            effect.details = clean(action.getDetails());
            effect.active = true;
        }

        StateChangeEntity change = record(
                campaignId,
                StateChangeRepository.TYPE_EFFECT_ADD,
                "ЭФФЕКТ ПОЛУЧЕН",
                composeNameDetails(effect.name, effect.details),
                effect.id,
                effect.name,
                "",
                effect.details,
                0,
                0
        );
        return appliedWithCard(created ? "EFFECT_ADDED" : "EFFECT_REACTIVATED", effectState(effect), change);
    }

    private DirectorStoreResult effectRemove(long campaignId, DirectorAction action) {
        EffectEntity effect = database.effectDao().findByNameKey(campaignId, key(action.getName()));
        if (effect == null || !effect.active) {
            return rejected("ACTIVE_EFFECT_NOT_FOUND");
        }
        if (database.effectDao().deactivate(effect.id, System.currentTimeMillis()) != 1) {
            return rejected("EFFECT_REMOVE_FAILED");
        }

        StateChangeEntity change = record(
                campaignId,
                StateChangeRepository.TYPE_EFFECT_REMOVE,
                "ЭФФЕКТ СНЯТ",
                effect.name + suffixReason(action.getDetails()),
                effect.id,
                effect.name,
                "ACTIVE",
                "INACTIVE",
                0,
                0
        );
        return appliedWithCard("EFFECT_REMOVED", effect.name + " | INACTIVE", change);
    }

    private DirectorStoreResult location(long campaignId, DirectorAction action) {
        CampaignEntity campaign = campaign(campaignId);
        if (campaign == null) {
            return rejected("CAMPAIGN_NOT_FOUND");
        }
        String before = clean(campaign.currentLocation);
        String after = clean(action.getName());
        if (before.equalsIgnoreCase(after)) {
            return DirectorStoreResult.rejected("LOCATION_UNCHANGED", before);
        }
        if (database.campaignDao().updateCurrentLocation(
                campaignId,
                after,
                System.currentTimeMillis()
        ) != 1) {
            return rejected("LOCATION_UPDATE_FAILED");
        }

        StateChangeEntity change = record(
                campaignId,
                StateChangeRepository.TYPE_LOCATION,
                "ЛОКАЦИЯ ИЗМЕНЕНА",
                (before.isEmpty() ? "—" : before) + " → " + after
                        + suffixReason(action.getDetails()),
                0L,
                after,
                before,
                after,
                0,
                0
        );
        return appliedWithCard("LOCATION_CHANGED", after, change);
    }

    private StateChangeEntity record(
            long campaignId,
            String type,
            String title,
            String description,
            long subjectId,
            String subjectName,
            String beforeText,
            String afterText,
            int beforeNumber,
            int afterNumber
    ) {
        return stateChangeRepository.recordDirectorChange(
                campaignId,
                type,
                title,
                description,
                subjectId,
                subjectName,
                beforeText,
                afterText,
                beforeNumber,
                afterNumber
        );
    }

    private DirectorStoreResult appliedWithCard(
            String code,
            String stateAfter,
            StateChangeEntity change
    ) {
        if (change == null) {
            return DirectorStoreResult.rejected(
                    code + "_STATE_CARD_FAILED",
                    stateAfter
            );
        }
        return DirectorStoreResult.applied(code, stateAfter, change.id);
    }

    private static final class RejectedAction extends RuntimeException {
        private final DirectorStoreResult result;

        private RejectedAction(DirectorStoreResult result) {
            super(result == null ? "DIRECTOR_REJECTED" : result.getCode());
            this.result = result == null
                    ? DirectorStoreResult.rejected("DIRECTOR_REJECTED", "")
                    : result;
        }
    }

    private CampaignEntity campaign(long campaignId) {
        return database.campaignDao().getById(campaignId);
    }

    private CharacterEntity character(CampaignEntity campaign) {
        if (campaign == null || campaign.characterId <= 0L) {
            return null;
        }
        return database.characterDao().getCharacter(campaign.characterId);
    }

    private WorldEventEntity activeWorldEvent(CampaignEntity campaign, String name) {
        if (campaign == null || campaign.worldTimelineId <= 0L) {
            return null;
        }
        return database.worldEventDao().findActiveByNameKey(
                campaign.worldTimelineId,
                key(name)
        );
    }

    private void touchWorld(CampaignEntity campaign, long now) {
        if (campaign != null && campaign.worldTimelineId > 0L) {
            database.worldTimelineDao().touch(campaign.worldTimelineId, now);
        }
    }

    private String inventoryState(long campaignId) {
        List<String> items = inventoryRepository.getItemNames(campaignId);
        return items.isEmpty() ? "EMPTY" : String.join(", ", items);
    }

    private String hpState(CharacterEntity character) {
        return character.hp + "/" + character.maxHp;
    }

    private String worldState(WorldEventEntity event) {
        return event.name + " | " + event.status + " | importance=" + event.importance;
    }

    private String questState(QuestEntity quest) {
        return quest.name + " | " + quest.status
                + (clean(quest.summary).isEmpty() ? "" : " | " + quest.summary);
    }

    private String abilityState(AbilityEntity ability) {
        return ability.name + " | " + ability.category
                + (clean(ability.details).isEmpty() ? "" : " | " + ability.details);
    }

    private String effectState(EffectEntity effect) {
        return effect.name + (clean(effect.details).isEmpty() ? "" : " | " + effect.details);
    }

    private int parseImportance(String value) {
        String clean = clean(value);
        if (clean.isEmpty()) {
            return 1;
        }
        return clamp(parseInt(clean, 1), 1, 3);
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(clean(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String signed(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    private String suffixReason(String details) {
        String clean = clean(details);
        return clean.isEmpty() ? "" : " · " + clean;
    }

    private String composeNameDetails(String name, String details) {
        String cleanName = clean(name);
        String cleanDetails = clean(details);
        return cleanDetails.isEmpty() ? cleanName : cleanName + ": " + cleanDetails;
    }

    private String key(String value) {
        return clean(value).toLowerCase(Locale.ROOT);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private DirectorStoreResult rejected(String code) {
        return DirectorStoreResult.rejected(code, "");
    }
}
