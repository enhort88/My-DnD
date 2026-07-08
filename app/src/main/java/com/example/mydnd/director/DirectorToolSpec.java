package com.example.mydnd.director;

/** Gemma 4 native tool declaration and compact semantic contracts. */
public final class DirectorToolSpec {

    private DirectorToolSpec() {
    }

    public static String declaration() {
        return "<|tool>declaration:director_action{"
                + "description:<|\"|>One RPG state action.<|\"|>,"
                + "parameters:{properties:{"
                + "type:{description:<|\"|>Action code. Direct numeric damage or healing must use HP.<|\"|>,type:<|\"|>STRING<|\"|>},"
                + "name:{description:<|\"|>Exact target/item/entity required by the chosen type.<|\"|>,type:<|\"|>STRING<|\"|>},"
                + "value:{description:<|\"|>Type-specific value only; otherwise empty. Never embed another action here.<|\"|>,type:<|\"|>STRING<|\"|>},"
                + "details:{description:<|\"|>Short cause or fact only. Never embed another action.<|\"|>,type:<|\"|>STRING<|\"|>}"
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
        return "\nТы мастер DnD. Сначала обрабатывай ТОЛЬКО прямые последствия PLAYER_ACTION через director_action."
                + "\nSTATE BEFORE — справочник, НЕ список задач. Не создавай action только потому, что факт уже есть в STATE BEFORE."
                + "\nНе пересохраняй атмосферу, существующих NPC, известные события, эффекты или квесты без нового изменения от PLAYER_ACTION."
                + "\nПосле каждого response внутренне проверь: есть ли ещё ОДНО прямое последствие именно этого PLAYER_ACTION? Нет → немедленно DONE.Никогда не выводи эту проверку пользователю"
                + "\nМаксимум 4 реальных изменения за ход. Случайные события мира здесь запрещены."
                + "\nКоды: INV_ADD/INV_REMOVE name=предмет; HP name=PLAYER или точный NPC value=+N/-N; MONEY name=PLAYER value=+N/-N."
                + "\nЧисловой урон или лечение ВСЕГДА оформляй отдельным HP. Если PLAYER_ACTION прямо говорит о N урона — HP name=PLAYER value=-N. Никогда не прячь HP внутри name/value/details другого action."
                + "\nНе используй EFFECT_ADD вместо изменения HP. EFFECT_ADD — только отдельное длительное состояние, не числовой урон."
                + "\nNPC_UPSERT/NPC_STATUS/NPC_MEMORY name=NPC; MEMORY value=GOOD/BAD/NEUTRAL."
                + "\nWORLD_ADD/WORLD_RESOLVE только если PLAYER_ACTION прямо изменил мир. WORLD_UPDATE — редкий: только для уже существующего WORLD_EVENTS с точным name; если нужен вместе с другими изменениями, вызывай первым. value=важность 1-3 или пусто."
                + "\nQUEST_START/QUEST_UPDATE/QUEST_COMPLETE/QUEST_FAIL; ABILITY_ADD/UPDATE/REMOVE value=SKILL/SPELL/TRAIT/POWER."
                + "\nEFFECT_ADD/EFFECT_REMOVE; LOCATION name=локация."
                + "\nCHECK name=STR/DEX/INT/CHA value=5-25 details=причина; до броска исход не описывай."
                + "\nDONE: name/value/details пустые; может быть первым. Не печатай [CHECK], [NPC_MEMORY] или плашки текстом."
                + "\nВ DONE-response поле confirmed — единственный источник реально применённых изменений. narrative_rule=CONFIRMED_ONLY: не утверждай изменение HP, денег, инвентаря, статуса, эффектов или других данных, если его нет в confirmed. confirmed=NONE означает, что состояние не изменилось."
                + "\nПосле подтверждённого DONE продолжи сцену по-русски кратко и атмосферно. Кубики не бросай. За игрока не решай.";
    }

    private static String checkResultRules() {
        return "\nРежим CHECK_RESULT. Проверка уже завершена; RECENT_EVENTS содержит PLAYER_ACTION и SYSTEM-результат броска."
                + "\nПеред рассказом зафиксируй максимум ОДНО непосредственное механическое последствие результата."
                + "\nРазрешены только HP, EFFECT_ADD, LOCATION и DONE. CHECK запрещён."
                + "\nЕсли ПРОВАЛ прямо привёл к физической травме, падению или удару — используй HP name=PLAYER value=-N, обычно 1-6. Не заменяй числовой урон EFFECT_ADD."
                + "\nНе снимай HP за социальный, умственный или безвредный провал без прямой физической травмы."
                + "\nЕсли механического последствия нет — сразу DONE. После одного APPLIED действия следующий action обязан быть DONE."
                + "\nВ DONE-response поле confirmed — единственный источник реально применённых изменений; не утверждай HP/эффект/локацию вне confirmed."
                + "\nПосле DONE кратко опиши непосредственный итог проверки по-русски и остановись перед новым выбором игрока.";
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
