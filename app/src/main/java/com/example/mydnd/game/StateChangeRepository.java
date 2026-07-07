package com.example.mydnd.game;

import com.example.mydnd.db.AppDatabase;
import com.example.mydnd.db.entity.NpcEntity;
import com.example.mydnd.db.entity.StateChangeEntity;

import java.util.Collections;
import java.util.List;

public class StateChangeRepository {

    public static final String TYPE_INVENTORY_ADD = "INVENTORY_ADD";
    public static final String TYPE_INVENTORY_REMOVE = "INVENTORY_REMOVE";
    public static final String TYPE_NPC_MEMORY_GOOD = "NPC_MEMORY_GOOD";
    public static final String TYPE_NPC_MEMORY_BAD = "NPC_MEMORY_BAD";
    public static final String TYPE_NPC_MEMORY_NEUTRAL = "NPC_MEMORY_NEUTRAL";
    public static final String TYPE_DICE_CHECK = "DICE_CHECK";
    public static final String TYPE_WORLD_EVENT = "WORLD_EVENT";

    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_REVERTED = "REVERTED";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RESOLVED = "RESOLVED";

    private static final int MAX_NPC_KNOWLEDGE_CHARS = 600;

    private final AppDatabase database;
    private final InventoryRepository inventoryRepository;

    public StateChangeRepository(AppDatabase database) {
        this.database = database;
        this.inventoryRepository = new InventoryRepository(database);
    }

    public List<StateChangeEntity> getForCampaign(long campaignId) {
        List<StateChangeEntity> result =
                database.stateChangeDao().getForCampaign(campaignId);

        return result == null
                ? Collections.emptyList()
                : result;
    }

    public StateChangeEntity recordInventoryChange(
            long campaignId,
            String functionName,
            String itemName
    ) {
        if (campaignId <= 0L || safe(itemName).isEmpty()) {
            return null;
        }

        StateChangeEntity change = base(campaignId);
        change.subjectName = safe(itemName);
        change.description = change.subjectName;
        change.canUndo = true;

        if (InventoryRepository.TOOL_ADD_ITEM.equals(functionName)) {
            change.type = TYPE_INVENTORY_ADD;
            change.title = "ПРЕДМЕТ ПОЛУЧЕН";
        } else if (InventoryRepository.TOOL_REMOVE_ITEM.equals(functionName)) {
            change.type = TYPE_INVENTORY_REMOVE;
            change.title = "ПРЕДМЕТ ПОТЕРЯН";
        } else {
            return null;
        }

        return insertAndReload(change);
    }

    public StateChangeEntity rememberNpc(
            long campaignId,
            String npcName,
            String tone,
            String fact
    ) {
        String safeName = safe(npcName);
        String safeFact = safe(fact);

        if (campaignId <= 0L || safeName.isEmpty() || safeFact.isEmpty()) {
            return null;
        }

        NpcEntity npc = database.npcDao().findActiveByName(
                campaignId,
                safeName
        );

        if (npc == null) {
            return null;
        }

        String beforeKnowledge = safe(npc.knowledgeSummary);
        int beforeAttitude = npc.attitude;

        if (!beforeKnowledge.isEmpty()
                && beforeKnowledge.toLowerCase(java.util.Locale.ROOT)
                .contains(safeFact.toLowerCase(java.util.Locale.ROOT))) {
            return null;
        }

        String afterKnowledge = appendKnowledge(
                beforeKnowledge,
                safeFact
        );

        int delta = toneDelta(tone);
        int afterAttitude = clampAttitude(beforeAttitude + delta);

        int updated = database.npcDao().updateMemoryState(
                npc.id,
                afterKnowledge,
                afterAttitude,
                System.currentTimeMillis()
        );

        if (updated != 1) {
            return null;
        }

        StateChangeEntity change = base(campaignId);
        change.type = typeForTone(tone);
        change.title = npc.name + " ЗАПОМНИЛ";
        change.description = safeFact;
        change.subjectId = npc.id;
        change.subjectName = npc.name;
        change.beforeText = beforeKnowledge;
        change.afterText = afterKnowledge;
        change.beforeNumber = beforeAttitude;
        change.afterNumber = afterAttitude;
        change.canUndo = true;

        return insertAndReload(change);
    }

    public StateChangeEntity createDiceCheck(
            long campaignId,
            String attributeCode,
            int difficulty,
            String reason
    ) {
        if (campaignId <= 0L) {
            return null;
        }

        StateChangeEntity change = base(campaignId);
        change.type = TYPE_DICE_CHECK;
        change.status = STATUS_PENDING;
        change.title = "НУЖНА ПРОВЕРКА";
        change.description = safe(reason);
        change.subjectName = safe(attributeCode).toUpperCase();
        change.beforeNumber = Math.max(1, Math.min(30, difficulty));
        change.canUndo = false;

        return insertAndReload(change);
    }

    public StateChangeEntity recordWorldEvent(
            long campaignId,
            String text
    ) {
        if (campaignId <= 0L || safe(text).isEmpty()) {
            return null;
        }

        StateChangeEntity change = base(campaignId);
        change.type = TYPE_WORLD_EVENT;
        change.title = "МИР ИЗМЕНИЛСЯ";
        change.description = safe(text);
        change.canUndo = false;

        return insertAndReload(change);
    }

    public UndoResult undo(long changeId) {
        StateChangeEntity change = database.stateChangeDao().getById(changeId);

        if (change == null) {
            return UndoResult.failed("Изменение не найдено.");
        }

        if (!change.canUndo) {
            return UndoResult.failed("Это изменение нельзя отменить.");
        }

        if (!STATUS_APPLIED.equals(change.status)) {
            return UndoResult.failed("Изменение уже отменено.");
        }

        int laterApplied = database.stateChangeDao()
                .countLaterAppliedForSubject(
                        change.campaignId,
                        change.subjectName,
                        change.createdAt,
                        change.id
                );

        if (laterApplied > 0) {
            return UndoResult.failed(
                    "Слишком поздно: это состояние уже изменилось дальше."
            );
        }

        boolean reverted;

        if (TYPE_INVENTORY_ADD.equals(change.type)) {
            InventoryRepository.ApplyResult result =
                    inventoryRepository.applyToolCall(
                            change.campaignId,
                            InventoryRepository.TOOL_REMOVE_ITEM,
                            change.subjectName
                    );
            reverted = result.isApplied();

        } else if (TYPE_INVENTORY_REMOVE.equals(change.type)) {
            InventoryRepository.ApplyResult result =
                    inventoryRepository.applyToolCall(
                            change.campaignId,
                            InventoryRepository.TOOL_ADD_ITEM,
                            change.subjectName
                    );
            reverted = result.isApplied();

        } else if (isNpcMemory(change.type)) {
            NpcEntity currentNpc = database.npcDao().getById(change.subjectId);

            if (currentNpc == null
                    || !safe(currentNpc.knowledgeSummary).equals(
                    safe(change.afterText)
            )
                    || currentNpc.attitude != change.afterNumber) {
                return UndoResult.failed(
                        "NPC уже изменился дальше — старый откат небезопасен."
                );
            }

            reverted = database.npcDao().updateMemoryState(
                    change.subjectId,
                    change.beforeText,
                    change.beforeNumber,
                    System.currentTimeMillis()
            ) == 1;

        } else {
            reverted = false;
        }

        if (!reverted) {
            return UndoResult.failed(
                    "Не удалось безопасно откатить текущее состояние."
            );
        }

        database.stateChangeDao().updateStatus(
                change.id,
                STATUS_REVERTED,
                System.currentTimeMillis()
        );

        return UndoResult.success();
    }

    public boolean resolveDiceCheck(
            long changeId,
            int result,
            boolean success
    ) {
        return database.stateChangeDao().resolveDiceCheck(
                changeId,
                STATUS_RESOLVED,
                result,
                success ? "УСПЕХ" : "ПРОВАЛ",
                System.currentTimeMillis()
        ) == 1;
    }

    public StateChangeEntity get(long changeId) {
        return database.stateChangeDao().getById(changeId);
    }

    private StateChangeEntity base(long campaignId) {
        long now = System.currentTimeMillis();

        StateChangeEntity change = new StateChangeEntity();
        change.campaignId = campaignId;
        change.status = STATUS_APPLIED;
        change.createdAt = now;
        change.updatedAt = now;
        return change;
    }

    private StateChangeEntity insertAndReload(StateChangeEntity change) {
        long id = database.stateChangeDao().insert(change);

        if (id <= 0L) {
            return null;
        }

        return database.stateChangeDao().getById(id);
    }

    private String appendKnowledge(String before, String fact) {
        String result = before.isEmpty()
                ? fact
                : before + "; " + fact;

        if (result.length() <= MAX_NPC_KNOWLEDGE_CHARS) {
            return result;
        }

        return result.substring(
                result.length() - MAX_NPC_KNOWLEDGE_CHARS
        ).trim();
    }

    private int toneDelta(String tone) {
        if ("GOOD".equalsIgnoreCase(safe(tone))) {
            return 1;
        }

        if ("BAD".equalsIgnoreCase(safe(tone))) {
            return -1;
        }

        return 0;
    }

    private String typeForTone(String tone) {
        if ("GOOD".equalsIgnoreCase(safe(tone))) {
            return TYPE_NPC_MEMORY_GOOD;
        }

        if ("BAD".equalsIgnoreCase(safe(tone))) {
            return TYPE_NPC_MEMORY_BAD;
        }

        return TYPE_NPC_MEMORY_NEUTRAL;
    }

    private boolean isNpcMemory(String type) {
        return TYPE_NPC_MEMORY_GOOD.equals(type)
                || TYPE_NPC_MEMORY_BAD.equals(type)
                || TYPE_NPC_MEMORY_NEUTRAL.equals(type);
    }

    private int clampAttitude(int value) {
        return Math.max(-10, Math.min(10, value));
    }

    private String safe(String value) {
        return value == null
                ? ""
                : value.trim().replaceAll("\\s+", " ");
    }

    public static class UndoResult {
        private final boolean success;
        private final String message;

        private UndoResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public static UndoResult success() {
            return new UndoResult(true, "Изменение отменено.");
        }

        public static UndoResult failed(String message) {
            return new UndoResult(false, message);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }
    }
}
