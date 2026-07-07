package com.example.mydnd.director;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses Gemma 4 native tool-call syntax without treating narrative text as state. */
public final class DirectorActionParser {

    private static final Pattern CALL_PATTERN = Pattern.compile(
            "call\\s*:\\s*director_action\\s*\\{(.*?)\\}",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern SPECIAL_QUOTED_FIELD = Pattern.compile(
            "([a-zA-Z_]+)\\s*:\\s*<\\|\"\\|>(.*?)<\\|\"\\|>",
            Pattern.DOTALL
    );

    private static final Pattern NORMAL_QUOTED_FIELD = Pattern.compile(
            "([a-zA-Z_]+)\\s*:\\s*\"(.*?)\"",
            Pattern.DOTALL
    );

    public DirectorAction parse(String rawToolCall) {
        if (rawToolCall == null || rawToolCall.trim().isEmpty()) {
            throw new IllegalArgumentException("Empty tool call");
        }

        Matcher callMatcher = CALL_PATTERN.matcher(rawToolCall);
        if (!callMatcher.find()) {
            throw new IllegalArgumentException("director_action call not found");
        }

        String body = callMatcher.group(1);
        Map<String, String> fields = new LinkedHashMap<>();
        collectFields(SPECIAL_QUOTED_FIELD, body, fields);
        collectFields(NORMAL_QUOTED_FIELD, body, fields);

        String typeValue = fields.get("type");
        if (typeValue == null || typeValue.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing tool field: type");
        }

        return new DirectorAction(
                DirectorActionType.fromToolValue(typeValue),
                fields.get("name"),
                fields.get("value"),
                fields.get("details")
        );
    }

    private void collectFields(
            Pattern pattern,
            String body,
            Map<String, String> target
    ) {
        Matcher matcher = pattern.matcher(body);
        while (matcher.find()) {
            target.putIfAbsent(
                    matcher.group(1).trim().toLowerCase(java.util.Locale.ROOT),
                    matcher.group(2).trim()
            );
        }
    }
}
