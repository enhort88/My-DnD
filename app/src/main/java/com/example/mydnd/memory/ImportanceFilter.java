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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class ImportanceFilter {

    private static final String TAG =
            "MyDND_IMPORTANCE";

    private static final int MAX_EVENTS =
            2;

    private static final Pattern NUMBER_PATTERN =
            Pattern.compile("\\d+");


    private final AppDatabase database;

    private final LlmModelManager modelManager;

    private final AtomicBoolean working =
            new AtomicBoolean(false);


    public ImportanceFilter(
            AppDatabase database,
            LlmModelManager modelManager
    ) {
        this.database =
                database;

        this.modelManager =
                modelManager;
    }


    public void filterLatestTurn(
            long campaignId,
            Listener listener
    ) {
        if (!working.compareAndSet(false, true)) {

            listener.onSkipped(
                    "ImportanceFilter уже работает"
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
                            "Недостаточно событий"
                    );

                    return;
                }


                // DAO возвращает DESC.
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
                            "Первое событие не PLAYER"
                    );

                    return;
                }


                if (!"MASTER".equals(
                        masterEvent.speaker
                )) {

                    finishSkipped(
                            listener,
                            "Второе событие не MASTER"
                    );

                    return;
                }


                List<SourceSentence> sourceSentences =
                        buildSourceSentences(
                                playerEvent,
                                masterEvent
                        );


                if (sourceSentences.isEmpty()) {

                    finishSkipped(
                            listener,
                            "Нет предложений для анализа"
                    );

                    return;
                }


                Log.d(
                        TAG,
                        "SOURCE TURN:"
                );

                for (SourceSentence sentence
                        : sourceSentences) {

                    Log.d(
                            TAG,
                            "["
                                    + sentence.id
                                    + "] "
                                    + sentence.speaker
                                    + ": "
                                    + sentence.text
                    );
                }


                String prompt =
                        buildPrompt(
                                sourceSentences
                        );


                Log.d(
                        TAG,
                        "Starting filter"
                                + ", sentences="
                                + sourceSentences.size()
                                + ", promptChars="
                                + prompt.length()
                );


                listener.onStarted(
                        sourceSentences.size()
                );


                modelManager.generate(
                        ModelRole.SERVICE,
                        prompt,
                        GenerationProfile.importanceFilter(),
                        new LlmCallback() {

                            @Override
                            public void onToken(
                                    String token
                            ) {
                                // Пользователю не показываем.
                            }


                            @Override
                            public void onComplete(
                                    String fullText
                            ) {
                                try {
                                    Log.d(
                                            TAG,
                                            "RAW RESULT:\n"
                                                    + fullText
                                    );


                                    List<ImportantSentence> result =
                                            parseResult(
                                                    fullText,
                                                    sourceSentences
                                            );


                                    int savedCount =
                                            saveRawFacts(
                                                    campaignId,
                                                    result
                                            );


                                    working.set(false);


                                    Log.d(
                                            TAG,
                                            "IMPORTANT COUNT="
                                                    + result.size()
                                                    + ", SAVED="
                                                    + savedCount
                                    );


                                    for (ImportantSentence sentence
                                            : result) {

                                        Log.d(
                                                TAG,
                                                "KEEP ["
                                                        + sentence.id
                                                        + "] "
                                                        + sentence.speaker
                                                        + ": "
                                                        + sentence.text
                                        );
                                    }


                                    listener.onCompleted(
                                            result
                                    );

                                } catch (Throwable throwable) {

                                    working.set(false);

                                    Log.e(
                                            TAG,
                                            "Failed to parse importance result",
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
                                        "Importance filter failed",
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
                        "Failed to prepare importance filter",
                        throwable
                );

                listener.onError(
                        throwable
                );
            }
        });
    }


    private List<SourceSentence> buildSourceSentences(
            GameEventEntity playerEvent,
            GameEventEntity masterEvent
    ) {
        List<SourceSentence> result =
                new ArrayList<>();


        addSentences(
                result,
                "PLAYER",
                playerEvent.text
        );


        addSentences(
                result,
                "DM",
                masterEvent.text
        );


        return result;
    }


    private void addSentences(
            List<SourceSentence> target,
            String speaker,
            String text
    ) {
        String[] sentences =
                splitSentences(
                        text
                );


        for (String sentence : sentences) {

            String trimmed =
                    sentence.trim();


            if (trimmed.isEmpty()) {
                continue;
            }


            target.add(
                    new SourceSentence(
                            target.size() + 1,
                            speaker,
                            trimmed
                    )
            );
        }
    }


    private String buildPrompt(
            List<SourceSentence> sentences
    ) {
        StringBuilder prompt =
                new StringBuilder();


        prompt.append(
                "Select only sentences that contain information worth remembering after this scene.\n"
        );

        prompt.append(
                "Important: a completed lasting change, item gained/lost/changed, character state change, "
                        + "new ability, quest change, NPC fact, relationship change, location change, "
                        + "important decision, secret or concrete world fact.\n"
        );

        prompt.append(
                "Not important: weather, atmosphere, smells, sounds, poetic imagery, metaphors, "
                        + "temporary feelings, repeated description and decorative details.\n"
        );

        prompt.append(
                "A PLAYER sentence is important only when the DM confirms that the action actually happened.\n"
        );

        prompt.append(
                "When PLAYER and DM describe the same confirmed change, prefer the PLAYER sentence number.\n"
        );

        prompt.append(
                "Return only sentence numbers separated by commas.\n"
        );

        prompt.append(
                "Example output: 1,3\n"
        );

        prompt.append(
                "If nothing is important, return only NONE.\n"
        );

        prompt.append(
                "No words. No explanation. No JSON. No markdown.\n"
        );


        prompt.append(
                "\nSENTENCES:\n"
        );


        for (SourceSentence sentence
                : sentences) {

            prompt.append("[")
                    .append(sentence.id)
                    .append("] ")
                    .append(sentence.speaker)
                    .append(": ")
                    .append(sentence.text)
                    .append("\n");
        }


        return wrapGemmaPrompt(
                prompt.toString()
        );
    }


    private List<ImportantSentence> parseResult(
            String modelOutput,
            List<SourceSentence> sourceSentences
    ) {
        if (modelOutput == null) {

            return Collections.emptyList();
        }


        String cleaned =
                modelOutput
                        .replace("```", "")
                        .trim();


        Log.d(
                TAG,
                "CLEANED RESULT="
                        + cleaned
        );


        String upper =
                cleaned.toUpperCase(
                        Locale.ROOT
                );


        if (upper.contains("NONE")) {

            return Collections.emptyList();
        }


        Matcher matcher =
                NUMBER_PATTERN.matcher(
                        cleaned
                );


        Set<Integer> uniqueIds =
                new LinkedHashSet<>();


        while (matcher.find()) {

            try {
                int id =
                        Integer.parseInt(
                                matcher.group()
                        );


                if (id >= 1
                        && id <= sourceSentences.size()) {

                    uniqueIds.add(
                            id
                    );
                }

            } catch (NumberFormatException ignored) {
                // Просто пропускаем мусор.
            }
        }


        if (uniqueIds.isEmpty()) {

            throw new IllegalStateException(
                    "Модель не вернула NONE или номера предложений: "
                            + cleaned
            );
        }


        List<ImportantSentence> result =
                new ArrayList<>();


        for (Integer id : uniqueIds) {

            SourceSentence source =
                    sourceSentences.get(
                            id - 1
                    );


            result.add(
                    new ImportantSentence(
                            source.id,
                            source.speaker,
                            source.text
                    )
            );
        }


        return result;
    }


    private int saveRawFacts(
            long campaignId,
            List<ImportantSentence> sentences
    ) {
        if (sentences == null
                || sentences.isEmpty()) {

            return 0;
        }


        int savedCount = 0;

        long now =
                System.currentTimeMillis();


        for (ImportantSentence sentence
                : sentences) {

            if (sentence == null
                    || sentence.getText() == null) {

                continue;
            }


            String factText =
                    sentence.getText().trim();


            if (factText.isEmpty()) {
                continue;
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
                        "SKIP DUPLICATE: "
                                + factText
                );

                continue;
            }


            MemoryFactEntity fact =
                    new MemoryFactEntity();

            fact.campaignId =
                    campaignId;

            fact.factType =
                    "RAW_FACT";

            fact.subject =
                    sentence.getSpeaker();

            fact.factText =
                    factText;

            fact.importance =
                    50;

            fact.tags =
                    null;

            fact.active =
                    true;

            fact.createdAt =
                    now;

            fact.updatedAt =
                    now;

            fact.lastUsedAt =
                    0L;


            long factId =
                    database.memoryFactDao()
                            .insert(
                                    fact
                            );


            savedCount++;


            Log.d(
                    TAG,
                    "SAVED RAW FACT id="
                            + factId
                            + " | "
                            + sentence.getSpeaker()
                            + ": "
                            + factText
            );
        }


        return savedCount;
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
                int sentenceCount
        );

        void onCompleted(
                List<ImportantSentence> sentences
        );

        void onSkipped(
                String reason
        );

        void onError(
                Throwable throwable
        );
    }


    public static class ImportantSentence {

        private final int id;

        private final String speaker;

        private final String text;


        public ImportantSentence(
                int id,
                String speaker,
                String text
        ) {
            this.id =
                    id;

            this.speaker =
                    speaker;

            this.text =
                    text;
        }


        public int getId() {
            return id;
        }


        public String getSpeaker() {
            return speaker;
        }


        public String getText() {
            return text;
        }
    }


    private static class SourceSentence {

        final int id;

        final String speaker;

        final String text;


        SourceSentence(
                int id,
                String speaker,
                String text
        ) {
            this.id =
                    id;

            this.speaker =
                    speaker;

            this.text =
                    text;
        }
    }
}