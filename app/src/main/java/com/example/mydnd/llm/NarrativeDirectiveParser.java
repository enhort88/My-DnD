package com.example.mydnd.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class NarrativeDirectiveParser {

    public ParseResult parse(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return new ParseResult(
                    "",
                    Collections.emptyList(),
                    Collections.emptyList()
            );
        }

        String[] lines = rawText.split("\r?\n");
        StringBuilder narrative = new StringBuilder();
        List<Directive> directives = new ArrayList<>();
        List<String> rejectedDirectiveLines = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            Directive directive = parseDirective(trimmed);

            if (directive != null) {
                directives.add(directive);
                continue;
            }

            if (looksLikeDirective(trimmed)) {
                rejectedDirectiveLines.add(trimmed);
            }

            if (narrative.length() > 0) {
                narrative.append('\n');
            }

            narrative.append(line);
        }

        return new ParseResult(
                narrative.toString().trim(),
                directives,
                rejectedDirectiveLines
        );
    }

    private Directive parseDirective(String line) {
        if (!line.startsWith("[")) {
            return null;
        }

        int closingBracket = line.lastIndexOf(']');

        if (closingBracket <= 0) {
            return null;
        }

        String trailing = line
                .substring(closingBracket + 1)
                .trim();

        if (!isAllowedTrailingPunctuation(trailing)) {
            return null;
        }

        String body = line
                .substring(1, closingBracket)
                .trim();

        if (body.startsWith("NPC_MEMORY|")) {
            String[] parts = body.split("\\|", 4);

            if (parts.length != 4) {
                return null;
            }

            String tone = parts[2].trim().toUpperCase(Locale.ROOT);

            if (!"GOOD".equals(tone)
                    && !"BAD".equals(tone)
                    && !"NEUTRAL".equals(tone)) {
                return null;
            }

            return Directive.npcMemory(
                    parts[1].trim(),
                    tone,
                    parts[3].trim()
            );
        }

        if (body.startsWith("CHECK|")) {
            String[] parts = body.split("\\|", 4);

            if (parts.length != 4) {
                return null;
            }

            int difficulty;

            try {
                difficulty = Integer.parseInt(parts[2].trim());
            } catch (NumberFormatException ignored) {
                return null;
            }

            return Directive.diceCheck(
                    parts[1].trim(),
                    difficulty,
                    parts[3].trim()
            );
        }

        return null;
    }

    private boolean looksLikeDirective(String line) {
        return line.startsWith("[NPC_MEMORY|")
                || line.startsWith("[CHECK|");
    }

    private boolean isAllowedTrailingPunctuation(String trailing) {
        if (trailing.isEmpty()) {
            return true;
        }

        String allowed = ".,;:!?…»”\"'";

        for (int i = 0; i < trailing.length(); i++) {
            if (allowed.indexOf(trailing.charAt(i)) < 0) {
                return false;
            }
        }

        return true;
    }

    public static class ParseResult {
        private final String narrativeText;
        private final List<Directive> directives;
        private final List<String> rejectedDirectiveLines;

        private ParseResult(
                String narrativeText,
                List<Directive> directives,
                List<String> rejectedDirectiveLines
        ) {
            this.narrativeText = narrativeText;
            this.directives = directives;
            this.rejectedDirectiveLines = rejectedDirectiveLines;
        }

        public String getNarrativeText() {
            return narrativeText;
        }

        public List<Directive> getDirectives() {
            return directives;
        }

        public List<String> getRejectedDirectiveLines() {
            return rejectedDirectiveLines;
        }
    }

    public static class Directive {
        public static final String NPC_MEMORY = "NPC_MEMORY";
        public static final String DICE_CHECK = "DICE_CHECK";

        private final String type;
        private final String subject;
        private final String tone;
        private final String text;
        private final int difficulty;

        private Directive(
                String type,
                String subject,
                String tone,
                String text,
                int difficulty
        ) {
            this.type = type;
            this.subject = subject;
            this.tone = tone;
            this.text = text;
            this.difficulty = difficulty;
        }

        public static Directive npcMemory(
                String npcName,
                String tone,
                String fact
        ) {
            return new Directive(
                    NPC_MEMORY,
                    npcName,
                    tone,
                    fact,
                    0
            );
        }

        public static Directive diceCheck(
                String attribute,
                int difficulty,
                String reason
        ) {
            return new Directive(
                    DICE_CHECK,
                    attribute,
                    "",
                    reason,
                    Math.max(1, Math.min(30, difficulty))
            );
        }

        public String getType() {
            return type;
        }

        public String getSubject() {
            return subject;
        }

        public String getTone() {
            return tone;
        }

        public String getText() {
            return text;
        }

        public int getDifficulty() {
            return difficulty;
        }
    }
}
