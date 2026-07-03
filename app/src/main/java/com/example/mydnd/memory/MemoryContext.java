package com.example.mydnd.memory;

import com.example.mydnd.game.GameEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MemoryContext {

    private final String latestSummary;
    private final List<GameEvent> recentEvents;
    private final List<String> relevantFacts;

    public MemoryContext(
            String latestSummary,
            List<GameEvent> recentEvents,
            List<String> relevantFacts
    ) {
        this.latestSummary = latestSummary == null
                ? ""
                : latestSummary;

        this.recentEvents = recentEvents == null
                ? new ArrayList<>()
                : new ArrayList<>(recentEvents);

        this.relevantFacts = relevantFacts == null
                ? new ArrayList<>()
                : new ArrayList<>(relevantFacts);
    }

    public String getLatestSummary() {
        return latestSummary;
    }

    public List<GameEvent> getRecentEvents() {
        return Collections.unmodifiableList(recentEvents);
    }

    public List<String> getRelevantFacts() {
        return Collections.unmodifiableList(relevantFacts);
    }

    public boolean hasSummary() {
        return !latestSummary.trim().isEmpty();
    }

    public boolean hasRelevantFacts() {
        return !relevantFacts.isEmpty();
    }
}