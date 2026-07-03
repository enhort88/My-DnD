package com.example.mydnd.prompt;

import com.example.mydnd.game.GameEvent;
import com.example.mydnd.llm.GenerationProfile;

import java.util.List;

public class PromptBuilder {

    private static final int MAX_RECENT_HISTORY_CHARS = 350;

    public String buildPrompt(
            String playerText,
            List<GameEvent> events,
            boolean useThinking,
            GenerationProfile profile
    ) {
        String thinkingMode = useThinking ? "/think" : "/no_think";

        String recentHistory = buildRecentHistory(events, MAX_RECENT_HISTORY_CHARS);
        String actionHint = buildActionHint(playerText);

        return thinkingMode +
                "\nSYSTEM:" +
                "\nТы мастер настольной RPG в духе DnD." +
                "\nПиши только художественный ответ мастера." +
                "\nНе пиши SYSTEM, TASK, SCENE, HISTORY, PLAYER_ACTION." +
                "\nНе пиши 'Мастер:' или 'Игрок:'." +
                "\nНе объясняй правила промпта." +
                "\nНе повторяй одни и те же слова или фразы подряд." +
                "\nНе решай за персонажа игрока." +
                "\nКубики не бросай. Если есть риск — попроси проверку." +
                "\n\nSTYLE:" +
                "\nРусский язык. Мрачное фэнтези. Атмосферно, но без воды." +
                "\n\nSCENE:" +
                "\nСтарая придорожная таверна. Ночь. Дождь. Внутри тихо и тревожно." +
                "\n\nHISTORY:" +
                "\n" + recentHistory +
                "\nPLAYER_ACTION:" +
                "\n" + playerText +
                actionHint +
                "\n\nTASK:" +
                "\nОтветь 4-8 предложениями: что происходит, что замечает герой, какие есть последствия или выбор." +
                "\nФинал — короткий вопрос или выбор для игрока." +
                "\n\nANSWER:";
    }

    private String buildRecentHistory(List<GameEvent> events, int maxChars) {
        StringBuilder builder = new StringBuilder();

        for (GameEvent event : events) {
            if (!event.isIncludeInPrompt()) {
                continue;
            }

            if (event.getSpeaker() == GameEvent.Speaker.PLAYER) {
                builder.append("Игрок: ");
            } else if (event.getSpeaker() == GameEvent.Speaker.MASTER) {
                builder.append("Мастер: ");
            } else {
                continue;
            }

            builder.append(event.getText().trim());
            builder.append("\n");
        }

        String result = builder.toString();

        if (result.length() <= maxChars) {
            return result;
        }

        return result.substring(result.length() - maxChars);
    }

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
}
