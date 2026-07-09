package com.example.mydnd.diagnostics;

import com.example.mydnd.director.DirectorMode;
import com.example.mydnd.director.DirectorPromptState;
import com.example.mydnd.memory.MemoryContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One stable synthetic scenario used to inspect Director behaviour without touching Room. */
public final class BehaviorTestCase {

    private final String id;
    private final DirectorMode mode;
    private final String playerAction;
    private final DirectorPromptState state;
    private final MemoryContext memoryContext;
    private final List<String> requiredAll;
    private final List<String> requiredAny;
    private final List<String> forbiddenAny;
    private final boolean onlyDoneExpected;

    public BehaviorTestCase(
            String id,
            DirectorMode mode,
            String playerAction,
            DirectorPromptState state,
            MemoryContext memoryContext,
            List<String> requiredAll,
            List<String> requiredAny,
            boolean onlyDoneExpected
    ) {
        this(
                id,
                mode,
                playerAction,
                state,
                memoryContext,
                requiredAll,
                requiredAny,
                Collections.emptyList(),
                onlyDoneExpected
        );
    }

    public BehaviorTestCase(
            String id,
            DirectorMode mode,
            String playerAction,
            DirectorPromptState state,
            MemoryContext memoryContext,
            List<String> requiredAll,
            List<String> requiredAny,
            List<String> forbiddenAny,
            boolean onlyDoneExpected
    ) {
        this.id = safe(id);
        this.mode = mode == null ? DirectorMode.PLAYER_ACTION : mode;
        this.playerAction = safe(playerAction);
        this.state = state == null ? DirectorPromptState.empty() : state;
        this.memoryContext = memoryContext == null
                ? new MemoryContext("", Collections.emptyList(), Collections.emptyList())
                : memoryContext;
        this.requiredAll = immutable(requiredAll);
        this.requiredAny = immutable(requiredAny);
        this.forbiddenAny = immutable(forbiddenAny);
        this.onlyDoneExpected = onlyDoneExpected;
    }

    public String getId() {
        return id;
    }

    public DirectorMode getMode() {
        return mode;
    }

    public String getPlayerAction() {
        return playerAction;
    }

    public DirectorPromptState getState() {
        return state;
    }

    public MemoryContext getMemoryContext() {
        return memoryContext;
    }

    public List<String> getRequiredAll() {
        return requiredAll;
    }

    public List<String> getRequiredAny() {
        return requiredAny;
    }

    public List<String> getForbiddenAny() {
        return forbiddenAny;
    }

    public boolean isOnlyDoneExpected() {
        return onlyDoneExpected;
    }

    public String expectedSummary() {
        if (onlyDoneExpected) {
            return "ONLY_DONE";
        }
        return "all=" + requiredAll
                + ", any=" + requiredAny
                + ", forbidden=" + forbiddenAny;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> immutable(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
