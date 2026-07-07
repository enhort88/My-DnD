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
 * Java does not modify the inventory here.
 * The final tool call is still decided by the existing LLM flow.
 * This resolver only reduces ambiguity for the small model.
 */
public final class InventoryActionHintResolver {

    private static final Pattern STARRED_ITEM_PATTERN =
            Pattern.compile("\\*([^*\\r\\n]{1,80})\\*");

    private static final Pattern ADD_VERB_PATTERN =
            Pattern.compile(
                    "\\b("
                            + "take|takes|took|taking"
                            + "|grab|grabs|grabbed|grabbing"
                            + "|pick\\s+up|picks\\s+up|picked\\s+up|picking\\s+up"
                            + "|collect|collects|collected|collecting"
                            + ")\\b",
                    Pattern.CASE_INSENSITIVE
            );

    private static final Pattern REMOVE_VERB_PATTERN =
            Pattern.compile(
                    "\\b("
                            + "drop|drops|dropped|dropping"
                            + "|discard|discards|discarded|discarding"
                            + "|throw\\s+away|throws\\s+away|threw\\s+away|throwing\\s+away"
                            + "|give|gives|gave|giving"
                            + "|leave|leaves|left|leaving"
                            + ")\\b",
                    Pattern.CASE_INSENSITIVE
            );

    private static final Pattern NEGATED_ACTION_PATTERN =
            Pattern.compile(
                    "\\b("
                            + "do\\s+not|don't"
                            + "|does\\s+not|doesn't"
                            + "|did\\s+not|didn't"
                            + "|will\\s+not|won't"
                            + "|not"
                            + ")\\s+("
                            + "take|grab|pick\\s+up|collect"
                            + "|drop|discard|throw\\s+away|give|leave"
                            + ")\\b",
                    Pattern.CASE_INSENSITIVE
            );

    private static final Pattern INVENTORY_PLACEMENT_PATTERN =
            Pattern.compile(
                    "\\b("
                            + "put|puts|placed?|placing"
                            + ")\\b.*\\b(in|into)\\s+"
                            + "(?:(?:my|the)\\s+)?"
                            + "(pocket|inventory|backpack|bag)\\b",
                    Pattern.CASE_INSENSITIVE
            );

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

        String normalizedText = normalize(safeText);

        if (NEGATED_ACTION_PATTERN.matcher(normalizedText).find()) {
            return Result.none("NEGATED_ACTION");
        }

        boolean explicitAdd =
                ADD_VERB_PATTERN.matcher(normalizedText).find()
                        || containsInventoryPlacement(normalizedText);

        boolean explicitRemove =
                REMOVE_VERB_PATTERN.matcher(normalizedText).find();

        // "I leave it with me" does not mean losing the item.
        if (explicitRemove
                && normalizedText.contains("leave")
                && (normalizedText.contains("with me")
                || normalizedText.contains("for myself"))) {
            explicitRemove = false;
        }

        if (explicitAdd && explicitRemove) {
            return Result.none("CONFLICTING_ACTIONS");
        }

        if (!explicitAdd && !explicitRemove) {
            return Result.none("NO_EXPLICIT_ACTION");
        }

        String matchingInventoryItem = findMatchingInventoryItem(
                safeInventory,
                itemName
        );

        if (explicitAdd) {
            if (matchingInventoryItem != null) {
                return Result.none("ITEM_ALREADY_PRESENT");
            }

            return Result.explicitAdd(itemName);
        }

        if (matchingInventoryItem == null) {
            return Result.none("ITEM_NOT_PRESENT_FOR_REMOVE");
        }

        return Result.explicitRemove(matchingInventoryItem);
    }

    private boolean containsInventoryPlacement(String normalizedText) {
        return INVENTORY_PLACEMENT_PATTERN
                .matcher(normalizedText)
                .find();
    }

    private String findMatchingInventoryItem(
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
                return null;
            }

            found = inventoryItem;
        }

        return found;
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