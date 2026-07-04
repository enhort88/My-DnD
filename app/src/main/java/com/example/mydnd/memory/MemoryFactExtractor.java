package com.example.mydnd.memory;

import android.util.Log;

import com.example.mydnd.db.AppDatabase;
import com.example.mydnd.db.DbExecutor;
import com.example.mydnd.db.entity.GameEventEntity;
import com.example.mydnd.db.entity.MemoryFactEntity;
import com.example.mydnd.llm.GenerationProfile;
import com.example.mydnd.llm.LlmCallback;
import com.example.mydnd.llm.LlmModelManager;
import com.example.mydnd.llm.ModelRole;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MemoryFactExtractor {

    private static final String TAG =
            "MyDND_FACTS";

    private static final int MAX_EVENTS = 2;

    private static final int MAX_SUBJECT_CHARS = 80;

    private static final Set<String> ALLOWED_TYPES =
            new HashSet<>(
                    Arrays.asList(
                            "NPC",
                            "LOCATION",
                            "ITEM",
                            "QUEST",
                            "DECISION",
                            "SECRET",
                            "RELATION",
                            "WORLD"
                    )
            );

    /*
     * Gemma иногда пишет не наш внутренний тип:
     *
     * KEY, 1
     * WEAPON|2
     * PLACE: 1
     *
     * Поэтому сначала ищем смысловой тип,
     * потом нормализуем его.
     */
    private static final Pattern TYPE_PATTERN =
            Pattern.compile(
                    "(?i)\\b("
                            + "NONE"
                            + "|NPC|CHARACTER|PERSON"
                            + "|LOCATION|PLACE"
                            + "|ITEM|KEY|WEAPON|DOCUMENT|TOOL|ARTIFACT"
                            + "|QUEST|MISSION"
                            + "|DECISION|CHOICE"
                            + "|SECRET"
                            + "|RELATION|RELATIONSHIP"
                            + "|WORLD"
                            + ")\\b"
            );

    private static final Pattern NUMBER_PATTERN =
            Pattern.compile("\\d+");

    private final AppDatabase database;

    private final LlmModelManager modelManager;

    private final AtomicBoolean working =
            new AtomicBoolean(false);


    public MemoryFactExtractor(
            AppDatabase database,
            LlmModelManager modelManager
    ) {
        this.database = database;
        this.modelManager = modelManager;
    }


    public void extractLatest(
            long campaignId,
            Listener listener
    ) {
        if (!working.compareAndSet(false, true)) {

            listener.onSkipped(
                    "Extractor уже работает"
            );

            return;
        }

        DbExecutor.execute(() -> {

            try {
                List<GameEventEntity> events =
                        database.gameEventDao()
                                .getRecentPromptEvents(
                                        campaignId,
                                        MAX_EVENTS
                                );

                if (events == null
                        || events.size() < 2) {

                    finishSkipped(
                            listener,
                            "Недостаточно событий для анализа хода"
                    );

                    return;
                }

                // DAO возвращает события в DESC.
                // После reverse получаем:
                // PLAYER -> MASTER.
                Collections.reverse(events);

                GameEventEntity playerEvent =
                        events.get(0);

                GameEventEntity masterEvent =
                        events.get(1);

                if (!"PLAYER".equals(
                        playerEvent.speaker
                )) {

                    finishSkipped(
                            listener,
                            "Первое событие хода не PLAYER"
                    );

                    return;
                }

                if (!"MASTER".equals(
                        masterEvent.speaker
                )) {

                    finishSkipped(
                            listener,
                            "Второе событие хода не MASTER"
                    );

                    return;
                }

                if (playerEvent.text == null
                        || playerEvent.text.trim().isEmpty()) {

                    finishSkipped(
                            listener,
                            "Действие игрока пустое"
                    );

                    return;
                }

                if (masterEvent.text == null
                        || masterEvent.text.trim().isEmpty()) {

                    finishSkipped(
                            listener,
                            "Ответ мастера пуст"
                    );

                    return;
                }

                Log.d(
                        TAG,
                        "SOURCE TURN:"
                                + "\nPLAYER id="
                                + playerEvent.id
                                + "\n"
                                + playerEvent.text
                                + "\n\nMASTER id="
                                + masterEvent.id
                                + "\n"
                                + masterEvent.text
                );

                String prompt =
                        buildPrompt(
                                playerEvent,
                                masterEvent
                        );

                Log.d(
                        TAG,
                        "Starting extraction"
                                + ", playerEventId="
                                + playerEvent.id
                                + ", masterEventId="
                                + masterEvent.id
                                + ", promptChars="
                                + prompt.length()
                );

                listener.onStarted(2);

                modelManager.generate(
                        ModelRole.SERVICE,
                        prompt,
                        GenerationProfile.factExtraction(),
                        new LlmCallback() {

                            @Override
                            public void onToken(
                                    String token
                            ) {
                                // Служебную генерацию
                                // пользователю не показываем.
                            }


                            @Override
                            public void onComplete(
                                    String fullText
                            ) {
                                try {
                                    Log.d(
                                            TAG,
                                            "EXTRACTION RAW:\n"
                                                    + fullText
                                    );

                                    String result =
                                            processAndSave(
                                                    campaignId,
                                                    playerEvent,
                                                    masterEvent,
                                                    fullText
                                            );

                                    working.set(false);

                                    listener.onCompleted(
                                            result
                                    );

                                } catch (Throwable throwable) {

                                    working.set(false);

                                    Log.e(
                                            TAG,
                                            "Failed to save extracted fact",
                                            throwable
                                    );

                                    listener.onError(
                                            throwable
                                    );
                                }
                            }


                            @Override
                            public void onError(
                                    Throwable throwable
                            ) {
                                working.set(false);

                                Log.e(
                                        TAG,
                                        "Extraction failed",
                                        throwable
                                );

                                listener.onError(
                                        throwable
                                );
                            }
                        }
                );

            } catch (Throwable throwable) {

                working.set(false);

                Log.e(
                        TAG,
                        "Failed to prepare extraction",
                        throwable
                );

                listener.onError(
                        throwable
                );
            }
        });
    }


    private String processAndSave(
            long campaignId,
            GameEventEntity playerEvent,
            GameEventEntity masterEvent,
            String modelOutput
    ) {
        String cleaned =
                cleanModelOutput(
                        modelOutput
                );

        Log.d(
                TAG,
                "EXTRACTION CLEANED:\n"
                        + cleaned
        );

        ParsedFact parsed =
                parseCandidate(
                        cleaned
                );

        if (parsed == null) {

            Log.w(
                    TAG,
                    "REJECTED: model output cannot be parsed"
            );

            return "REJECTED";
        }

        if (parsed.none) {

            Log.d(
                    TAG,
                    "No long-term fact found"
            );

            return "NONE";
        }

        String[] sentences =
                buildSourceSentences(
                        playerEvent,
                        masterEvent
                );

        List<Integer> sentenceIds =
                validateSentenceIds(
                        parsed.sentenceIds,
                        sentences.length
                );

        if (sentenceIds.isEmpty()) {

            Log.w(
                    TAG,
                    "REJECTED: invalid sentence ids"
                            + parsed.sentenceIds
            );

            return "REJECTED";
        }

        String factText =
                buildFactText(
                        sentences,
                        sentenceIds
                );

        if (factText.isEmpty()) {

            Log.w(
                    TAG,
                    "REJECTED: empty fact text"
            );

            return "REJECTED";
        }

        int duplicateCount =
                database.memoryFactDao()
                        .countActiveExactFact(
                                campaignId,
                                factText
                        );

        if (duplicateCount > 0) {

            Log.d(
                    TAG,
                    "DUPLICATE SKIPPED:"
                            + "\ntype="
                            + parsed.type
                            + "\nfact="
                            + factText
            );

            return "DUPLICATE";
        }

        long now =
                System.currentTimeMillis();

        MemoryFactEntity entity =
                new MemoryFactEntity();

        entity.campaignId =
                campaignId;

        entity.factType =
                parsed.type;

        entity.subject =
                buildSubject(
                        factText
                );

        entity.factText =
                factText;

        entity.importance =
                defaultImportance(
                        parsed.type
                );

        entity.tags =
                null;

        entity.active =
                true;

        entity.createdAt =
                now;

        entity.updatedAt =
                now;

        entity.lastUsedAt =
                0L;

        long factId =
                database.memoryFactDao()
                        .insert(entity);

        Log.d(
                TAG,
                "SAVED MEMORY FACT:"
                        + "\nid="
                        + factId
                        + "\ncampaignId="
                        + campaignId
                        + "\ntype="
                        + entity.factType
                        + "\nsentenceIds="
                        + sentenceIds
                        + "\nsubject="
                        + entity.subject
                        + "\nfact="
                        + entity.factText
        );

        return "SAVED id=" + factId;
    }


    private ParsedFact parseCandidate(
            String output
    ) {
        if (output == null
                || output.trim().isEmpty()) {

            return null;
        }

        String[] lines =
                output.split("\\R");

        for (String rawLine : lines) {

            String line =
                    rawLine.trim();

            if (line.isEmpty()) {
                continue;
            }

            Matcher typeMatcher =
                    TYPE_PATTERN.matcher(
                            line
                    );

            if (!typeMatcher.find()) {
                continue;
            }

            String type =
                    normalizeType(
                            typeMatcher.group(1)
                    );

            if ("NONE".equals(type)) {

                return ParsedFact.none();
            }

            if (!ALLOWED_TYPES.contains(type)) {
                continue;
            }

            String afterType =
                    line.substring(
                            typeMatcher.end()
                    );

            Matcher numberMatcher =
                    NUMBER_PATTERN.matcher(
                            afterType
                    );

            List<Integer> sentenceIds =
                    new ArrayList<>();

            while (numberMatcher.find()) {

                try {
                    sentenceIds.add(
                            Integer.parseInt(
                                    numberMatcher.group()
                            )
                    );

                } catch (NumberFormatException ignored) {
                    // Невалидное число просто пропускаем.
                }
            }

            if (sentenceIds.isEmpty()) {
                continue;
            }

            Log.d(
                    TAG,
                    "PARSED:"
                            + "\nrawLine="
                            + line
                            + "\ntype="
                            + type
                            + "\nsentenceIds="
                            + sentenceIds
            );

            return new ParsedFact(
                    type,
                    sentenceIds,
                    false
            );
        }

        return null;
    }


    private String normalizeType(
            String rawType
    ) {
        if (rawType == null) {
            return "";
        }

        String type =
                rawType
                        .trim()
                        .toUpperCase(Locale.ROOT);

        switch (type) {

            case "CHARACTER":
            case "PERSON":
                return "NPC";

            case "PLACE":
                return "LOCATION";

            case "KEY":
            case "WEAPON":
            case "DOCUMENT":
            case "TOOL":
            case "ARTIFACT":
                return "ITEM";

            case "MISSION":
                return "QUEST";

            case "CHOICE":
                return "DECISION";

            case "RELATIONSHIP":
                return "RELATION";

            default:
                return type;
        }
    }


    private List<Integer> validateSentenceIds(
            List<Integer> rawIds,
            int sentenceCount
    ) {
        if (rawIds == null
                || rawIds.isEmpty()) {

            return Collections.emptyList();
        }

        LinkedHashSet<Integer> uniqueIds =
                new LinkedHashSet<>();

        for (Integer id : rawIds) {

            if (id == null
                    || id < 1
                    || id > sentenceCount) {

                return Collections.emptyList();
            }

            uniqueIds.add(id);
        }

        List<Integer> result =
                new ArrayList<>(
                        uniqueIds
                );

        Collections.sort(result);

        return result;
    }


    private String buildFactText(
            String[] sentences,
            List<Integer> sentenceIds
    ) {
        StringBuilder fact =
                new StringBuilder();

        for (Integer sentenceId : sentenceIds) {

            int index =
                    sentenceId - 1;

            if (index < 0
                    || index >= sentences.length) {

                continue;
            }

            String sentence =
                    sentences[index].trim();

            if (sentence.isEmpty()) {
                continue;
            }

            if (fact.length() > 0) {
                fact.append(' ');
            }

            fact.append(sentence);
        }

        return fact
                .toString()
                .trim();
    }


    private String buildSubject(
            String factText
    ) {
        if (factText == null
                || factText.trim().isEmpty()) {

            return "Fact";
        }

        String subject =
                factText.trim();

        int sentenceEnd =
                findFirstSentenceEnd(
                        subject
                );

        if (sentenceEnd > 0) {

            subject =
                    subject.substring(
                            0,
                            sentenceEnd
                    ).trim();
        }

        if (subject.length()
                <= MAX_SUBJECT_CHARS) {

            return subject;
        }

        return subject
                .substring(
                        0,
                        MAX_SUBJECT_CHARS - 1
                )
                .trim()
                + "…";
    }


    private int findFirstSentenceEnd(
            String text
    ) {
        int result =
                -1;

        char[] marks =
                {'.', '!', '?'};

        for (char mark : marks) {

            int index =
                    text.indexOf(mark);

            if (index > 0
                    && (result < 0
                    || index < result)) {

                result =
                        index;
            }
        }

        return result;
    }


    private int defaultImportance(
            String type
    ) {
        switch (type) {

            case "QUEST":
            case "DECISION":
            case "SECRET":
                return 9;

            case "ITEM":
            case "RELATION":
                return 8;

            case "NPC":
            case "LOCATION":
                return 7;

            case "WORLD":
            default:
                return 6;
        }
    }


    private String[] splitSentences(
            String text
    ) {
        if (text == null
                || text.trim().isEmpty()) {

            return new String[0];
        }

        return text
                .trim()
                .split(
                        "(?<=[.!?])\\s+"
                );
    }


    private String cleanModelOutput(
            String text
    ) {
        if (text == null) {
            return "";
        }

        return text
                .replaceAll(
                        "(?i)```json",
                        ""
                )
                .replace(
                        "```",
                        ""
                )
                .trim();
    }


    private String buildPrompt(
            GameEventEntity playerEvent,
            GameEventEntity masterEvent
    ) {
        String[] playerSentences =
                splitSentences(
                        playerEvent.text
                );

        String[] masterSentences =
                splitSentences(
                        masterEvent.text
                );

        StringBuilder prompt =
                new StringBuilder();


        prompt.append(
                "Find at most one important long-term RPG memory fact from this completed turn.\n"
        );

        prompt.append(
                "Use PLAYER and DM together.\n"
        );

        prompt.append(
                "A PLAYER action is not automatically successful.\n"
        );

        prompt.append(
                "Store a player action only if the DM confirms that it happened.\n"
        );

        prompt.append(
                "If the DM confirms a completed player action, prefer the PLAYER sentence number as the supporting sentence.\n"
        );

        prompt.append(
                "DM narration may contain metaphors and poetic imagery.\n"
        );

        prompt.append(
                "Ignore atmosphere, weather, smells, sounds, mood, metaphors, comparisons, symbolism and foreshadowing.\n"
        );

        prompt.append(
                "Do not treat phrases like ancient artifact, magical object, dark power or unknown force as facts unless the DM explicitly confirms a literal property or change.\n"
        );

        prompt.append(
                "Do not invent information.\n"
        );

        prompt.append(
                "Choose exactly one type from: "
                        + "NPC, LOCATION, ITEM, QUEST, DECISION, SECRET, RELATION, WORLD.\n"
        );

        prompt.append(
                "Keys, weapons, documents, tools and artifacts are ITEM.\n"
        );

        prompt.append(
                "After the type, write | and the minimum supporting sentence numbers separated by commas.\n"
        );

        prompt.append(
                "Output exactly one line.\n"
        );

        prompt.append(
                "No JSON. No markdown. No explanation.\n"
        );

        prompt.append(
                "If there is no important confirmed long-term fact, output only NONE.\n"
        );


        prompt.append(
                "\nSOURCE:\n"
        );

        int sentenceNumber =
                1;


        for (String sentence : playerSentences) {

            prompt.append("[")
                    .append(sentenceNumber)
                    .append("] PLAYER: ")
                    .append(sentence.trim())
                    .append("\n");

            sentenceNumber++;
        }


        for (String sentence : masterSentences) {

            prompt.append("[")
                    .append(sentenceNumber)
                    .append("] DM: ")
                    .append(sentence.trim())
                    .append("\n");

            sentenceNumber++;
        }


        prompt.append(
                "\nANSWER:\n"
        );

        return wrapGemmaPrompt(
                prompt.toString()
        );
    }


    private String wrapGemmaPrompt(
            String userPrompt
    ) {
        return "<start_of_turn>user\n"
                + userPrompt.trim()
                + "\n<end_of_turn>\n"
                + "<start_of_turn>model\n";
    }


    private void finishSkipped(
            Listener listener,
            String reason
    ) {
        working.set(false);

        Log.d(
                TAG,
                "Skipped: "
                        + reason
        );

        listener.onSkipped(
                reason
        );
    }


    public boolean isWorking() {
        return working.get();
    }


    public interface Listener {

        void onStarted(
                int eventCount
        );

        void onCompleted(
                String result
        );

        void onSkipped(
                String reason
        );

        void onError(
                Throwable throwable
        );
    }


    private static class ParsedFact {

        final String type;

        final List<Integer> sentenceIds;

        final boolean none;


        ParsedFact(
                String type,
                List<Integer> sentenceIds,
                boolean none
        ) {
            this.type =
                    type;

            this.sentenceIds =
                    sentenceIds;

            this.none =
                    none;
        }


        static ParsedFact none() {

            return new ParsedFact(
                    "NONE",
                    Collections.emptyList(),
                    true
            );
        }
    }
    private String[] buildSourceSentences(
            GameEventEntity playerEvent,
            GameEventEntity masterEvent
    ) {
        List<String> result =
                new ArrayList<>();

        String[] playerSentences =
                splitSentences(
                        playerEvent.text
                );

        String[] masterSentences =
                splitSentences(
                        masterEvent.text
                );

        Collections.addAll(
                result,
                playerSentences
        );

        Collections.addAll(
                result,
                masterSentences
        );

        return result.toArray(
                new String[0]
        );
    }
}