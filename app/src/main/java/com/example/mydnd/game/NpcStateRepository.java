package com.example.mydnd.game;

import com.example.mydnd.db.AppDatabase;
import com.example.mydnd.db.entity.NpcEntity;

public class NpcStateRepository {

    private final AppDatabase database;

    public NpcStateRepository(AppDatabase database) {
        this.database = database;
    }

    public NpcEntity get(long npcId) {
        return database.npcDao().getById(npcId);
    }

    public boolean applyDamage(long npcId, int damage) {
        NpcEntity npc = get(npcId);

        if (npc == null || damage <= 0) {
            return false;
        }

        int newHp = Math.max(0, npc.hp - damage);

        return database.npcDao().updateHp(
                npcId,
                newHp,
                System.currentTimeMillis()
        ) > 0;
    }

    public boolean heal(long npcId, int amount) {
        NpcEntity npc = get(npcId);

        if (npc == null || amount <= 0) {
            return false;
        }

        int newHp = Math.min(npc.maxHp, npc.hp + amount);

        return database.npcDao().updateHp(
                npcId,
                newHp,
                System.currentTimeMillis()
        ) > 0;
    }

    public boolean changeAttitude(long npcId, int delta) {
        NpcEntity npc = get(npcId);

        if (npc == null || delta == 0) {
            return false;
        }

        int attitude = Math.max(
                -10,
                Math.min(10, npc.attitude + delta)
        );

        return database.npcDao().updateAttitude(
                npcId,
                attitude,
                System.currentTimeMillis()
        ) > 0;
    }

    public boolean move(long npcId, String location) {
        return database.npcDao().updateLocation(
                npcId,
                safe(location),
                System.currentTimeMillis()
        ) > 0;
    }

    public boolean updateKnowledge(long npcId, String knowledge) {
        return database.npcDao().updateKnowledge(
                npcId,
                safe(knowledge),
                System.currentTimeMillis()
        ) > 0;
    }

    public boolean isDead(NpcEntity npc) {
        return npc != null && npc.hp <= 0;
    }

    private String safe(String value) {
        return value == null
                ? ""
                : value.trim().replaceAll("\\s+", " ");
    }
}
