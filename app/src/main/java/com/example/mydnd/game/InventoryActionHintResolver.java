package com.example.mydnd.game;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gives the LLM a strong but safe hint for an obvious action
 * involving exactly one item explicitly marked by the player as *item*.
 *
 * Java does not modify the inventory here and does not try to parse verbs.
 * Verb/intent understanding is the LLM's job (natural language is
 * multilingual); Java only supplies the one fact it alone owns: whether the
 * starred item is currently present in the canonical Room inventory.
 *
 * A single starred item that the player does not yet own can only become an
 * ADD; a single starred item already in the inventory can only become a
 * REMOVE (or NO_CHANGE, which the model is free to choose instead). This
 * keeps the hint language-agnostic: it works the same for "Я беру *stone*",
 * "I take *stone*" or any other language, because it never inspects the verb.
 */
public final class InventoryActionHintResolver {

    private static final Pattern STARRED_ITEM_PATTERN =
            Pattern.compile("\\*([^*\\r\\n]{1,80})\\*");

    public Result resolve(
            String playerText,
            List<String> inventoryBefore
    ) {
        String safeText = playerText == null
                ? ""
                : playerText.trim();

        List<String> safeInventory = inventoryBefore == null
                ? Collections.emptyList()
                : inventoryBefore;

        Matcher matcher = STARRED_ITEM_PATTERN.matcher(safeText);

        if (!matcher.find()) {
            return Result.none("NO_STARRED_ITEM");
        }

        String itemName = matcher.group(1).trim();

        if (itemName.isEmpty()) {
            return Result.none("EMPTY_STARRED_ITEM");
        }

        if (matcher.find()) {
            return Result.none("MULTIPLE_STARRED_ITEMS");
        }

        MatchOutcome outcome = findMatchingInventoryItem(
                safeInventory,
                itemName
        );

        if (outcome.ambiguous) {
            return Result.none("AMBIGUOUS_INVENTORY_MATCH");
        }

        if (outcome.match == null) {
            return Result.explicitAdd(itemName);
        }

        return Result.explicitRemove(outcome.match);
    }

    private MatchOutcome findMatchingInventoryItem(
            List<String> inventory,
            String itemName
    ) {
        String expected = normalize(itemName);
        String found = null;

        for (String inventoryItem : inventory) {
            String candidate = normalize(inventoryItem);

            if (!expected.equals(candidate)) {
                continue;
            }

            if (found != null) {
                return MatchOutcome.ambiguous();
            }

            found = inventoryItem;
        }

        return MatchOutcome.of(found);
    }

    private static final class MatchOutcome {

        private final String match;
        private final boolean ambiguous;

        private MatchOutcome(String match, boolean ambiguous) {
            this.match = match;
            this.ambiguous = ambiguous;
        }

        static MatchOutcome of(String match) {
            return new MatchOutcome(match, false);
        }

        static MatchOutcome ambiguous() {
            return new MatchOutcome(null, true);
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }

        return value
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('’', '\'')
                .replaceAll("\\s+", " ");
    }

    public static final class Result {

        private final String promptValue;
        private final String reason;

        private Result(
                String promptValue,
                String reason
        ) {
            this.promptValue = promptValue;
            this.reason = reason;
        }

        public static Result none(String reason) {
            return new Result(
                    "NONE",
                    reason
            );
        }

        public static Result explicitAdd(String itemName) {
            return new Result(
                    "EXPLICIT_ADD: *" + itemName + "*",
                    "EXPLICIT_ADD"
            );
        }

        public static Result explicitRemove(String itemName) {
            return new Result(
                    "EXPLICIT_REMOVE: *" + itemName + "*",
                    "EXPLICIT_REMOVE"
            );
        }

        public String getPromptValue() {
            return promptValue;
        }

        public String getReason() {
            return reason;
        }
    }
}
