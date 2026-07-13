package com.example.mydnd.diagnostics;

import com.example.mydnd.director.DirectorAction;
import com.example.mydnd.director.DirectorActionParser;
import com.example.mydnd.director.DirectorActionType;
import com.example.mydnd.director.DirectorMode;
import com.example.mydnd.llm.GenerationProfile;
import com.example.mydnd.llm.LlmCallback;
import com.example.mydnd.llm.LlmModelManager;
import com.example.mydnd.llm.ModelRole;
import com.example.mydnd.prompt.PromptBuilder;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Runs the fixed {@link BehaviorTestDataset} suite against the Director without touching Room.
 * Holds no reference to any Activity/View - progress and completion are reported through
 * {@link Listener} so the caller decides how to reflect them in the UI.
 */
public final class BehaviorTestRunner {

    private static final String TAG_BEHAVIOR_TEST = "MyDND_BEHAVIOR_TEST";

    public interface Listener {
        void onSuiteStarted(int totalCases);

        void onCaseStarted(int index, int totalCases);

        void onSuiteFinished(boolean success, long totalMs, Throwable error);
    }

    private final LlmModelManager modelManager;
    private final PromptBuilder promptBuilder;
    private final Function<String, String> promptPreparer;
    private final String masterModelFile;
    private final String directorCheckPendingMarker;
    private final Listener listener;

    public BehaviorTestRunner(
            LlmModelManager modelManager,
            PromptBuilder promptBuilder,
            Function<String, String> promptPreparer,
            String masterModelFile,
            String directorCheckPendingMarker,
            Listener listener
    ) {
        this.modelManager = modelManager;
        this.promptBuilder = promptBuilder;
        this.promptPreparer = promptPreparer;
        this.masterModelFile = masterModelFile;
        this.directorCheckPendingMarker = directorCheckPendingMarker;
        this.listener = listener;
    }

    public void run() {
        List<BehaviorTestCase> cases = BehaviorTestDataset.create();
        if (cases.isEmpty()) {
            return;
        }

        long suiteStartedAt = System.currentTimeMillis();

        Log.i(
                TAG_BEHAVIOR_TEST,
                "SUITE START | cases=" + cases.size()
                        + " | model=" + masterModelFile
                        + " | database=UNTOUCHED"
        );

        listener.onSuiteStarted(cases.size());

        runCase(cases, 0, suiteStartedAt);
    }

    private void runCase(
            List<BehaviorTestCase> cases,
            int index,
            long suiteStartedAt
    ) {
        if (cases == null || index >= cases.size()) {
            finishSuite(
                    true,
                    suiteStartedAt,
                    null
            );
            return;
        }

        final BehaviorTestCase testCase = cases.get(index);

        final String rawPrompt = testCase.getMode() == DirectorMode.CHECK_RESULT
                ? promptBuilder.buildDiceContinuationDirectorPrompt(
                        testCase.getMemoryContext(),
                        testCase.getState(),
                        BehaviorTestDataset.campaignState()
                )
                : promptBuilder.buildDirectorToolAwarePrompt(
                        testCase.getPlayerAction(),
                        testCase.getMemoryContext(),
                        testCase.getState(),
                        BehaviorTestDataset.campaignState()
                );

        final String preparedPrompt = promptPreparer.apply(rawPrompt);
        final List<String> actions = Collections.synchronizedList(new ArrayList<>());
        final Set<String> seenFingerprints = Collections.synchronizedSet(new HashSet<>());
        final int[] toolNumber = {0};
        final long caseStartedAt = System.currentTimeMillis();

        listener.onCaseStarted(index, cases.size());

        Log.i(
                TAG_BEHAVIOR_TEST,
                "CASE START | index=" + (index + 1)
                        + " | id=" + testCase.getId()
                        + " | mode=" + testCase.getMode()
                        + " | promptChars=" + preparedPrompt.length()
        );
        Log.i(
                TAG_BEHAVIOR_TEST,
                "INPUT | id=" + testCase.getId()
                        + " | text=" + compactLog(testCase.getPlayerAction())
        );
        Log.i(
                TAG_BEHAVIOR_TEST,
                "EXPECT | id=" + testCase.getId()
                        + " | " + testCase.expectedSummary()
        );

        modelManager.generateDirectorAware(
                ModelRole.MASTER,
                preparedPrompt,
                testCase.getMode().name(),
                GenerationProfile.fast(),
                false,
                "",
                rawToolCall -> {
                    toolNumber[0]++;

                    try {
                        DirectorAction action = new DirectorActionParser().parse(rawToolCall);
                        String toolCode = action.getType().getToolCode();
                        actions.add(toolCode);

                        String fingerprint = toolCode
                                + "\u001F" + action.getName()
                                + "\u001F" + action.getValue()
                                + "\u001F" + action.getDetails();

                        boolean duplicate = !seenFingerprints.add(fingerprint);

                        Log.i(
                                TAG_BEHAVIOR_TEST,
                                "TOOL | id=" + testCase.getId()
                                        + " | #=" + toolNumber[0]
                                        + " | type=" + toolCode
                                        + " | name=" + compactLog(action.getName())
                                        + " | value=" + compactLog(action.getValue())
                                        + " | details=" + compactLog(action.getDetails())
                                        + (duplicate ? " | duplicate=YES" : "")
                        );

                        if (duplicate) {
                            return buildToolResponse(
                                    "REJECTED",
                                    "DUPLICATE_ACTION_IN_TEST",
                                    "",
                                    false,
                                    false
                            );
                        }

                        if (action.getType() == DirectorActionType.NO_CHANGE) {
                            return buildToolResponse(
                                    "NO_CHANGE",
                                    "NO_CHANGE",
                                    "",
                                    false,
                                    false
                            );
                        }

                        if (action.getType() == DirectorActionType.CHECK_REQUEST) {
                            return buildToolResponse(
                                    "APPLIED",
                                    "CHECK_REQUESTED",
                                    action.getName() + " DC " + action.getValue(),
                                    true,
                                    false
                            );
                        }

                        return buildToolResponse(
                                "APPLIED",
                                "TEST_APPLIED",
                                toolCode + " " + action.getName() + " " + action.getValue(),
                                false,
                                testCase.getMode() == DirectorMode.CHECK_RESULT
                        );

                    } catch (Throwable throwable) {
                        Log.e(
                                TAG_BEHAVIOR_TEST,
                                "TOOL PARSE ERROR | id=" + testCase.getId(),
                                throwable
                        );

                        return buildToolResponse(
                                "REJECTED",
                                "TEST_PARSE_ERROR",
                                "",
                                false,
                                false
                        );
                    }
                },
                new LlmCallback() {
                    @Override
                    public void onToken(String token) {
                        // Behaviour test stays out of the visible chat.
                    }

                    @Override
                    public void onComplete(String fullText) {
                        long totalMs = System.currentTimeMillis() - caseStartedAt;
                        boolean passed = casePassed(testCase, actions);

                        Log.i(
                                TAG_BEHAVIOR_TEST,
                                "CASE END | index=" + (index + 1)
                                        + " | id=" + testCase.getId()
                                        + " | pass=" + (passed ? "YES" : "NO")
                                        + " | totalMs=" + totalMs
                                        + " | actions=" + actions
                        );

                        String narrative = fullText == null
                                ? ""
                                : fullText;

                        if (directorCheckPendingMarker.equals(narrative.trim())) {
                            narrative = "[PAUSED_FOR_CHECK]";
                        }

                        Log.d(
                                TAG_BEHAVIOR_TEST,
                                "NARRATIVE | id=" + testCase.getId()
                                        + " | text=" + compactLog(narrative)
                        );

                        runCase(
                                cases,
                                index + 1,
                                suiteStartedAt
                        );
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        Log.e(
                                TAG_BEHAVIOR_TEST,
                                "CASE ERROR | index=" + (index + 1)
                                        + " | id=" + testCase.getId(),
                                throwable
                        );

                        finishSuite(
                                false,
                                suiteStartedAt,
                                throwable
                        );
                    }
                }
        );
    }

    private boolean casePassed(
            BehaviorTestCase testCase,
            List<String> actions
    ) {
        List<String> safeActions = actions == null
                ? Collections.emptyList()
                : new ArrayList<>(actions);

        if (testCase.isOnlyDoneExpected()) {
            return safeActions.size() == 1
                    && "DONE".equals(safeActions.get(0));
        }

        if (!safeActions.containsAll(testCase.getRequiredAll())) {
            return false;
        }

        if (!testCase.getRequiredAny().isEmpty()) {
            boolean anyFound = false;
            for (String expected : testCase.getRequiredAny()) {
                if (safeActions.contains(expected)) {
                    anyFound = true;
                    break;
                }
            }
            if (!anyFound) {
                return false;
            }
        }

        for (String forbidden : testCase.getForbiddenAny()) {
            if (safeActions.contains(forbidden)) {
                return false;
            }
        }

        return true;
    }

    private String buildToolResponse(
            String status,
            String code,
            String stateAfter,
            boolean checkRequested,
            boolean forceDoneNext
    ) {
        StringBuilder response = new StringBuilder();
        response.append("<|tool_response>response:director_action{")
                .append("status:<|\"|>").append(safeToolValue(status)).append("<|\"|>,")
                .append("code:<|\"|>").append(safeToolValue(code)).append("<|\"|>,")
                .append("state_after:<|\"|>").append(safeToolValue(stateAfter)).append("<|\"|>");

        if ("APPLIED".equals(status) && !checkRequested) {
            response.append(forceDoneNext
                    ? ",next:<|\"|>DONE_ONLY<|\"|>"
                    : ",next:<|\"|>DIRECT_OR_DONE<|\"|>");
        } else if ("REJECTED".equals(status)) {
            response.append(",next:<|\"|>DIRECT_FIX_OR_DONE<|\"|>");
        }

        response.append("}<tool_response|>");
        return response.toString();
    }

    private String safeToolValue(String value) {
        if (value == null) {
            return "";
        }

        String safe = value
                .replace("<", "")
                .replace(">", "")
                .replace("{", "(")
                .replace("}", ")")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim();

        return safe.length() <= 160
                ? safe
                : safe.substring(0, 160).trim();
    }

    private String compactLog(String value) {
        if (value == null) {
            return "";
        }

        String compact = value
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();

        return compact.length() <= 260
                ? compact
                : compact.substring(0, 260) + "...";
    }

    private void finishSuite(
            boolean success,
            long suiteStartedAt,
            Throwable error
    ) {
        long totalMs = System.currentTimeMillis() - suiteStartedAt;

        Log.i(
                TAG_BEHAVIOR_TEST,
                "SUITE END | success=" + success
                        + " | totalMs=" + totalMs
                        + (error == null ? "" : " | error=" + error.getClass().getSimpleName())
        );

        listener.onSuiteFinished(success, totalMs, error);
    }
}
