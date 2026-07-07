package com.example.mydnd.llm;

public class ResponseCleaner {

    public String clean(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String result = text;

        result = result.replace("Мастер:", "");
        result = result.replace("Игрок:", "");
        result = removeReasoningTail(result);

        String[] lines = result.split("\n");
        StringBuilder cleaned = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                cleaned.append("\n");
                continue;
            }

            if (isServiceLine(trimmed)) {
                continue;
            }

            cleaned.append(line).append("\n");
        }

        return cleaned.toString().trim();
    }

    private boolean isServiceLine(String line) {
        return line.startsWith("[NPC_MEMORY|")
                || line.startsWith("[CHECK|")
                || line.startsWith("SYSTEM:")
                || line.startsWith("STYLE:")
                || line.startsWith("SCENE:")
                || line.startsWith("HISTORY:")
                || line.startsWith("PLAYER_ACTION:")
                || line.startsWith("TASK:")
                || line.startsWith("ANSWER:")
                || line.startsWith("Сцена:")
                || line.startsWith("История:")
                || line.startsWith("Действие игрока:")
                || line.startsWith("Ответ:")
                || line.startsWith("Подсказка:")
                || line.startsWith("Напомню:")
                || line.startsWith("Ты должен")
                || line.startsWith("В ответе")
                || line.startsWith("Предлагается выбор");
    }
    private String removeReasoningTail(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }

        String lowerText =
                text.toLowerCase(java.util.Locale.ROOT);

        String[] markers = {
                "\nokay, let me ",
                "\nokay, let's ",
                "\nlet me think",
                "\nlet's think",
                "\nwe need to ",
                "\ni need to ",
                "\nthe user ",
                "\nthe current situation ",
                "\nbased on the given information",
                "\ni should ",
                "\ni will "
        };

        int cutIndex = -1;

        for (String marker : markers) {
            int index = lowerText.indexOf(marker);

            if (index >= 0
                    && (cutIndex < 0 || index < cutIndex)) {
                cutIndex = index;
            }
        }

        if (cutIndex >= 0) {
            android.util.Log.w(
                    "MyDND_CLEANER",
                    "Removed reasoning tail: "
                            + text.substring(cutIndex)
            );

            return text
                    .substring(0, cutIndex)
                    .trim();
        }

        return text.trim();
    }
}