package com.example.mydnd.memory;

import android.util.Log;

import com.example.mydnd.llm.GenerationProfile;
import com.example.mydnd.llm.LlmCallback;
import com.example.mydnd.llm.LlmModelManager;
import com.example.mydnd.llm.ModelRole;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class ChangeOperationClassifier {

    private static final String TAG =
            "MyDND_OPERATION";


    private static final Pattern RESULT_PATTERN =
            Pattern.compile(
                    "(?i)\\[?(\\d+)\\]?\\s*"
                            + "(?:=|:|\\||-)?\\s*"
                            + "(ADD|REMOVE|UPDATE|MOVE|START|COMPLETE|FAIL|NONE)\\b"
            );


    private final LlmModelManager modelManager;

    private final AtomicBoolean working =
            new AtomicBoolean(false);


    public ChangeOperationClassifier(
            LlmModelManager modelManager
    ) {
        this.modelManager =
                modelManager;
    }


    public void classify(
            List<ChangeClassifier.ClassifiedSentence> source,
            Listener listener
    ) {
        if (!working.compareAndSet(false, true)) {

            listener.onSkipped(
                    "ChangeOperationClassifier уже работает"
            );

            return;
        }


        List<ChangeClassifier.ClassifiedSentence> changes =
                removeNone(
                        source
                );


        if (changes.isEmpty()) {

            working.set(false);

            listener.onCompleted(
                    new ArrayList<>()
            );

            return;
        }


        String prompt =
                buildPrompt(
                        changes
                );


        Log.d(
                TAG,
                "Starting operation classifier"
                        + ", candidates="
                        + changes.size()
                        + ", promptChars="
                        + prompt.length()
        );


        for (ChangeClassifier.ClassifiedSentence item
                : changes) {

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
                changes.size()
        );


        modelManager.generate(
                ModelRole.SERVICE,
                prompt,
                GenerationProfile.changeOperation(),
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


                            List<OperationResult> result =
                                    parseResult(
                                            fullText,
                                            changes
                                    );


                            working.set(false);


                            for (OperationResult item
                                    : result) {

                                Log.d(
                                        TAG,
                                        "RESULT ["
                                                + item.getId()
                                                + "] "
                                                + item.getType()
                                                + " / "
                                                + item.getOperation()
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
                                    "Failed to parse operation result",
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
                                "Operation classification failed",
                                throwable
                        );

                        listener.onError(
                                throwable
                        );
                    }
                }
        );
    }


    private List<ChangeClassifier.ClassifiedSentence> removeNone(
            List<ChangeClassifier.ClassifiedSentence> source
    ) {
        List<ChangeClassifier.ClassifiedSentence> result =
                new ArrayList<>();


        if (source == null) {
            return result;
        }


        for (ChangeClassifier.ClassifiedSentence item
                : source) {

            if (!"NONE".equals(
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
            List<ChangeClassifier.ClassifiedSentence> changes
    ) {
        StringBuilder prompt =
                new StringBuilder();


        prompt.append(
                "Classify what operation happened for each persistent game-state change.\n"
        );
        prompt.append(
                "Classify from the player's persistent state, inventory and campaign state, not from the world's point of view.\n"
        );


        prompt.append(
                "For ITEM, classify relative to the player's inventory.\n"
        );

        prompt.append(
                "ADD = the item enters the player's possession.\n"
        );

        prompt.append(
                "REMOVE = the item leaves the player's possession, including putting it down, dropping it, losing it, spending it or giving it away.\n"
        );

        prompt.append(
                "UPDATE = the player keeps the item, but the item itself is modified.\n"
        );

        prompt.append(
                "Examples:\n"
        );

        prompt.append(
                "\"Я беру фонарь с земли\" = ADD.\n"
        );

        prompt.append(
                "\"Я кладу фонарь обратно на землю\" = REMOVE.\n"
        );

        prompt.append(
                "\"Я привязываю красную нить к ключу\" = UPDATE.\n"
        );


        prompt.append(
                "ABILITY: ADD, REMOVE or UPDATE.\n"
        );


        prompt.append(
                "CHARACTER: UPDATE.\n"
        );


        prompt.append(
                "NPC: ADD for a new NPC, otherwise UPDATE.\n"
        );


        prompt.append(
                "LOCATION: MOVE when the character changes current location, otherwise UPDATE.\n"
        );


        prompt.append(
                "QUEST: START, UPDATE, COMPLETE or FAIL.\n"
        );


        prompt.append(
                "RELATION: UPDATE.\n"
        );


        prompt.append(
                "WORLD or FACT: ADD for a new fact, otherwise UPDATE.\n"
        );


        prompt.append(
                "If no persistent change actually happened, use NONE.\n"
        );


        prompt.append(
                "Output exactly one line per candidate.\n"
        );


        prompt.append(
                "Format: ID=OPERATION\n"
        );


        prompt.append(
                "No explanation. No JSON. No markdown.\n"
        );


        prompt.append(
                "\nCHANGES:\n"
        );


        for (ChangeClassifier.ClassifiedSentence item
                : changes) {

            prompt.append("[")
                    .append(item.getId())
                    .append("] TYPE=")
                    .append(item.getType())
                    .append(" | ")
                    .append(item.getText())
                    .append("\n");
        }


        return wrapGemmaPrompt(
                prompt.toString()
        );
    }


    private List<OperationResult> parseResult(
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


        List<OperationResult> result =
                new ArrayList<>();


        Matcher matcher =
                RESULT_PATTERN.matcher(
                        cleaned
                );


        while (matcher.find()) {

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


            String operation =
                    matcher.group(2)
                            .toUpperCase(
                                    Locale.ROOT
                            );


            result.add(
                    new OperationResult(
                            sourceItem.getId(),
                            sourceItem.getType(),
                            sourceItem.getText(),
                            operation
                    )
            );
        }


        /*
         * Для одного кандидата Gemma может снова
         * вернуть просто ADD / REMOVE / UPDATE.
         */
        if (result.isEmpty()
                && source.size() == 1) {

            String upper =
                    cleaned.toUpperCase(
                            Locale.ROOT
                    );


            String[] operations = {
                    "ADD",
                    "REMOVE",
                    "UPDATE",
                    "MOVE",
                    "START",
                    "COMPLETE",
                    "FAIL",
                    "NONE"
            };


            for (String operation : operations) {

                if (upper.contains(
                        operation
                )) {

                    ChangeClassifier.ClassifiedSentence item =
                            source.get(0);


                    result.add(
                            new OperationResult(
                                    item.getId(),
                                    item.getType(),
                                    item.getText(),
                                    operation
                            )
                    );

                    break;
                }
            }
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
                List<OperationResult> result
        );

        void onSkipped(
                String reason
        );

        void onError(
                Throwable throwable
        );
    }


    public static class OperationResult {

        private final int id;

        private final String type;

        private final String text;

        private final String operation;


        public OperationResult(
                int id,
                String type,
                String text,
                String operation
        ) {
            this.id =
                    id;

            this.type =
                    type;

            this.text =
                    text;

            this.operation =
                    operation;
        }


        public int getId() {
            return id;
        }


        public String getType() {
            return type;
        }


        public String getText() {
            return text;
        }


        public String getOperation() {
            return operation;
        }
    }
}