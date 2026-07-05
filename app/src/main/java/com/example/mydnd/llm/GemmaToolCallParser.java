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

    private static final Pattern NAME_STRING_PATTERN =
            Pattern.compile(
                    "(?:^|,)\\s*name\\s*:\\s*<\\|\"\\|>(.*?)<\\|\"\\|>",
                    Pattern.DOTALL
            );

    private static final Pattern NAME_FALLBACK_PATTERN =
            Pattern.compile(
                    "(?:^|,)\\s*name\\s*:\\s*([^,}]*)",
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
            return Result.noCall(
                    safeRaw
            );
        }

        Matcher callMatcher =
                TOOL_CALL_PATTERN.matcher(
                        safeRaw
                );

        if (!callMatcher.find()) {
            return Result.noCall(
                    safeRaw
            );
        }

        String functionName =
                callMatcher
                        .group(1)
                        .trim();

        String arguments =
                callMatcher
                        .group(2)
                        .trim();

        String itemName =
                extractName(
                        arguments
                );

        return Result.toolCall(
                functionName,
                itemName,
                safeRaw
        );
    }


    private String extractName(
            String arguments
    ) {
        Matcher stringMatcher =
                NAME_STRING_PATTERN.matcher(
                        arguments
                );

        if (stringMatcher.find()) {
            return stringMatcher
                    .group(1)
                    .trim();
        }

        Matcher fallbackMatcher =
                NAME_FALLBACK_PATTERN.matcher(
                        arguments
                );

        if (fallbackMatcher.find()) {
            return fallbackMatcher
                    .group(1)
                    .trim();
        }

        return "";
    }


    public static class Result {

        private final boolean toolCall;
        private final String functionName;
        private final String fact;
        private final String rawText;


        private Result(
                boolean toolCall,
                String functionName,
                String fact,
                String rawText
        ) {
            this.toolCall = toolCall;
            this.functionName = functionName;
            this.fact = fact;
            this.rawText = rawText;
        }


        public static Result noCall(
                String rawText
        ) {
            return new Result(
                    false,
                    "",
                    "",
                    rawText
            );
        }


        public static Result toolCall(
                String functionName,
                String fact,
                String rawText
        ) {
            return new Result(
                    true,
                    functionName,
                    fact,
                    rawText
            );
        }


        public boolean hasToolCall() {
            return toolCall;
        }


        public String getFunctionName() {
            return functionName;
        }


        /**
         * Kept for compatibility with the current MainActivity test UI.
         * For inventory tools this value is the item name.
         */
        public String getFact() {
            return fact;
        }


        public String getRawText() {
            return rawText;
        }
    }
}
