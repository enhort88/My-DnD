package com.example.mydnd.director;

/** Gemma 4 native tool declaration and compact semantic contracts. */
public final class DirectorToolSpec {

    private DirectorToolSpec() {
    }

    public static String declaration() {
        return "<|tool>declaration:director_action{"
                + "description:<|\"|>One RPG state action.<|\"|>,"
                + "parameters:{properties:{"
                + "type:{type:<|\"|>STRING<|\"|>},"
                + "name:{type:<|\"|>STRING<|\"|>},"
                + "value:{type:<|\"|>STRING<|\"|>},"
                + "details:{type:<|\"|>STRING<|\"|>}"
                + "},required:[<|\"|>type<|\"|>,<|\"|>name<|\"|>,<|\"|>value<|\"|>,<|\"|>details<|\"|>],"
                + "type:<|\"|>OBJECT<|\"|>}"
                + "}<tool|>";
    }

    public static String compactRules() {
        return compactRules(DirectorMode.PLAYER_ACTION);
    }

    public static String compactRules(DirectorMode mode) {
        DirectorMode safeMode = mode == null
                ? DirectorMode.PLAYER_ACTION
                : mode;

        if (safeMode == DirectorMode.RANDOM_WORLD_EVENT) {
            return randomWorldEventRules();
        }

        return playerActionRules();
    }

    private static String playerActionRules() {
        return "\nТы мастер DnD. Сначала обрабатывай ТОЛЬКО прямые последствия PLAYER_ACTION через director_action."
                + "\nSTATE BEFORE — справочник, НЕ список задач. Не создавай action только потому, что факт уже есть в STATE BEFORE."
                + "\nНе пересохраняй атмосферу, существующих NPC, известные события, эффекты или квесты без нового изменения от PLAYER_ACTION."
                + "\nПосле каждого response спроси: есть ли ещё ОДНО прямое последствие именно этого PLAYER_ACTION? Нет → немедленно DONE."
                + "\nМаксимум 4 реальных изменения за ход. Случайные события мира здесь запрещены."
                + "\nКоды: INV_ADD/INV_REMOVE name=предмет; HP name=PLAYER или точный NPC value=+N/-N; MONEY name=PLAYER value=+N/-N."
                + "\nNPC_UPSERT/NPC_STATUS/NPC_MEMORY name=NPC; MEMORY value=GOOD/BAD/NEUTRAL."
                + "\nWORLD_ADD/WORLD_UPDATE/WORLD_RESOLVE только если PLAYER_ACTION прямо изменил мир; value=важность 1-3 или пусто."
                + "\nQUEST_START/QUEST_UPDATE/QUEST_COMPLETE/QUEST_FAIL; ABILITY_ADD/UPDATE/REMOVE value=SKILL/SPELL/TRAIT/POWER."
                + "\nEFFECT_ADD/EFFECT_REMOVE; LOCATION name=локация."
                + "\nCHECK name=STR/DEX/INT/CHA value=5-25 details=причина; до броска исход не описывай."
                + "\nDONE: name/value/details пустые; может быть первым. Не печатай [CHECK], [NPC_MEMORY] или плашки текстом."
                + "\nПосле подтверждённого DONE продолжи сцену по-русски кратко и атмосферно. Кубики не бросай. За игрока не решай.";
    }

    private static String randomWorldEventRules() {
        return "\nРежим RANDOM_WORLD_EVENT. Создай одно НОВОЕ редкое автономное событие мира."
                + "\nИзвестное состояние — только справочник. Не пересказывай атмосферу и не повторяй существующие факты."
                + "\nРазрешены только WORLD_ADD, NPC_UPSERT, QUEST_START, EFFECT_ADD и DONE."
                + "\nСначала одно основное событие. Допускается максимум одно прямое следствие. Затем немедленно DONE."
                + "\nСобытие не должно управлять персонажем игрока и не должно переписывать прошлое."
                + "\nПосле DONE опиши событие одним коротким атмосферным абзацем по-русски.";
    }
}
