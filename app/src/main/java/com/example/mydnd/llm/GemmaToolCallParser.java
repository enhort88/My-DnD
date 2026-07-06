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


    public static class Result {

        private final boolean toolCall;
        private final String functionName;
        private final String itemName;
        private final String worldEventText;
        private final String rawText;


        private Result(
                boolean toolCall,
                String functionName,
                String itemName,
                String worldEventText,
                String rawText
        ) {
            this.toolCall = toolCall;
            this.functionName = functionName;
            this.itemName = itemName;
            this.worldEventText = worldEventText;
            this.rawText = rawText;
        }


        public static Result noCall(String rawText) {
            return new Result(
                    false,
                    "",
                    "",
                    "",
                    rawText
            );
        }


        public static Result toolCall(
                String functionName,
                String itemName,
                String worldEventText,
                String rawText
        ) {
            return new Result(
                    true,
                    functionName,
                    itemName,
                    worldEventText,
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
