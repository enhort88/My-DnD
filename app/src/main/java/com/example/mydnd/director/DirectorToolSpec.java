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
            return randomWorldEventRules() + SETTING_CONSISTENCY_RULE;
        }

        if (safeMode == DirectorMode.CHECK_RESULT) {
            return checkResultRules() + SETTING_CONSISTENCY_RULE;
        }

        return playerActionRules() + SETTING_CONSISTENCY_RULE;
    }

    /**
     * Universal narrative-consistency guardrail, appended to every mode's
     * rules once here rather than duplicated in each. Not specific to any
     * one detail (weather, terrain, lighting, etc.) - the model tends to
     * reach for genre-stock phrasing (e.g. snow/cold imagery for any injury
     * narrative) that can contradict CURRENT_SCENE/WORLD regardless of what
     * kind of detail it is.
     */
    private static final String SETTING_CONSISTENCY_RULE =
            "\nНикакая деталь нарратива (погода, климат, освещение, ландшафт, время суток,"
                    + " звуки, окружение) не должна противоречить CURRENT_SCENE/WORLD;"
                    + " при сомнении не упоминай её вовсе, а не выдумывай жанровый штамп.";

    private static String playerActionRules() {
        return "\nТы мастер DnD. СНАЧАЛА зафиксируй только прямые новые последствия PLAYER_ACTION через director_action."
                + "\nSTATE BEFORE — справочник, не задачи. Не пересохраняй старые факты и не создавай случайные события."
                + "\nПосле response: есть ещё одно прямое последствие? Нет → DONE. Максимум 4 изменения."
                + "\nCHECK только при реальном риске и отдельном значимом последствии провала; ожидание, осмотр и обычное безопасное движение → DONE."
                + "\nПример: «Я осматриваюсь», «Я беру предмет» → просто DONE или один INV_ADD; без CHECK, без HP, без LOCATION."
                + "\nHP: PLAYER или точный NPC, value=+N/-N. Числовой урон/лечение — только HP."
                + "\nINV_*: точный предмет. MONEY: PLAYER +N/-N. NPC_*: точный NPC; MEMORY=GOOD/BAD/NEUTRAL."
                + "\nWORLD_* только для нового долгого изменения мира; обычное действие или шум не событие."
                + "\nQUEST_*, ABILITY_*, EFFECT_*, LOCATION — только при реальном новом изменении."
                + "\nCHECK: STR/DEX/INT/CHA, DC 5-25, details=только причина; до броска не пиши исход."
                + "\nDONE: пустые поля. confirmed в DONE-response — единственная истина об изменениях."
                + "\nЕсли реального изменения нет — сразу DONE; не выдумывай QUEST_START/EFFECT_ADD/HP/MONEY/LOCATION/NPC_MEMORY без причины, напрямую вызванной действием игрока."
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
