package com.example.mydnd.llm;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GemmaToolCallParser {

    private static final Pattern TOOL_CALL_PATTERN =
            Pattern.compile(
                    "(?:<\\|tool_call>\\s*call:)?"
                            + "([A-Za-z_][A-Za-z0-9_]*)\\{(.*?)\\}"
                            + "(?:<tool_call\\|>)?",
                    Pattern.DOTALL
            );


    public Result parse(
            String rawText
    ) {
        String safeRaw =
                rawText == null
                        ? ""
                        : rawText.trim();

        if (safeRaw.equalsIgnoreCase("NONE")) {
            return Result.noCall(safeRaw);
        }

        Matcher callMatcher =
                TOOL_CALL_PATTERN.matcher(safeRaw);

        if (!callMatcher.find()) {
            return Result.noCall(safeRaw);
        }

        String functionName = callMatcher.group(1).trim();
        String arguments = callMatcher.group(2).trim();

        return Result.toolCall(
                functionName,
                extractStringArgument(arguments, "name"),
                extractStringArgument(arguments, "text"),
                extractIntArgument(arguments, "importance", 1),
                safeRaw
        );
    }


    private String extractStringArgument(
            String arguments,
            String key
    ) {
        Pattern quotedPattern = Pattern.compile(
                "(?:^|,)\\s*"
                        + Pattern.quote(key)
                        + "\\s*:\\s*<\\|\"\\|>(.*?)<\\|\"\\|>",
                Pattern.DOTALL
        );

        Matcher quotedMatcher = quotedPattern.matcher(arguments);

        if (quotedMatcher.find()) {
            return quotedMatcher.group(1).trim();
        }

        Pattern fallbackPattern = Pattern.compile(
                "(?:^|,)\\s*"
                        + Pattern.quote(key)
                        + "\\s*:\\s*([^,}]*)",
                Pattern.DOTALL
        );

        Matcher fallbackMatcher = fallbackPattern.matcher(arguments);

        if (fallbackMatcher.find()) {
            return fallbackMatcher.group(1).trim();
        }

        return "";
    }



    private int extractIntArgument(
            String arguments,
            String key,
            int fallback
    ) {
        Pattern pattern = Pattern.compile(
                "(?:^|,)\\s*"
                        + Pattern.quote(key)
                        + "\\s*:\\s*(-?\\d+)"
        );

        Matcher matcher = pattern.matcher(arguments);

        if (!matcher.find()) {
            return fallback;
        }

        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public static class Result {

        private final boolean toolCall;
        private final String functionName;
        private final String itemName;
        private final String worldEventText;
        private final int worldEventImportance;
        private final String rawText;


        private Result(
                boolean toolCall,
                String functionName,
                String itemName,
                String worldEventText,
                int worldEventImportance,
                String rawText
        ) {
            this.toolCall = toolCall;
            this.functionName = functionName;
            this.itemName = itemName;
            this.worldEventText = worldEventText;
            this.worldEventImportance = Math.max(1, Math.min(3, worldEventImportance));
            this.rawText = rawText;
        }


        public static Result noCall(String rawText) {
            return new Result(
                    false,
                    "",
                    "",
                    "",
                    1,
                    rawText
            );
        }


        public static Result toolCall(
                String functionName,
                String itemName,
                String worldEventText,
                int worldEventImportance,
                String rawText
        ) {
            return new Result(
                    true,
                    functionName,
                    itemName,
                    worldEventText,
                    worldEventImportance,
                    rawText
            );
        }


        public boolean hasToolCall() {
            return toolCall;
        }


        public String getFunctionName() {
            return functionName;
        }


        public String getItemName() {
            return itemName;
        }


        public String getWorldEventText() {
            return worldEventText;
        }

        public int getWorldEventImportance() {
            return worldEventImportance;
        }


        /**
         * Старое имя оставлено для совместимости
         * с экспериментальным UI.
         */
        public String getFact() {
            return getItemName();
        }


        public String getRawText() {
            return rawText;
        }
    }
}
