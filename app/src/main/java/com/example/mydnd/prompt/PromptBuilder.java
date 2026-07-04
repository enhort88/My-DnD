package com.example.mydnd.prompt;

import com.example.mydnd.game.GameEvent;
import com.example.mydnd.memory.MemoryContext;

public class PromptBuilder {

    private static final int MAX_RECENT_HISTORY_CHARS =
            500;


    public String buildPrompt(
            String playerText,
            MemoryContext memoryContext
    ) {
        String recentEvents =
                buildRecentEvents(
                        memoryContext
                );

        String relevantFacts =
                buildRelevantFacts(
                        memoryContext
                );

        String actionHint =
                buildActionHint(
                        playerText
                );

        StringBuilder prompt =
                new StringBuilder();


        prompt.append("SYSTEM:");

        prompt.append(
                "\nТы — мастер настольной RPG в духе DnD."
        );

        prompt.append(
                "\nРеагируй только на текущее действие игрока и веди сцену на один небольшой шаг."
        );

        prompt.append(
                "\nЕсли игрок задал прямой вопрос, сначала дай на него ясный ответ, а затем добавь атмосферное описание."
        );

        prompt.append(
                "\nНе игнорируй конкретный вопрос игрока ради атмосферы."
        );

        prompt.append(
                "\nНе продолжай действие персонажа дальше того, что явно написал игрок."
        );

        prompt.append(
                "\nНе заставляй персонажа идти, брать предмет, открывать дверь, атаковать, соглашаться или совершать другое действие без решения игрока."
        );

        prompt.append(
                "\nОписывай реакцию мира, наблюдаемые детали и непосредственные последствия."
        );

        prompt.append(
                "\nОставляй следующее решение за игроком."
        );

        prompt.append(
                "\nНе решай за персонажа игрока."
        );


        prompt.append(
                "\n\nFACTS_AND_CANON:"
        );

        prompt.append(
                "\nRELEVANT_FACTS — подтверждённые факты кампании."
        );

        prompt.append(
                "\nRECENT_EVENTS — самые свежие реальные события."
        );

        prompt.append(
                "\nSUMMARY — краткое описание более старой истории."
        );

        prompt.append(
                "\nПри противоречии доверяй источникам в порядке: RELEVANT_FACTS, затем RECENT_EVENTS, затем SUMMARY."
        );

        prompt.append(
                "\nНе противоречь подтверждённым фактам кампании."
        );

        prompt.append(
                "\nНе превращай сравнения, метафоры и художественные образы в реальные свойства мира."
        );

        prompt.append(
                "\nНе объявляй предмет магическим, проклятым или необычным только потому, что он был художественно описан."
        );

        prompt.append(
                "\nНе придумывай постоянные свойства предметов, NPC и мира без основания в переданных фактах или событиях."
        );

        prompt.append(
                "\nЕсли точный ответ неизвестен, покажи только то, что персонаж действительно может наблюдать, и не выдавай догадку за факт."
        );


        prompt.append(
                "\n\nRULES:"
        );

        prompt.append(
                "\nКубики не бросай."
        );

        prompt.append(
                "\nЕсли действие рискованное и исход не очевиден — попроси проверку."
        );

        prompt.append(
                "\nНе выдумывай результат проверки до броска."
        );


        prompt.append(
                "\n\nOUTPUT:"
        );

        prompt.append(
                "\nПиши только художественный ответ мастера."
        );

        prompt.append(
                "\nНе показывай рассуждения, анализ, план ответа или внутренний монолог."
        );

        prompt.append(
                "\nНачни сразу с ответа на действие игрока."
        );

        prompt.append(
                "\nВесь видимый ответ должен быть только на русском языке."
        );

        prompt.append(
                "\nНе пиши названия служебных блоков."
        );

        prompt.append(
                "\nНе объясняй инструкции."
        );

        prompt.append(
                "\nНе пиши «Мастер:» или «Игрок:»."
        );

        prompt.append(
                "\nНе повторяй одни и те же слова, события или образы без причины."
        );


        prompt.append(
                "\n\nSTYLE:"
        );

        prompt.append(
                "\nМрачное фэнтези."
        );

        prompt.append(
                "\nАтмосферно, но без лишнего пафоса."
        );

        prompt.append(
                "\nОбычно 2–4 коротких абзаца."
        );

        prompt.append(
                "\nНе растягивай ответ, если действие простое."
        );


        if (memoryContext.hasSummary()) {

            prompt.append(
                    "\n\nSUMMARY:"
            );

            prompt.append("\n");

            prompt.append(
                    memoryContext.getLatestSummary()
            );
        }


        if (!recentEvents.isEmpty()) {

            prompt.append(
                    "\n\nRECENT_EVENTS:"
            );

            prompt.append("\n");

            prompt.append(
                    recentEvents
            );
        }


        if (memoryContext.hasRelevantFacts()) {

            prompt.append(
                    "\n\nRELEVANT_FACTS:"
            );

            prompt.append("\n");

            prompt.append(
                    relevantFacts
            );
        }


        prompt.append(
                "\n\nPLAYER_ACTION:"
        );

        prompt.append("\n");

        prompt.append(
                playerText
        );

        prompt.append(
                actionHint
        );


        prompt.append(
                "\n\nTASK:"
        );

        prompt.append(
                "\nОбработай только этот ход игрока."
        );

        prompt.append(
                "\nЕсли есть прямой вопрос — ответь на него прямо."
        );

        prompt.append(
                "\nПокажи непосредственную реакцию мира и последствия уже совершённого действия."
        );

        prompt.append(
                "\nНе совершай новое действие за персонажа игрока."
        );

        prompt.append(
                "\nНе повторяй уже описанное без причины."
        );

        prompt.append(
                "\nЗаверши в точке, где игрок снова может принять решение."
        );


        prompt.append(
                "\n\nANSWER:"
        );

        return prompt.toString();
    }


    private String buildActionHint(
            String playerText
    ) {
        if (playerText == null) {
            return "";
        }

        String normalized =
                playerText.trim();

        if (normalized.length() > 12) {
            return "";
        }

        return "\nПодсказка: ввод короткий, интерпретируй его в контексте последних событий.";
    }


    private String buildRecentEvents(
            MemoryContext memoryContext
    ) {
        StringBuilder builder =
                new StringBuilder();

        for (GameEvent event
                : memoryContext.getRecentEvents()) {

            if (event.getSpeaker()
                    == GameEvent.Speaker.PLAYER) {

                builder.append(
                        "PLAYER: "
                );

            } else if (
                    event.getSpeaker()
                            == GameEvent.Speaker.MASTER
            ) {

                builder.append(
                        "DM: "
                );

            } else {
                continue;
            }

            builder.append(
                    event.getText().trim()
            );

            builder.append("\n");
        }

        String result =
                builder
                        .toString()
                        .trim();

        if (result.length()
                <= MAX_RECENT_HISTORY_CHARS) {

            return result;
        }

        int startIndex =
                result.length()
                        - MAX_RECENT_HISTORY_CHARS;

        int nextLineBreak =
                result.indexOf(
                        '\n',
                        startIndex
                );

        if (nextLineBreak >= 0
                && nextLineBreak
                < startIndex + 150) {

            startIndex =
                    nextLineBreak + 1;
        }

        return result
                .substring(
                        startIndex
                )
                .trim();
    }


    private String buildRelevantFacts(
            MemoryContext memoryContext
    ) {
        StringBuilder builder =
                new StringBuilder();

        for (String fact
                : memoryContext.getRelevantFacts()) {

            builder.append("- ");

            builder.append(
                    fact
            );

            builder.append("\n");
        }

        return builder
                .toString()
                .trim();
    }
}