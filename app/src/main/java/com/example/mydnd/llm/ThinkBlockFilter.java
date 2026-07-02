package com.example.mydnd.llm;

public class ThinkBlockFilter {

    public String removeThinkBlocks(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String result = text;

        result = result.replaceAll("(?s)<think>.*?</think>", "");
        result = result.replace("<think>", "");
        result = result.replace("</think>", "");

        return result.trim();
    }
}