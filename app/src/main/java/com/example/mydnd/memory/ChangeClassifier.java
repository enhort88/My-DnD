package com.example.mydnd.memory;

import android.util.Log;

import com.example.mydnd.llm.GenerationProfile;
import com.example.mydnd.llm.LlmCallback;
import com.example.mydnd.llm.LlmModelManager;
import com.example.mydnd.llm.ModelRole;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class ChangeClassifier {

    private static final String TAG =
            "MyDND_CLASSIFIER";


    private static final Set<String> ALLOWED_TYPES =
            new HashSet<>(
                    Arrays.asList(
                            "ITEM",
                            "CHARACTER",
                            "ABILITY",
                            "NPC",
                            "LOCATION",
                            "QUEST",
                            "RELATION",
                            "WORLD",
                            "FACT",
                            "NONE"
                    )
            );


    private static final Pattern PAIR_PATTERN =
            Pattern.compile(
                    "(?i)(\\d+)\\s*(?:=|:|\\||-)\\s*"
                            + "(ITEM|CHARACTER|ABILITY|NPC|LOCATION|QUEST|RELATION|WORLD|FACT|NONE)"
            );


    private static final Pattern TYPE_PATTERN =
            Pattern.compile(
                    "(?i)\\b"
                            + "(ITEM|CHARACTER|ABILITY|NPC|LOCATION|QUEST|RELATION|WORLD|FACT|NONE)"
                            + "\\b"
            );


    private final LlmModelManager modelManager;

    private final AtomicBoolean working =
            new AtomicBoolean(false);


    public ChangeClassifier(
            LlmModelManager modelManager
    ) {
        this.modelManager =
                modelManager;
    }


    public void classify(
            List<ImportanceFilter.ImportantSentence> sentences,
            Listener listener
    ) {
        if (!working.compareAndSet(false, true)) {

            listener.onSkipped(
                    "ChangeClassifier уже работает"
            );

            return;
        }


        if (sentences == null
                || sentences.isEmpty()) {

            working.set(false);

            listener.onCompleted(
                    new ArrayList<>()
            );

            return;
        }


        String prompt =
                buildPrompt(
                        sentences
                );


        Log.d(
                TAG,
                "Starting classifier"
                        + ", candidates="
                        + sentences.size()
                        + ", promptChars="
                        + prompt.length()
        );


        for (ImportanceFilter.ImportantSentence sentence
                : sentences) {

            Log.d(
                    TAG,
                    "CANDIDATE ["
                            + sentence.getId()
                            + "] "
                            + sentence.getSpeaker()
                            + ": "
                            + sentence.getText()
            );
        }


        listener.onStarted(
                sentences.size()
        );


        modelManager.generate(
                ModelRole.SERVICE,
                prompt,
                GenerationProfile.changeClassification(),
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


                            List<ClassifiedSentence> result =
                                    parseResult(
                                            fullText,
                                            sentences
                                    );


                            working.set(false);


                            Log.d(
                                    TAG,
                                    "CLASSIFIED COUNT="
                                            + result.size()
                            );


                            for (ClassifiedSentence item
                                    : result) {

                                Log.d(
                                        TAG,
                                        "RESULT ["
                                                + item.getId()
                                                + "] "
                                                + item.getType()
                                                + " | "
                                                + item.getText()
                                );
                            }


                            listener.onCompleted(
                                    result
                            );

                        } catch (Throwable throwable) {

                            working.set(false);

                            Log.e(
                                    TAG,
                                    "Failed to parse classifier result",
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
                                "Classification failed",
                                throwable
                        );

                        listener.onError(
                                throwable
                        );
                    }
                }
        );
    }


    private String buildPrompt(
            List<ImportanceFilter.ImportantSentence> sentences
    ) {
        StringBuilder prompt =
                new StringBuilder();


        prompt.append(
                "Classify each candidate by the persistent game state it changes.\n"
        );

        prompt.append(
                "Use exactly one type for every candidate.\n"
        );


        prompt.append(
                "ITEM = item gained, lost, moved, equipped or permanently changed.\n"
        );

        prompt.append(
                "CHARACTER = HP, injury, condition, level, attribute or other character state.\n"
        );

        prompt.append(
                "ABILITY = ability, spell or skill learned, lost or changed.\n"
        );

        prompt.append(
                "NPC = a lasting fact or lasting change about a specific identified non-player character.\n"
        );

        prompt.append(
                "A sound, voice, bark, shadow or unknown unseen creature is not an NPC fact. Use NONE.\n"
        );

        prompt.append(
                "LOCATION = current location change or stable information about a place.\n"
        );

        prompt.append(
                "QUEST = quest started, progressed, completed or failed.\n"
        );

        prompt.append(
                "RELATION = lasting relationship, trust, hostility or reputation change.\n"
        );

        prompt.append(
                "WORLD = stable fact about the world that does not fit another type.\n"
        );

        prompt.append(
                "FACT = important plot fact or secret that does not fit another type.\n"
        );

        prompt.append(
                "NONE = atmosphere, weather, sound, smell, temporary feeling, metaphor, "
                        + "poetic image, decorative detail or unconfirmed speculation.\n"
        );

        prompt.append(
                "Example: \"лай пса слышен за дверью\" = NONE.\n"
        );


        prompt.append(
                "Output one line per candidate in the same order.\n"
        );

        prompt.append(
                "Format exactly: ID=TYPE\n"
        );

        prompt.append(
                "Example:\n"
                        + "1=ITEM\n"
                        + "3=NONE\n"
        );

        prompt.append(
                "No explanation. No JSON. No markdown.\n"
        );


        prompt.append(
                "\nCANDIDATES:\n"
        );


        for (ImportanceFilter.ImportantSentence sentence
                : sentences) {

            prompt.append("[")
                    .append(sentence.getId())
                    .append("] ")
                    .append(sentence.getSpeaker())
                    .append(": ")
                    .append(sentence.getText())
                    .append("\n");
        }


        return wrapGemmaPrompt(
                prompt.toString()
        );
    }


    private List<ClassifiedSentence> parseResult(
            String modelOutput,
            List<ImportanceFilter.ImportantSentence> source
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


        Map<Integer, String> parsedTypes =
                new LinkedHashMap<>();


        Matcher pairMatcher =
                PAIR_PATTERN.matcher(
                        cleaned
                );


        while (pairMatcher.find()) {

            int id;

            try {

                id =
                        Integer.parseInt(
                                pairMatcher.group(1)
                        );

            } catch (NumberFormatException ignored) {

                continue;
            }


            String type =
                    pairMatcher.group(2)
                            .toUpperCase(
                                    Locale.ROOT
                            );


            if (!ALLOWED_TYPES.contains(type)) {

                continue;
            }


            if (findSentenceById(
                    source,
                    id
            ) == null) {

                continue;
            }


            parsedTypes.put(
                    id,
                    type
            );
        }


        /*
         * Gemma 1B иногда может написать просто:
         *
         * ITEM
         *
         * Для одного кандидата это можно
         * безопасно принять.
         */
        if (parsedTypes.isEmpty()
                && source.size() == 1) {

            Matcher typeMatcher =
                    TYPE_PATTERN.matcher(
                            cleaned
                    );


            if (typeMatcher.find()) {

                parsedTypes.put(
                        source.get(0).getId(),
                        typeMatcher.group(1)
                                .toUpperCase(
                                        Locale.ROOT
                                )
                );
            }
        }


        List<ClassifiedSentence> result =
                new ArrayList<>();


        for (ImportanceFilter.ImportantSentence sentence
                : source) {

            String type =
                    parsedTypes.get(
                            sentence.getId()
                    );


            /*
             * Если модель забыла классифицировать строку,
             * безопасно считаем её NONE.
             */
            if (type == null) {

                type =
                        "NONE";


                Log.w(
                        TAG,
                        "Missing classification for id="
                                + sentence.getId()
                                + ", fallback to NONE"
                );
            }


            result.add(
                    new ClassifiedSentence(
                            sentence.getId(),
                            sentence.getSpeaker(),
                            sentence.getText(),
                            type
                    )
            );
        }


        return result;
    }


    private ImportanceFilter.ImportantSentence findSentenceById(
            List<ImportanceFilter.ImportantSentence> sentences,
            int id
    ) {
        for (ImportanceFilter.ImportantSentence sentence
                : sentences) {

            if (sentence.getId() == id) {

                return sentence;
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
                List<ClassifiedSentence> result
        );

        void onSkipped(
                String reason
        );

        void onError(
                Throwable throwable
        );
    }


    public static class ClassifiedSentence {

        private final int id;

        private final String speaker;

        private final String text;

        private final String type;


        public ClassifiedSentence(
                int id,
                String speaker,
                String text,
                String type
        ) {
            this.id =
                    id;

            this.speaker =
                    speaker;

            this.text =
                    text;

            this.type =
                    type;
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


        public String getType() {
            return type;
        }
    }
}