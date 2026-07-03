package com.example.mydnd.memory;

import com.example.mydnd.db.AppDatabase;
import com.example.mydnd.db.entity.GameEventEntity;
import com.example.mydnd.db.entity.MemoryFactEntity;
import com.example.mydnd.db.entity.SummaryEntity;
import com.example.mydnd.game.GameEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class CampaignMemory {

    private static final int RECENT_EVENTS_LIMIT = 4;
    private static final int FACT_CANDIDATE_LIMIT = 30;
    private static final int RELEVANT_FACT_LIMIT = 6;

    private final AppDatabase database;

    public CampaignMemory(AppDatabase database) {
        this.database = database;
    }

    public MemoryContext buildContext(
            long campaignId,
            String playerAction
    ) {
        SummaryEntity latestSummary =
                database.summaryDao()
                        .getLatestForCampaign(campaignId);

        String summaryText = "";

        long summarizedToEventId = 0L;

        if (latestSummary != null) {
            if (latestSummary.text != null) {
                summaryText =
                        latestSummary.text.trim();
            }

            summarizedToEventId =
                    latestSummary.toEventId;
        }

        List<GameEvent> recentEvents =
                loadRecentEvents(
                        campaignId,
                        summarizedToEventId,
                        playerAction
                );

        List<String> relevantFacts =
                loadRelevantFacts(
                        campaignId,
                        playerAction
                );

        return new MemoryContext(
                summaryText,
                recentEvents,
                relevantFacts
        );
    }

    private String loadLatestSummary(long campaignId) {
        SummaryEntity summary =
                database.summaryDao().getLatestForCampaign(campaignId);

        if (summary == null || summary.text == null) {
            return "";
        }

        return summary.text.trim();
    }

    private List<GameEvent> loadRecentEvents(
            long campaignId,
            long afterEventId,
            String currentPlayerAction
    ) {
        List<GameEventEntity> entities =
                database.gameEventDao()
                        .getRecentPromptEventsAfterId(
                                campaignId,
                                afterEventId,
                                RECENT_EVENTS_LIMIT + 1
                        );

        if (entities == null || entities.isEmpty()) {
            return new ArrayList<>();
        }

        List<GameEvent> result = new ArrayList<>();

        boolean currentActionSkipped = false;

        for (GameEventEntity entity : entities) {
            if (!currentActionSkipped
                    && isCurrentPlayerAction(entity, currentPlayerAction)) {

                currentActionSkipped = true;
                continue;
            }

            GameEvent event = toGameEvent(entity);

            if (event != null) {
                result.add(event);
            }

            if (result.size() >= RECENT_EVENTS_LIMIT) {
                break;
            }
        }

        // DAO вернул от новых к старым.
        // Для prompt нужна обычная хронология.
        Collections.reverse(result);

        return result;
    }

    private boolean isCurrentPlayerAction(
            GameEventEntity entity,
            String currentPlayerAction
    ) {
        if (currentPlayerAction == null
                || currentPlayerAction.trim().isEmpty()) {
            return false;
        }

        if (!"PLAYER".equals(entity.speaker)) {
            return false;
        }

        return currentPlayerAction.trim()
                .equalsIgnoreCase(entity.text.trim());
    }

    private GameEvent toGameEvent(GameEventEntity entity) {
        try {
            GameEvent.Speaker speaker =
                    GameEvent.Speaker.valueOf(entity.speaker);

            return new GameEvent(
                    speaker,
                    entity.text,
                    entity.createdAt,
                    entity.includeInPrompt
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> loadRelevantFacts(
            long campaignId,
            String playerAction
    ) {
        List<MemoryFactEntity> candidates =
                database.memoryFactDao().getActiveFacts(
                        campaignId,
                        FACT_CANDIDATE_LIMIT
                );

        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }

        Set<String> actionWords =
                extractMeaningfulWords(playerAction);

        List<ScoredFact> scoredFacts = new ArrayList<>();

        for (MemoryFactEntity fact : candidates) {
            int score = calculateFactScore(fact, actionWords);

            scoredFacts.add(
                    new ScoredFact(fact, score)
            );
        }

        scoredFacts.sort(
                Comparator.comparingInt(ScoredFact::getScore)
                        .reversed()
        );

        List<String> result = new ArrayList<>();

        int limit = Math.min(
                RELEVANT_FACT_LIMIT,
                scoredFacts.size()
        );

        long now = System.currentTimeMillis();

        for (int i = 0; i < limit; i++) {
            MemoryFactEntity fact =
                    scoredFacts.get(i).getFact();

            result.add(formatFact(fact));

            database.memoryFactDao().markUsed(
                    fact.id,
                    now
            );
        }

        return result;
    }

    private int calculateFactScore(
            MemoryFactEntity fact,
            Set<String> actionWords
    ) {
        int score = fact.importance;

        if (actionWords.isEmpty()) {
            return score;
        }

        String subject = normalize(fact.subject);
        String factText = normalize(fact.factText);
        String tags = normalize(fact.tags);
        String factType = normalize(fact.factType);

        for (String word : actionWords) {
            if (subject.contains(word)) {
                score += 30;
            }

            if (tags.contains(word)) {
                score += 20;
            }

            if (factText.contains(word)) {
                score += 10;
            }

            if (factType.contains(word)) {
                score += 5;
            }
        }

        return score;
    }

    private Set<String> extractMeaningfulWords(String text) {
        Set<String> result = new HashSet<>();

        if (text == null || text.trim().isEmpty()) {
            return result;
        }

        String[] words = normalize(text)
                .split("[^\\p{L}\\p{N}]+");

        for (String word : words) {
            if (word.length() < 3) {
                continue;
            }

            if (isStopWord(word)) {
                continue;
            }

            result.add(word);
        }

        return result;
    }

    private boolean isStopWord(String word) {
        return word.equals("это")
                || word.equals("как")
                || word.equals("что")
                || word.equals("где")
                || word.equals("когда")
                || word.equals("тогда")
                || word.equals("меня")
                || word.equals("мне")
                || word.equals("ему")
                || word.equals("она")
                || word.equals("они")
                || word.equals("или")
                || word.equals("для");
    }

    private String formatFact(MemoryFactEntity fact) {
        StringBuilder builder = new StringBuilder();

        builder.append("[");
        builder.append(fact.factType);
        builder.append("] ");

        if (fact.subject != null
                && !fact.subject.trim().isEmpty()) {

            builder.append(fact.subject.trim());
            builder.append(": ");
        }

        builder.append(fact.factText.trim());

        return builder.toString();
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }

        return text
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private static class ScoredFact {

        private final MemoryFactEntity fact;
        private final int score;

        private ScoredFact(
                MemoryFactEntity fact,
                int score
        ) {
            this.fact = fact;
            this.score = score;
        }

        public MemoryFactEntity getFact() {
            return fact;
        }

        public int getScore() {
            return score;
        }
    }
}