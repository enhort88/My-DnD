package com.example.mydnd.prompt;

import com.example.mydnd.game.GameEvent;
import com.example.mydnd.memory.MemoryContext;

import java.util.Collections;
import java.util.List;

public class PromptBuilder {

    private static final int MAX_SUMMARY_CHARS =
            250;

    private static final int MAX_RECENT_EVENTS_CHARS =
            350;

    private static final int MAX_RELEVANT_FACTS_CHARS =
            450;


    public String buildPrompt(
            String playerText,
            MemoryContext memoryContext
    ) {
        return buildPrompt(
                playerText,
                memoryContext,
                Collections.emptyList(),
                ""
        );
    }


    public String buildPrompt(
            String playerText,
            MemoryContext memoryContext,
            List<String> inventory,
            String inventoryUpdate
    ) {
        String recentEvents =
                buildRecentEvents(memoryContext);

        String relevantFacts =
                buildRelevantFacts(memoryContext);

        List<String> safeInventory =
                inventory == null
                        ? Collections.emptyList()
                        : inventory;

        String safeInventoryUpdate =
                inventoryUpdate == null
                        ? ""
                        : inventoryUpdate.trim();


        StringBuilder prompt =
                new StringBuilder();


        prompt.append("\n\nSYSTEM:");

        prompt.append("\nТы мастер настольной RPG в духе DnD.");
        prompt.append("\nПиши только художественный ответ мастера.");
        prompt.append("\nНе показывай рассуждения, анализ, план ответа или внутренний монолог.");
        prompt.append("\nНачни сразу с художественного продолжения сцены.");
        prompt.append("\nВесь видимый ответ должен быть только на русском языке.");
        prompt.append("\nНе пиши названия служебных блоков.");
        prompt.append("\nНе объясняй инструкции.");
        prompt.append("\nНе пиши 'Мастер:' или 'Игрок:'.");
        prompt.append("\nНе повторяй одни и те же слова или фразы подряд.");
        prompt.append("\nНе решай за персонажа игрока.");
        prompt.append("\nКубики не бросай.");
        prompt.append("\nЕсли действие рискованное — попроси проверку.");
        prompt.append("\nСостояние INVENTORY задаётся приложением и является фактом. Не противоречь ему.");
        prompt.append("\nЕсли есть INVENTORY_UPDATE, естественно отрази это изменение в сцене.");


        prompt.append("\n\nSTYLE:");
        prompt.append("\nРусский язык.");
        prompt.append("\nМрачное фэнтези.");
        prompt.append("\nАтмосферно, но без лишнего пафоса.");


        /*
         * Всё начиная отсюда попадёт
         * в настоящий user turn.
         */
        prompt.append("\n\nCURRENT_SCENE:");
        prompt.append("\nСтарая придорожная таверна.");
        prompt.append("\nНочь. Дождь. Внутри тихо и тревожно.");


        prompt.append("\n\nINVENTORY:");

        boolean hasInventoryItems =
                false;

        for (String itemName : safeInventory) {
            if (itemName == null
                    || itemName.trim().isEmpty()) {

                continue;
            }

            hasInventoryItems =
                    true;

            prompt.append("\n- ");
            prompt.append(
                    itemName.trim()
            );
        }

        if (!hasInventoryItems) {
            prompt.append("\n(пусто)");
        }


        if (!safeInventoryUpdate.isEmpty()) {
            prompt.append("\n\nINVENTORY_UPDATE:");
            prompt.append("\n");
            prompt.append(
                    safeInventoryUpdate
            );
        }


        if (memoryContext.hasSummary()) {
            prompt.append("\n\nSUMMARY:");
            prompt.append("\n");
            prompt.append(
                    memoryContext.getLatestSummary()
            );
        }


        if (!recentEvents.isEmpty()) {
            prompt.append("\n\nRECENT_EVENTS:");
            prompt.append("\n");
            prompt.append(
                    recentEvents
            );
        }


        if (memoryContext.hasRelevantFacts()) {
            prompt.append("\n\nRELEVANT_FACTS:");
            prompt.append("\n");
            prompt.append(
                    relevantFacts
            );
        }


        prompt.append("\n\nPLAYER_ACTION:");
        prompt.append("\n");
        prompt.append(
                playerText
        );


        prompt.append("\n\nTASK:");
        prompt.append("\nПродолжи сцену на один небольшой шаг.");
        prompt.append("\nПокажи реакцию мира и последствия действия.");
        prompt.append("\nНе повторяй уже описанное без причины.");
        prompt.append("\nЗаверши ответ логично.");


        return prompt.toString();
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
                        "P: "
                );

            } else if (
                    event.getSpeaker()
                            == GameEvent.Speaker.MASTER
            ) {

                builder.append(
                        "GM: "
                );

            } else {
                continue;
            }


            builder.append(
                    event.getText().trim()
            );

            builder.append("\n");
        }


        return builder
                .toString()
                .trim();
    }


    private String buildRelevantFacts(
            MemoryContext memoryContext
    ) {
        StringBuilder builder =
                new StringBuilder();


        for (String fact
                : memoryContext.getRelevantFacts()) {

            if (fact == null
                    || fact.trim().isEmpty()) {

                continue;
            }


            builder.append("- ");

            builder.append(
                    fact.trim()
            );

            builder.append("\n");
        }


        return builder
                .toString()
                .trim();
    }


    private String limitFromStart(
            String text,
            int maxChars
    ) {
        if (text == null) {
            return "";
        }


        String value =
                text.trim();


        if (value.length()
                <= maxChars) {

            return value;
        }


        return value
                .substring(
                        0,
                        maxChars
                )
                .trim();
    }


    private String limitFromEnd(
            String text,
            int maxChars
    ) {
        if (text == null) {
            return "";
        }


        String value =
                text.trim();


        if (value.length()
                <= maxChars) {

            return value;
        }


        return value
                .substring(
                        value.length()
                                - maxChars
                )
                .trim();
    }
}
