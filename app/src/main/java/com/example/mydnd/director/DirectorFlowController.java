package com.example.mydnd.director;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Keeps Director orchestration out of MainActivity.
 * Native owns the repeated forced-action loop; this class owns parse/validate/Room/response.
 */
public final class DirectorFlowController {

    public interface Listener {
        void onDirectorResult(DirectorResult result);
    }

    private final DirectorActionParser parser;
    private final DirectorExecutor executor;
    private final DirectorToolResponseBuilder responseBuilder;
    private final Listener listener;

    private final Set<String> appliedActionFingerprints = new HashSet<>();

    private DirectorMode mode = DirectorMode.PLAYER_ACTION;
    private int actionCount;
    private int appliedActionCount;

    public DirectorFlowController(
            DirectorStore store,
            Listener listener
    ) {
        this.parser = new DirectorActionParser();
        this.executor = new DirectorExecutor(store);
        this.responseBuilder = new DirectorToolResponseBuilder();
        this.listener = listener;
    }

    public synchronized void startTurn() {
        startTurn(DirectorMode.PLAYER_ACTION);
    }

    public synchronized void startTurn(DirectorMode mode) {
        this.mode = mode == null
                ? DirectorMode.PLAYER_ACTION
                : mode;
        actionCount = 0;
        appliedActionCount = 0;
        appliedActionFingerprints.clear();
    }

    /** Called by the native callback for one forced director_action. */
    public synchronized String onToolCall(
            long campaignId,
            String rawToolCall
    ) {
        actionCount++;

        if (actionCount > mode.getMaxAttempts()) {
            return terminalError("DIRECTOR_ACTION_LIMIT");
        }

        DirectorAction action;
        try {
            action = parser.parse(rawToolCall);
        } catch (RuntimeException error) {
            return terminalError("DIRECTOR_PARSE_ERROR");
        }

        DirectorResult result;

        if (!mode.allows(action.getType())) {
            result = executor.reject(
                    campaignId,
                    action,
                    "ACTION_NOT_ALLOWED_IN_MODE",
                    mode.name()
            );
            dispatch(result);
            return responseBuilder.build(result);
        }

        if (action.getType() != DirectorActionType.NO_CHANGE
                && appliedActionCount >= mode.getMaxAppliedActions()) {
            result = executor.reject(
                    campaignId,
                    action,
                    "DIRECTOR_APPLIED_LIMIT",
                    mode.name()
            );
            dispatch(result);
            return responseBuilder.build(result);
        }

        String fingerprint = fingerprint(action);

        if (action.getType() != DirectorActionType.NO_CHANGE
                && appliedActionFingerprints.contains(fingerprint)) {
            result = executor.reject(
                    campaignId,
                    action,
                    "DUPLICATE_ACTION_IN_TURN",
                    ""
            );
        } else {
            result = executor.execute(campaignId, action);

            if (result.getStatus() == DirectorStatus.APPLIED) {
                appliedActionFingerprints.add(fingerprint);
                appliedActionCount++;
            }
        }

        dispatch(result);
        return responseBuilder.build(result);
    }

    public synchronized DirectorMode getMode() {
        return mode;
    }

    public synchronized int getActionCount() {
        return actionCount;
    }

    public synchronized int getAppliedActionCount() {
        return appliedActionCount;
    }

    private String fingerprint(DirectorAction action) {
        return action.getType().getToolCode()
                + '\u001F' + canonical(action.getName())
                + '\u001F' + canonical(action.getValue())
                + '\u001F' + canonical(action.getDetails());
    }

    private String canonical(String value) {
        return value == null
                ? ""
                : value.trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private String terminalError(String code) {
        DirectorAction terminalAction = new DirectorAction(
                DirectorActionType.NO_CHANGE,
                "",
                "",
                ""
        );
        DirectorResult result = DirectorResult.rejected(
                terminalAction,
                code,
                mode.name()
        );
        dispatch(result);
        return responseBuilder.build(result);
    }

    private void dispatch(DirectorResult result) {
        if (listener != null) {
            listener.onDirectorResult(result);
        }
    }
}
