package com.example.mydnd.memory;

import android.util.Log;

import com.example.mydnd.llm.GenerationProfile;
import com.example.mydnd.llm.LlmCallback;
import com.example.mydnd.llm.LlmModelManager;
import com.example.mydnd.llm.ModelRole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class EntityExtractor {

    private static final String TAG =
            "MyDND_ENTITY";


    private static final Pattern RESULT_PATTERN =
            Pattern.compile(
                    "^\\s*\\[?(\\d+)\\]?\\s*(?:=|:|\\|)\\s*(.+?)\\s*$"
            );


    private final LlmModelManager modelManager;

    private final AtomicBoolean working =
            new AtomicBoolean(false);


    public EntityExtractor(
            LlmModelManager modelManager
    ) {
        this.modelManager =
                modelManager;
    }


    public void extract(
            List<ChangeClassifier.ClassifiedSentence> source,
            Listener listener
    ) {
        if (!working.compareAndSet(false, true)) {

            listener.onSkipped(
                    "EntityExtractor уже работает"
            );

            return;
        }


        List<ChangeClassifier.ClassifiedSentence> candidates =
                getSupportedCandidates(
                        source
                );


        if (candidates.isEmpty()) {

            working.set(false);

            listener.onCompleted(
                    new ArrayList<>()
            );

            return;
        }


        String prompt =
                buildPrompt(
                        candidates
                );


        Log.d(
                TAG,
                "Starting entity extraction"
                        + ", candidates="
                        + candidates.size()
                        + ", promptChars="
                        + prompt.length()
        );


        for (ChangeClassifier.ClassifiedSentence item
                : candidates) {

            Log.d(
                    TAG,
                    "CANDIDATE ["
                            + item.getId()
                            + "] "
                            + item.getType()
                            + ": "
                            + item.getText()
            );
        }


        listener.onStarted(
                candidates.size()
        );


        modelManager.generate(
                ModelRole.SERVICE,
                prompt,
                GenerationProfile.entityExtraction(),
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


                            List<EntityResult> result =
                                    parseResult(
                                            fullText,
                                            candidates
                                    );


                            working.set(false);


                            for (EntityResult item
                                    : result) {

                                Log.d(
                                        TAG,
                                        "RESULT ["
                                                + item.getId()
                                                + "] "
                                                + item.getType()
                                                + " | entity="
                                                + item.getEntityName()
                                                + " | source="
                                                + item.getSourceText()
                                );
                            }


                            listener.onCompleted(
                                    result
                            );

                        } catch (Throwable throwable) {

                            working.set(false);

                            Log.e(
                                    TAG,
                                    "Failed to parse entity result",
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
                                "Entity extraction failed",
                                throwable
                        );

                        listener.onError(
                                throwable
                        );
                    }
                }
        );
    }


    private List<ChangeClassifier.ClassifiedSentence> getSupportedCandidates(
            List<ChangeClassifier.ClassifiedSentence> source
    ) {
        List<ChangeClassifier.ClassifiedSentence> result =
                new ArrayList<>();


        if (source == null) {
            return result;
        }


        for (ChangeClassifier.ClassifiedSentence item
                : source) {

            /*
             * Пока тестируем только предметы.
             * Потом сюда добавим NPC, LOCATION,
             * QUEST и остальные сущности.
             */
            if ("ITEM".equals(
                    item.getType()
            )) {

                result.add(
                        item
                );
            }
        }


        return result;
    }


    private String buildPrompt(
            List<ChangeClassifier.ClassifiedSentence> candidates
    ) {
        StringBuilder prompt =
                new StringBuilder();


        prompt.append(
                "Extract the concrete item name from each sentence.\n"
        );

        prompt.append(
                "Return only the item being acted on.\n"
        );

        prompt.append(
                "Do not classify the action.\n"
        );

        prompt.append(
                "Do not explain what happened.\n"
        );

        prompt.append(
                "Do not add properties that are not explicitly written.\n"
        );

        prompt.append(
                "Keep the same language as the source sentence.\n"
        );

        prompt.append(
                "Use the shortest clear noun phrase that identifies the item.\n"
        );

        prompt.append(
                "Use the numeric ID shown in square brackets.\n"
        );

        prompt.append(
                "Example:\n"
                        + "[7] I take the rusty lantern\n"
                        + "7=rusty lantern\n"
        );

        prompt.append(
                "Output exactly one line per candidate.\n"
        );

        prompt.append(
                "Format exactly: NUMBER=ITEM_NAME\n"
        );


        prompt.append(
                "No JSON. No markdown. No explanation.\n"
        );


        prompt.append(
                "\nCANDIDATES:\n"
        );


        for (ChangeClassifier.ClassifiedSentence item
                : candidates) {

            prompt.append("[")
                    .append(item.getId())
                    .append("] ")
                    .append(item.getText())
                    .append("\n");
        }


        return wrapGemmaPrompt(
                prompt.toString()
        );
    }


    private List<EntityResult> parseResult(
            String modelOutput,
            List<ChangeClassifier.ClassifiedSentence> source
    ) {
        String cleaned =
                modelOutput == null
                        ? ""
                        : modelOutput
                          .replace("```", "")
                          .trim();


        Log.d(
                TAG,
                "CLEANED RESULT="
                        + cleaned
        );


        List<EntityResult> result =
                new ArrayList<>();


        String[] lines =
                cleaned.split("\\R");


        for (String rawLine : lines) {

            Matcher matcher =
                    RESULT_PATTERN.matcher(
                            rawLine
                    );


            if (!matcher.matches()) {
                continue;
            }


            int id;

            try {

                id =
                        Integer.parseInt(
                                matcher.group(1)
                        );

            } catch (NumberFormatException ignored) {

                continue;
            }


            ChangeClassifier.ClassifiedSentence sourceItem =
                    findById(
                            source,
                            id
                    );


            if (sourceItem == null) {
                continue;
            }


            String entityName =
                    cleanEntityName(
                            matcher.group(2)
                    );


            if (entityName.isEmpty()) {
                continue;
            }


            result.add(
                    new EntityResult(
                            sourceItem.getId(),
                            sourceItem.getType(),
                            sourceItem.getText(),
                            entityName
                    )
            );
        }


        /*
         * Gemma 1B может для одного кандидата
         * вернуть просто:
         *
         * фонарь
         *
         * Это безопасно принимаем.
         */
        if (result.isEmpty()
                && source.size() == 1) {

            String entityName =
                    cleanEntityName(
                            cleaned
                    );


            if (!entityName.isEmpty()) {

                ChangeClassifier.ClassifiedSentence item =
                        source.get(0);


                result.add(
                        new EntityResult(
                                item.getId(),
                                item.getType(),
                                item.getText(),
                                entityName
                        )
                );
            }
        }


        return result;
    }


    private String cleanEntityName(
            String text
    ) {
        if (text == null) {
            return "";
        }


        String result =
                text.trim();


// Gemma иногда копирует служебную метку:
// ID=фонарь
// ITEM=фонарь
// ENTITY=фонарь
        result =
                result.replaceFirst(
                        "(?i)^\\s*(?:ID|ITEM_NAME|ITEM|ENTITY)\\s*[:=|]\\s*",
                        ""
                );


// На случай:
// 1=фонарь
// [1]=фонарь
        result =
                result.replaceFirst(
                        "^\\s*\\[?\\d+\\]?\\s*[:=|]\\s*",
                        ""
                );


        result =
                result
                        .replaceAll(
                                "^[\"'«»]+|[\"'«»]+$",
                                ""
                        )
                        .trim();


        if (result.length() > 100) {

            return "";
        }


        return result;
    }


    private ChangeClassifier.ClassifiedSentence findById(
            List<ChangeClassifier.ClassifiedSentence> source,
            int id
    ) {
        for (ChangeClassifier.ClassifiedSentence item
                : source) {

            if (item.getId() == id) {
                return item;
            }
        }


        return null;
    }


    private String wrapGemmaPrompt(
            String userPrompt
    ) {
        return "<start_of_turn>user\n"
                + userPrompt.trim()
                + "\n<end_of_turn>\n"
                + "<start_of_turn>model\n";
    }


    public interface Listener {

        void onStarted(
                int candidateCount
        );

        void onCompleted(
                List<EntityResult> result
        );

        void onSkipped(
                String reason
        );

        void onError(
                Throwable throwable
        );
    }


    public static class EntityResult {

        private final int id;

        private final String type;

        private final String sourceText;

        private final String entityName;


        public EntityResult(
                int id,
                String type,
                String sourceText,
                String entityName
        ) {
            this.id =
                    id;

            this.type =
                    type;

            this.sourceText =
                    sourceText;

            this.entityName =
                    entityName;
        }


        public int getId() {
            return id;
        }


        public String getType() {
            return type;
        }


        public String getSourceText() {
            return sourceText;
        }


        public String getEntityName() {
            return entityName;
        }
    }
}