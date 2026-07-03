package com.example.mydnd.llm;

public class ResponseCleaner {

    public String clean(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String result = text;

        result = result.replace("Мастер:", "");
        result = result.replace("Игрок:", "");

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
        return line.startsWith("SYSTEM:")
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
}