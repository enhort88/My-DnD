package com.example.mydnd.prompt;

import com.example.mydnd.game.GameEvent;
import com.example.mydnd.llm.GenerationProfile;
import com.example.mydnd.memory.MemoryContext;


public class PromptBuilder {
    private static final int MAX_RECENT_HISTORY_CHARS = 500;

    public String buildPrompt(
            String playerText,
            MemoryContext memoryContext,
            GenerationProfile profile
    ) {

        String recentEvents =
                buildRecentEvents(memoryContext);

        String relevantFacts =
                buildRelevantFacts(memoryContext);

        String actionHint =
                buildActionHint(playerText);

        StringBuilder prompt = new StringBuilder();

// Режим thinking должен быть только один раз,
// самой первой строкой prompt.

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

        prompt.append("\n\nSTYLE:");
        prompt.append("\nРусский язык.");
        prompt.append("\nМрачное фэнтези.");
        prompt.append("\nАтмосферно, но без лишнего пафоса.");

        prompt.append("\n\nCURRENT_SCENE:");
        prompt.append("\nСтарая придорожная таверна.");
        prompt.append("\nНочь. Дождь. Внутри тихо и тревожно.");

        if (memoryContext.hasSummary()) {
            prompt.append("\n\nSUMMARY:");
            prompt.append("\n");
            prompt.append(memoryContext.getLatestSummary());
        }

        if (!recentEvents.isEmpty()) {
            prompt.append("\n\nRECENT_EVENTS:");
            prompt.append("\n");
            prompt.append(recentEvents);
        }

        if (memoryContext.hasRelevantFacts()) {
            prompt.append("\n\nRELEVANT_FACTS:");
            prompt.append("\n");
            prompt.append(relevantFacts);
        }

        prompt.append("\n\nPLAYER_ACTION:");
        prompt.append("\n");
        prompt.append(playerText);

        prompt.append(actionHint);

        prompt.append("\n\nTASK:");
        prompt.append("\nПродолжи сцену на один небольшой шаг.");
        prompt.append("\nПокажи реакцию мира и последствия действия.");
        prompt.append("\nНе повторяй уже описанное без причины.");
        prompt.append("\nЗаверши ответ логично.");

        prompt.append("\n\nANSWER:");

        return prompt.toString();
    }

//    private String buildRecentHistory(List<GameEvent> events, int maxChars) {
//        StringBuilder builder = new StringBuilder();
//
//        for (GameEvent event : events) {
//            if (!event.isIncludeInPrompt()) {
//                continue;
//            }
//
//            if (event.getSpeaker() == GameEvent.Speaker.PLAYER) {
//                builder.append("Игрок: ");
//            } else if (event.getSpeaker() == GameEvent.Speaker.MASTER) {
//                builder.append("Мастер: ");
//            } else {
//                continue;
//            }
//
//            builder.append(event.getText().trim());
//            builder.append("\n");
//        }
//
//        String result = builder.toString();
//
//        if (result.length() <= maxChars) {
//            return result;
//        }
//
//        return result.substring(result.length() - maxChars);
//    }

    private String buildActionHint(String playerText) {
        if (playerText == null) {
            return "";
        }

        String normalized = playerText.trim();

        if (normalized.length() > 12) {
            return "";
        }

        return "\nПодсказка: ввод короткий, интерпретируй по сцене.";
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

                builder.append("PLAYER: ");

            } else if (event.getSpeaker()
                    == GameEvent.Speaker.MASTER) {

                builder.append("DM: ");

            } else {
                continue;
            }

            builder.append(
                    event.getText().trim()
            );

            builder.append("\n");
        }

        String result =
                builder.toString().trim();

        if (result.length()
                <= MAX_RECENT_HISTORY_CHARS) {

            return result;
        }

        // Оставляем самую свежую часть истории.
        int startIndex =
                result.length()
                        - MAX_RECENT_HISTORY_CHARS;

        // По возможности начинаем с целого события,
        // а не с середины предложения.
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
                .substring(startIndex)
                .trim();
    }
    private String buildRelevantFacts(MemoryContext memoryContext) {
        StringBuilder builder = new StringBuilder();

        for (String fact : memoryContext.getRelevantFacts()) {
            builder.append("- ");
            builder.append(fact);
            builder.append("\n");
        }

        return builder.toString().trim();
    }
}
