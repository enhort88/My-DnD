package com.example.mydnd.changeset;

import com.example.mydnd.director.DirectorActionType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Parses one compact change_set tool call. */
public final class ChangeSetParser {

    public static final int MAX_OPERATIONS = 6;

    public List<ChangeSetOperation> parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        int marker = text.indexOf("change_set{ops:[");
        if (marker < 0) {
            throw new IllegalArgumentException("CHANGE_SET_MARKER_MISSING");
        }

        int start = marker + "change_set{ops:[".length();
        int end = findClosingList(text, start);
        if (end < 0) {
            throw new IllegalArgumentException("CHANGE_SET_LIST_UNCLOSED");
        }

        String body = text.substring(start, end).trim();
        if (body.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> parts = splitTopLevel(body);
        if (parts.size() > MAX_OPERATIONS) {
            throw new IllegalArgumentException("CHANGE_SET_TOO_LARGE");
        }

        List<ChangeSetOperation> result = new ArrayList<>(parts.size());
        for (String part : parts) {
            result.add(parseOperation(part));
        }
        return result;
    }

    private ChangeSetOperation parseOperation(String raw) {
        String text = raw == null ? "" : raw.trim();
        int open = text.indexOf('(');
        int close = text.lastIndexOf(')');
        if (open <= 0 || close <= open || close != text.length() - 1) {
            throw new IllegalArgumentException("CHANGE_SET_OP_INVALID: " + text);
        }

        String code = text.substring(0, open).trim();
        DirectorActionType type = DirectorActionType.fromToolValue(code);
        List<String> args = splitArgs(text.substring(open + 1, close));

        switch (type) {
            case INVENTORY_ADD:
            case INVENTORY_REMOVE:
            case NPC_UPSERT:
            case WORLD_EVENT_RESOLVE:
            case QUEST_START:
            case QUEST_UPDATE:
            case QUEST_COMPLETE:
            case QUEST_FAIL:
            case ABILITY_REMOVE:
            case EFFECT_ADD:
            case EFFECT_REMOVE:
            case LOCATION_SET:
                requireCount(type, args, 1);
                return new ChangeSetOperation(type, unquote(args.get(0)), "");

            case HEALTH_CHANGE:
            case MONEY_CHANGE:
            case NPC_MEMORY:
            case NPC_STATUS:
            case WORLD_EVENT_ADD:
            case WORLD_EVENT_UPDATE:
            case ABILITY_ADD:
            case ABILITY_UPDATE:
                requireCount(type, args, 2);
                return new ChangeSetOperation(
                        type,
                        unquote(args.get(0)),
                        unquote(args.get(1))
                );

            default:
                throw new IllegalArgumentException("CHANGE_SET_TYPE_NOT_ALLOWED: " + code);
        }
    }

    private void requireCount(DirectorActionType type, List<String> args, int count) {
        if (args.size() != count) {
            throw new IllegalArgumentException(type.getToolCode() + "_ARG_COUNT");
        }
    }

    private int findClosingList(String text, int start) {
        boolean inQuote = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
                inQuote = !inQuote;
            } else if (ch == ']' && !inQuote) {
                return i;
            }
        }
        return -1;
    }

    private List<String> splitTopLevel(String body) {
        List<String> result = new ArrayList<>();
        boolean inQuote = false;
        int depth = 0;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '"' && (i == 0 || body.charAt(i - 1) != '\\')) {
                inQuote = !inQuote;
            } else if (!inQuote) {
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    depth--;
                } else if (ch == ',' && depth == 0) {
                    result.add(body.substring(start, i).trim());
                    start = i + 1;
                }
            }
        }
        result.add(body.substring(start).trim());
        return result;
    }

    private List<String> splitArgs(String body) {
        if (body.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        boolean inQuote = false;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '"' && (i == 0 || body.charAt(i - 1) != '\\')) {
                inQuote = !inQuote;
            } else if (ch == ',' && !inQuote) {
                result.add(body.substring(start, i).trim());
                start = i + 1;
            }
        }
        result.add(body.substring(start).trim());
        return result;
    }

    private String unquote(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() >= 2 && clean.charAt(0) == '"' && clean.charAt(clean.length() - 1) == '"') {
            clean = clean.substring(1, clean.length() - 1);
        }
        return clean
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .trim();
    }
}
