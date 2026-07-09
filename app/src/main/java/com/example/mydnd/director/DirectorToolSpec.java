package com.example.mydnd.director;

/** Gemma 4 native tool declaration and compact semantic contracts. */
public final class DirectorToolSpec {

    private DirectorToolSpec() {
    }

    public static String declaration() {
        return "<|tool>declaration:director_action{"
                + "description:<|\"|>One state action.<|\"|>,"
                + "parameters:{properties:{"
                + "type:{description:<|\"|>Action code; numeric damage/heal=HP.<|\"|>,type:<|\"|>STRING<|\"|>},"
                + "name:{description:<|\"|>Exact target/item/entity.<|\"|>,type:<|\"|>STRING<|\"|>},"
                + "value:{description:<|\"|>Type value only; no nested action.<|\"|>,type:<|\"|>STRING<|\"|>},"
                + "details:{description:<|\"|>Short cause/fact; no nested action.<|\"|>,type:<|\"|>STRING<|\"|>}"
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

        if (safeMode == DirectorMode.CHECK_RESULT) {
            return checkResultRules();
        }

        return playerActionRules();
    }

    private static String playerActionRules() {
        return "\nТы мастер DnD. СНАЧАЛА зафиксируй только прямые новые последствия PLAYER_ACTION через director_action."
                + "\nSTATE BEFORE — справочник, не задачи. Не пересохраняй старые факты и не создавай случайные события."
                + "\nПосле response: есть ещё одно прямое последствие? Нет → DONE. Максимум 4 изменения."
                + "\nCHECK только при реальном риске и отдельном значимом последствии провала; ожидание, осмотр и обычное безопасное движение → DONE."
                + "\nHP: PLAYER или точный NPC, value=+N/-N. Числовой урон/лечение — только HP."
                + "\nINV_*: точный предмет. MONEY: PLAYER +N/-N. NPC_*: точный NPC; MEMORY=GOOD/BAD/NEUTRAL."
                + "\nWORLD_* только для нового долгого изменения мира; обычное действие или шум не событие."
                + "\nQUEST_*, ABILITY_*, EFFECT_*, LOCATION — только при реальном новом изменении."
                + "\nCHECK: STR/DEX/INT/CHA, DC 5-25, details=только причина; до броска не пиши исход."
                + "\nDONE: пустые поля. confirmed в DONE-response — единственная истина об изменениях."
                + "\nЕсли реального изменения нет — сразу DONE; не выдумывай QUEST_START/EFFECT_ADD/HP/MONEY без причины."
                + "\nПосле DONE продолжи сцену 2-4 атмосферными предложениями на языке PLAYER_ACTION (если язык неочевиден — по-русски); не технический отчёт, не решай за игрока.";
    }


    private static String checkResultRules() {
        return "\nРежим CHECK_RESULT. Бросок ниже уже завершён; OUTCOME — абсолютная истина."
                + "\nВыбери ровно одно: HP при прямой физической травме, иначе DONE. Другие действия и CHECK запрещены."
                + "\nПосле tool опиши результат 2-4 атмосферными предложениями без слова «Итог:» и без новых механических изменений.";
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
