package com.example.mydnd.director;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final Set<String> appliedContentFingerprints = new HashSet<>();
    private final List<String> confirmedChanges = new ArrayList<>();

    private DirectorMode mode = DirectorMode.PLAYER_ACTION;
    private TurnPolicy turnPolicy = TurnPolicy.none();
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
        startTurn(mode, "");
    }

    public synchronized void startTurn(
            DirectorMode mode,
            String actionHint
    ) {
        startTurn(
                mode,
                actionHint,
                ""
        );
    }

    public synchronized void startTurn(
            DirectorMode mode,
            String actionHint,
            String playerText
    ) {
        this.mode = mode == null
                ? DirectorMode.PLAYER_ACTION
                : mode;
        this.turnPolicy = TurnPolicy.fromHint(
                this.mode,
                actionHint,
                playerText
        );
        actionCount = 0;
        appliedActionCount = 0;
        appliedActionFingerprints.clear();
        appliedContentFingerprints.clear();
        confirmedChanges.clear();
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

        if (mode == DirectorMode.PLAYER_ACTION
                && action.getType() == DirectorActionType.CHECK_REQUEST
                && actionCount > 1) {
            result = executor.reject(
                    campaignId,
                    action,
                    "CHECK_MUST_BE_FIRST",
                    ""
            );
            dispatch(result);
            return buildResponse(result);
        }

        if (!mode.allows(action.getType())) {
            result = executor.reject(
                    campaignId,
                    action,
                    "ACTION_NOT_ALLOWED_IN_MODE",
                    mode.name()
            );
            dispatch(result);
            return buildResponse(result);
        }

        String policyRejection = turnPolicy.rejectReason(
                action,
                appliedActionCount
        );
        if (!policyRejection.isEmpty()) {
            result = executor.reject(
                    campaignId,
                    action,
                    policyRejection,
                    turnPolicy.describe()
            );
            dispatch(result);
            return buildResponse(result);
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
            return buildResponse(result);
        }

        String fingerprint = fingerprint(action);
        String contentFingerprint = contentFingerprint(action);

        boolean sameTypeDuplicate = action.getType() != DirectorActionType.NO_CHANGE
                && appliedActionFingerprints.contains(fingerprint);

        boolean sameFactDifferentType = action.getType() != DirectorActionType.NO_CHANGE
                && !canonical(action.getName()).isEmpty()
                && !canonical(action.getDetails()).isEmpty()
                && appliedContentFingerprints.contains(contentFingerprint);

        if (sameTypeDuplicate || sameFactDifferentType) {
            result = executor.reject(
                    campaignId,
                    action,
                    sameTypeDuplicate
                            ? "DUPLICATE_ACTION_IN_TURN"
                            : "DUPLICATE_FACT_DIFFERENT_TYPE",
                    ""
            );
        } else {
            result = executor.execute(campaignId, action);

            if (result.getStatus() == DirectorStatus.APPLIED) {
                appliedActionFingerprints.add(fingerprint);
                appliedContentFingerprints.add(contentFingerprint);
                appliedActionCount++;
                confirmedChanges.add(confirmedChange(result));
                turnPolicy.recordApplied(action);
            }
        }

        dispatch(result);
        return buildResponse(result);
    }



    private String buildResponse(DirectorResult result) {
        boolean autoFinishCheckResult = shouldForceDoneNext(result);
        boolean acceptedDone = result != null
                && result.getStatus() == DirectorStatus.NO_CHANGE
                && result.getAction().getType() == DirectorActionType.NO_CHANGE;
        String confirmed = acceptedDone || autoFinishCheckResult
                ? String.join(" ; ", confirmedChanges)
                : "";

        return responseBuilder.build(
                result,
                autoFinishCheckResult,
                confirmed
        );
    }

    private String confirmedChange(DirectorResult result) {
        DirectorAction action = result.getAction();
        StringBuilder summary = new StringBuilder(action.getType().getToolCode());

        if (!action.getName().isEmpty()) {
            summary.append(':').append(action.getName());
        }
        if (!action.getValue().isEmpty()) {
            summary.append(':').append(action.getValue());
        }

        String state = result.getStateAfter() == null ? "" : result.getStateAfter().trim();
        if (state.length() > 60) {
            state = state.substring(0, 59).trim() + "…";
        }
        if (!state.isEmpty()) {
            summary.append("=").append(state);
        }

        return summary.toString();
    }

    private boolean shouldForceDoneNext(DirectorResult result) {
        if (result == null) {
            return false;
        }

        if (mode == DirectorMode.CHECK_RESULT
                && result.getStatus() == DirectorStatus.APPLIED) {
            return true;
        }

        if (result.getStatus() != DirectorStatus.REJECTED) {
            return false;
        }

        String code = result.getCode();
        return "ACTION_HINT_REQUIRES_DONE".equals(code)
                || "ACTION_HINT_TYPE_MISMATCH".equals(code)
                || "ACTION_HINT_TARGET_MISMATCH".equals(code)
                || "ACTION_SEMANTICALLY_INVALID".equals(code)
                || "HP_ALREADY_APPLIED_THIS_TURN".equals(code)
                || "MONEY_ALREADY_APPLIED_THIS_TURN".equals(code)
                || "DUPLICATE_FACT_DIFFERENT_TYPE".equals(code);
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

    /**
     * Unlike {@link #fingerprint}, deliberately omits the action type: catches the
     * same narrated fact re-applied as a different director_action type (e.g. one
     * city observation re-encoded as INV_ADD, then LOCATION, then QUEST_START,
     * then EFFECT_ADD in the same turn).
     */
    private String contentFingerprint(DirectorAction action) {
        return canonical(action.getName())
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
        return buildResponse(result);
    }

    private void dispatch(DirectorResult result) {
        if (listener != null) {
            listener.onDirectorResult(result);
        }
    }

    private static final class TurnPolicy {

        private static final Pattern EXPLICIT_HINT_PATTERN = Pattern.compile(
                "^EXPLICIT_(ADD|REMOVE):\\s*\\*([^*\\r\\n]{1,120})\\*\\s*$",
                Pattern.CASE_INSENSITIVE
        );

        private final DirectorActionType requiredFirstType;
        private final String requiredFirstName;
        private final boolean singleAppliedAction;
        private final String description;

        private boolean healthChangeApplied;
        private boolean moneyChangeApplied;

        private TurnPolicy(
                DirectorActionType requiredFirstType,
                String requiredFirstName,
                boolean singleAppliedAction,
                String description
        ) {
            this.requiredFirstType = requiredFirstType;
            this.requiredFirstName = canonical(requiredFirstName);
            this.singleAppliedAction = singleAppliedAction;
            this.description = description == null ? "" : description;
        }

        static TurnPolicy none() {
            return new TurnPolicy(
                    null,
                    "",
                    false,
                    ""
            );
        }

        /**
         * playerText is intentionally unused for policy decisions: guessing
         * intent from free natural-language text would require a per-language
         * keyword dictionary, which does not scale to a multilingual game.
         * Java only enforces structural, language-agnostic limits here; the
         * LLM (which already owns natural-language understanding) decides
         * intent, and the prompt tells it to call DONE when nothing changes.
         */
        static TurnPolicy fromHint(
                DirectorMode mode,
                String actionHint,
                String playerText
        ) {
            if (mode != DirectorMode.PLAYER_ACTION || actionHint == null) {
                return new TurnPolicy(
                        null,
                        "",
                        false,
                        ""
                );
            }

            Matcher matcher = EXPLICIT_HINT_PATTERN.matcher(actionHint.trim());
            if (!matcher.matches()) {
                return new TurnPolicy(
                        null,
                        "",
                        false,
                        ""
                );
            }

            DirectorActionType type = "ADD".equalsIgnoreCase(matcher.group(1))
                    ? DirectorActionType.INVENTORY_ADD
                    : DirectorActionType.INVENTORY_REMOVE;

            return new TurnPolicy(
                    type,
                    matcher.group(2),
                    true,
                    actionHint.trim()
            );
        }

        void recordApplied(DirectorAction action) {
            if (action == null) {
                return;
            }

            if (action.getType() == DirectorActionType.HEALTH_CHANGE) {
                healthChangeApplied = true;
            } else if (action.getType() == DirectorActionType.MONEY_CHANGE) {
                moneyChangeApplied = true;
            }
        }

        String rejectReason(
                DirectorAction action,
                int appliedActionCount
        ) {
            if (action == null) {
                return "ACTION_NULL";
            }

            if (action.getType() == DirectorActionType.NO_CHANGE) {
                return "";
            }

            if (isAlwaysSuspicious(action)) {
                return "ACTION_SEMANTICALLY_INVALID";
            }

            if (action.getType() == DirectorActionType.HEALTH_CHANGE
                    && healthChangeApplied) {
                return "HP_ALREADY_APPLIED_THIS_TURN";
            }

            if (action.getType() == DirectorActionType.MONEY_CHANGE
                    && moneyChangeApplied) {
                return "MONEY_ALREADY_APPLIED_THIS_TURN";
            }

            if (requiredFirstType == null) {
                return "";
            }

            if (singleAppliedAction && appliedActionCount > 0) {
                return "ACTION_HINT_REQUIRES_DONE";
            }

            if (appliedActionCount == 0
                    && action.getType() != requiredFirstType) {
                return "ACTION_HINT_TYPE_MISMATCH";
            }

            if (appliedActionCount == 0
                    && !requiredFirstName.isEmpty()
                    && !requiredFirstName.equals(canonical(action.getName()))) {
                return "ACTION_HINT_TARGET_MISMATCH";
            }

            return "";
        }

        String describe() {
            return description;
        }

        private static boolean isAlwaysSuspicious(DirectorAction action) {
            DirectorActionType type = action.getType();
            String name = canonical(action.getName());

            if (type == DirectorActionType.QUEST_START
                    && (name.isEmpty()
                    || "none".equals(name)
                    || "quest_none".equals(name)
                    || "no quest".equals(name)
                    || "нет".equals(name)
                    || "n/a".equals(name))) {
                return true;
            }

            if (type == DirectorActionType.EFFECT_ADD
                    && (name.isEmpty()
                    || "player".equals(name)
                    || "hero".equals(name)
                    || "world_events".equals(name)
                    || "state before".equals(name)
                    || "state_before".equals(name)
                    || "персонаж".equals(name)
                    || "герой".equals(name))) {
                return true;
            }

            return false;
        }

        private static String canonical(String value) {
            return value == null
                    ? ""
                    : value.trim()
                    .replaceAll("\\s+", " ")
                    .toLowerCase(Locale.ROOT);
        }
    }
}
