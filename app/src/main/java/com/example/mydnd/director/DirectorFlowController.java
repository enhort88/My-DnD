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

    private static final int MAX_ACTIONS_PER_TURN = 8;

    private final DirectorActionParser parser;
    private final DirectorExecutor executor;
    private final DirectorToolResponseBuilder responseBuilder;
    private final Listener listener;

    private final Set<String> appliedActionFingerprints = new HashSet<>();
    private int actionCount;

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
        actionCount = 0;
        appliedActionFingerprints.clear();
    }

    /** Called by the native callback for one forced director_action. */
    public synchronized String onToolCall(
            long campaignId,
            String rawToolCall
    ) {
        actionCount++;

        if (actionCount > MAX_ACTIONS_PER_TURN) {
            return terminalError("DIRECTOR_ACTION_LIMIT");
        }

        DirectorAction action;
        try {
            action = parser.parse(rawToolCall);
        } catch (RuntimeException error) {
            return terminalError("DIRECTOR_PARSE_ERROR");
        }

        String fingerprint = fingerprint(action);
        DirectorResult result;

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
            }
        }

        dispatch(result);
        return responseBuilder.build(result);
    }


    public synchronized int getActionCount() {
        return actionCount;
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
                ""
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
