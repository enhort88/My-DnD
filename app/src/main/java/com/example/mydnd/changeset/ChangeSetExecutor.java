package com.example.mydnd.changeset;

import com.example.mydnd.director.DirectorAction;
import com.example.mydnd.director.DirectorActionType;
import com.example.mydnd.director.DirectorExecutor;
import com.example.mydnd.director.DirectorResult;
import com.example.mydnd.director.DirectorStatus;
import com.example.mydnd.director.DirectorStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Applies one extracted ChangeSet exactly once. No retries and no second LLM pass.
 * Existing aliases are resolved against the exact Room snapshot used in the prompt.
 */
public final class ChangeSetExecutor {

    private final ChangeSetParser parser = new ChangeSetParser();
    private final DirectorExecutor directorExecutor;

    public ChangeSetExecutor(DirectorStore store) {
        directorExecutor = new DirectorExecutor(store);
    }

    public ChangeSetExecutionReport execute(
            long campaignId,
            ChangeSetPromptState state,
            String playerAction,
            String narrative,
            String rawChangeSet
    ) {
        final List<ChangeSetOperation> operations;
        try {
            operations = parser.parse(rawChangeSet);
        } catch (RuntimeException error) {
            return ChangeSetExecutionReport.parseFailed(
                    error.getMessage() == null ? "CHANGE_SET_PARSE_ERROR" : error.getMessage()
            );
        }

        ChangeSetPromptState safeState = state == null
                ? ChangeSetPromptState.empty()
                : state;
        String evidence = details(narrative);
        String actionSource = clean(playerAction);
        String fullSource = actionSource + "\n" + clean(narrative);

        List<DirectorResult> results = new ArrayList<>();
        Set<String> fingerprints = new HashSet<>();

        for (ChangeSetOperation operation : operations) {
            DirectorAction action = toDirectorAction(safeState, operation, evidence);
            if (action == null) {
                continue;
            }

            String fingerprint = fingerprint(action);
            if (!fingerprints.add(fingerprint)) {
                continue;
            }

            if (!referenceIsGrounded(safeState, operation, actionSource, fullSource)) {
                results.add(directorExecutor.reject(
                        campaignId,
                        action,
                        "CHANGE_REF_NOT_GROUNDED",
                        ""
                ));
                continue;
            }

            if (requiresNamedEvidence(action.getType())
                    && !containsIgnoreCase(fullSource, action.getName())) {
                results.add(directorExecutor.reject(
                        campaignId,
                        action,
                        "CHANGE_NEW_ENTITY_NOT_GROUNDED",
                        ""
                ));
                continue;
            }

            if (action.getType() == DirectorActionType.HEALTH_CHANGE) {
                int delta = parseInt(action.getValue(), 0);
                if (delta == 0 || Math.abs(delta) > 20) {
                    results.add(directorExecutor.reject(
                            campaignId,
                            action,
                            "CHANGESET_HP_DELTA_OUT_OF_RANGE",
                            ""
                    ));
                    continue;
                }
            }

            results.add(directorExecutor.execute(campaignId, action));
        }

        return ChangeSetExecutionReport.completed(results);
    }

    private DirectorAction toDirectorAction(
            ChangeSetPromptState state,
            ChangeSetOperation operation,
            String details
    ) {
        DirectorActionType type = operation.getType();
        String target = operation.getTarget();
        String value = operation.getValue();

        switch (type) {
            case INVENTORY_ADD:
                if (state.isActorName(target)) {
                    return null;
                }
                return action(type, target, "", details);

            case INVENTORY_REMOVE:
                return existing(state, type, target, 'I', "", details);

            case HEALTH_CHANGE:
                if ("PLAYER".equalsIgnoreCase(target)) {
                    return action(type, "PLAYER", value, details);
                }
                return existing(state, type, target, 'N', value, details);

            case MONEY_CHANGE:
                return action(type, "PLAYER", value, details);

            case NPC_UPSERT:
                if (state.isActorName(target)) {
                    return null;
                }
                return action(type, target, "", details);

            case NPC_MEMORY:
            case NPC_STATUS:
                return existing(state, type, target, 'N', value, details);

            case WORLD_EVENT_ADD:
                return action(type, target, value, details);

            case WORLD_EVENT_UPDATE:
                return existing(state, type, target, 'W', value, details);

            case WORLD_EVENT_RESOLVE:
                return existing(state, type, target, 'W', "", details);

            case QUEST_START:
                return action(type, target, "", details);

            case QUEST_UPDATE:
            case QUEST_COMPLETE:
            case QUEST_FAIL:
                return existing(state, type, target, 'Q', "", details);

            case ABILITY_ADD:
                return action(type, target, value, details);

            case ABILITY_UPDATE:
                return existing(state, type, target, 'A', value, details);

            case ABILITY_REMOVE:
                return existing(state, type, target, 'A', "", details);

            case EFFECT_ADD:
                if (state.isActorName(target)) {
                    return null;
                }
                return action(type, target, "", details);

            case EFFECT_REMOVE:
                return existing(state, type, target, 'E', "", details);

            case LOCATION_SET:
                return action(type, target, "", details);

            default:
                return null;
        }
    }

    private DirectorAction existing(
            ChangeSetPromptState state,
            DirectorActionType type,
            String alias,
            char prefix,
            String value,
            String details
    ) {
        ChangeSetPromptState.Ref ref = state.findRef(alias, prefix);
        if (ref == null) {
            return null;
        }
        return action(type, ref.getName(), value, details);
    }

    private DirectorAction action(
            DirectorActionType type,
            String name,
            String value,
            String details
    ) {
        return new DirectorAction(type, name, value, details);
    }

    private boolean requiresNamedEvidence(DirectorActionType type) {
        switch (type) {
            case INVENTORY_ADD:
            case NPC_UPSERT:
            case WORLD_EVENT_ADD:
            case QUEST_START:
            case ABILITY_ADD:
            case EFFECT_ADD:
            case LOCATION_SET:
                return true;
            default:
                return false;
        }
    }


    private boolean referenceIsGrounded(
            ChangeSetPromptState state,
            ChangeSetOperation operation,
            String actionSource,
            String fullSource
    ) {
        String alias = operation.getTarget();
        ChangeSetPromptState.Ref ref = state.findRef(alias);
        if (ref == null) {
            return true;
        }

        String name = clean(ref.getName());
        if (name.isEmpty()) {
            return false;
        }

        if (operation.getType() == DirectorActionType.NPC_MEMORY
                || operation.getType() == DirectorActionType.NPC_STATUS) {
            return containsIgnoreCase(actionSource, name);
        }

        return containsIgnoreCase(fullSource, name);
    }

    private boolean containsIgnoreCase(String text, String value) {
        return clean(text).toLowerCase(Locale.ROOT)
                .contains(clean(value).toLowerCase(Locale.ROOT));
    }

    private String details(String narrative) {
        String clean = clean(narrative);
        if (clean.length() <= 220) {
            return clean;
        }
        return clean.substring(0, 220).trim();
    }

    private String fingerprint(DirectorAction action) {
        return action.getType().getToolCode()
                + '\u001F' + clean(action.getName()).toLowerCase(Locale.ROOT)
                + '\u001F' + clean(action.getValue()).toLowerCase(Locale.ROOT);
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(clean(value));
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }
}
