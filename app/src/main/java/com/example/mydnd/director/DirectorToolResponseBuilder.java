package com.example.mydnd.director;

/** Builds the short same-context tool response consumed by Gemma 4. */
public final class DirectorToolResponseBuilder {

    public String build(DirectorResult result) {
        StringBuilder response = new StringBuilder();
        response.append("<|tool_response>response:director_action{")
                .append("status:<|\"|>").append(safe(result.getStatus().name())).append("<|\"|>,")
                .append("code:<|\"|>").append(safe(result.getCode())).append("<|\"|>,")
                .append("state_after:<|\"|>").append(safeState(result.getStateAfter())).append("<|\"|>");

        if (result.getStatus() == DirectorStatus.APPLIED) {
            response.append(",next:<|\"|>DIRECT_OR_DONE<|\"|>");
        } else if (result.getStatus() == DirectorStatus.REJECTED) {
            response.append(",next:<|\"|>DIRECT_FIX_OR_DONE<|\"|>");
        }

        response.append("}<tool_response|>");
        return response.toString();
    }

    private String safeState(String value) {
        String safe = safe(value);
        final int maxChars = 160;
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
