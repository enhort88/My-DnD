package com.example.mydnd.director;

/** Builds the short same-context tool response consumed by Gemma 4. */
public final class DirectorToolResponseBuilder {

    public String build(DirectorResult result) {
        return "<|tool_response>response:director_action{"
                + "status:<|\"|>" + safe(result.getStatus().name()) + "<|\"|>,"
                + "code:<|\"|>" + safe(result.getCode()) + "<|\"|>,"
                + "state_after:<|\"|>" + safeState(result.getStateAfter()) + "<|\"|>"
                + "}<tool_response|>";
    }

    private String safeState(String value) {
        String safe = safe(value);
        final int maxChars = 180;
        if (safe.length() <= maxChars) {
            return safe;
        }
        return safe.substring(0, maxChars - 1).trim() + "…";
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace("<", "")
                .replace(">", "")
                .replace("{", "(")
                .replace("}", ")")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim();
    }
}
