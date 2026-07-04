package com.example.mydnd.memory;

import android.util.Log;

import com.example.mydnd.db.AppDatabase;
import com.example.mydnd.db.DbExecutor;
import com.example.mydnd.db.entity.GameEventEntity;
import com.example.mydnd.db.entity.SummaryEntity;
import com.example.mydnd.llm.GenerationProfile;
import com.example.mydnd.llm.LlmCallback;
import com.example.mydnd.llm.LlmModelManager;
import com.example.mydnd.llm.ModelRole;
import java.util.Locale;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class SummaryService {

    private static final String TAG = "MyDND_SUMMARY";

    // Примерно 3 полных игровых хода:
    // PLAYER + MASTER = 2 события.
    private static final int MIN_NEW_EVENTS = 6;

    // За один раз не даём маленькой модели слишком много текста.
    private static final int MAX_EVENTS_PER_SUMMARY = 8;

    private static final int MAX_EVENT_CHARS_FOR_SUMMARY = 420;

    // Чтобы сама summary тоже не росла бесконечно.
    private static final int MAX_STORED_SUMMARY_CHARS = 1000;

    private final AppDatabase database;
    private final LlmModelManager modelManager;

    private final AtomicBoolean working =
            new AtomicBoolean(false);

    public SummaryService(
            AppDatabase database,
            LlmModelManager modelManager
    ) {
        this.database = database;
        this.modelManager = modelManager;
    }

    public void updateIfNeeded(
            long campaignId,
            Listener listener
    ) {
        if (!working.compareAndSet(false, true)) {
            Log.d(TAG, "updateIfNeeded(): already working");

            listener.onSkipped(0);
            return;
        }

        DbExecutor.execute(() -> {
            try {
                SummaryEntity latest =
                        database.summaryDao()
                                .getLatestForCampaign(campaignId);

                long afterEventId =
                        latest == null
                                ? 0L
                                : latest.toEventId;

                List<GameEventEntity> newEvents =
                        database.gameEventDao()
                                .getPromptEventsAfterIdAsc(
                                        campaignId,
                                        afterEventId,
                                        MAX_EVENTS_PER_SUMMARY
                                );

                int eventCount =
                        newEvents == null
                                ? 0
                                : newEvents.size();

                Log.d(
                        TAG,
                        "check: afterEventId="
                                + afterEventId
                                + ", newEvents="
                                + eventCount
                );

                if (eventCount < MIN_NEW_EVENTS) {
                    working.set(false);

                    listener.onSkipped(eventCount);
                    return;
                }

                // Если игрок уже успел запустить новый ход,
                // не мешаем мастеру.
                if (modelManager.isBusy()) {
                    Log.d(
                            TAG,
                            "skip: model manager is busy"
                    );

                    working.set(false);
                    listener.onSkipped(eventCount);
                    return;
                }

                String prompt =
                        buildSummaryPrompt(
                                newEvents
                        );

                listener.onStarted(eventCount);

                Log.d(
                        TAG,
                        "starting SERVICE model, events="
                                + eventCount
                );

                modelManager.generate(
                        ModelRole.SERVICE,
                        prompt,
                        GenerationProfile.summary(),
                        new LlmCallback() {

                            @Override
                            public void onToken(String token) {
                                // Summary пользователю не показываем.
                            }

                            @Override
                            public void onComplete(String fullText) {
                                saveSummary(
                                        campaignId,
                                        latest,
                                        newEvents,
                                        fullText,
                                        listener
                                );
                            }

                            @Override
                            public void onError(Throwable throwable) {
                                working.set(false);

                                Log.e(
                                        TAG,
                                        "Summary generation failed",
                                        throwable
                                );

                                listener.onError(throwable);
                            }
                        }
                );

            } catch (Throwable throwable) {
                working.set(false);

                Log.e(
                        TAG,
                        "Summary check failed",
                        throwable
                );

                listener.onError(throwable);
            }
        });
    }

    private String buildSummaryPrompt(
            List<GameEventEntity> events
    ) {
        StringBuilder prompt =
                new StringBuilder();

        prompt.append(
                "You compress RPG events into factual campaign memory.\n"
        );

        prompt.append(
                "Write only facts explicitly supported by EVENTS.\n"
        );

        prompt.append(
                "Write the result in Russian.\n"
        );

        prompt.append(
                "Keep concrete events that may matter later: "
                        + "completed player actions, NPC actions, "
                        + "items gained or lost, location changes, "
                        + "quest changes, relationship changes, injuries, "
                        + "state changes and explicit discoveries.\n"
        );

        prompt.append(
                "PLAYER lines may describe attempts or intentions. "
                        + "Do not record uncertain outcomes as successful "
                        + "unless the DM confirms them.\n"
        );

        prompt.append(
                "DM text may contain artistic narration. "
                        + "Ignore metaphors, comparisons, poetic imagery, "
                        + "mood, smells, decorative weather, symbolism, "
                        + "guesses and foreshadowing.\n"
        );

        prompt.append(
                "A poetic description is not a literal property of the world.\n"
        );

        prompt.append(
                "Do not invent information.\n"
        );

        prompt.append(
                "Do not add headings, bullet points, labels, ratings, "
                        + "time periods, metadata, markdown or code fences.\n"
        );

        prompt.append(
                "Return only one short factual paragraph in Russian.\n"
        );

        prompt.append("\nEVENTS:\n");

        for (GameEventEntity event : events) {

            if (event.text == null
                    || event.text.trim().isEmpty()) {
                continue;
            }

            if ("PLAYER".equals(event.speaker)) {

                prompt.append("\n[PLAYER] ");

            } else if ("MASTER".equals(event.speaker)) {

                prompt.append("\n[DM] ");

            } else {
                continue;
            }

            prompt.append(
                    shortenEventForSummary(
                            event.text
                    )
            );
        }

        return wrapGemmaPrompt(
                prompt.toString()
        );
    }

    private void saveSummary(
            long campaignId,
            SummaryEntity latest,
            List<GameEventEntity> events,
            String fullText,
            Listener listener
    ) {
        String deltaSummary =
                cleanSummary(fullText);

        if (deltaSummary.isEmpty()) {
            working.set(false);

            listener.onError(
                    new IllegalStateException(
                            "Служебная модель вернула пустой summary"
                    )
            );

            return;
        }
        if (!isAcceptableSummary(deltaSummary)) {
            working.set(false);

            Log.w(
                    TAG,
                    "Summary rejected and NOT saved: "
                            + deltaSummary
            );

            listener.onError(
                    new IllegalStateException(
                            "Служебная модель вернула некачественную summary"
                    )
            );

            return;
        }

        DbExecutor.execute(() -> {
            try {
                GameEventEntity firstEvent =
                        events.get(0);

                GameEventEntity lastEvent =
                        events.get(events.size() - 1);

                SummaryEntity summary =
                        new SummaryEntity();

                summary.campaignId = campaignId;

                summary.text =
                        mergeSummary(
                                latest,
                                deltaSummary
                        );

                summary.fromEventId =
                        latest == null
                                ? firstEvent.id
                                : latest.fromEventId;

                summary.toEventId =
                        lastEvent.id;

                summary.createdAt =
                        System.currentTimeMillis();

                long id =
                        database.summaryDao()
                                .insert(summary);

                summary.id = id;

                working.set(false);

                Log.d(
                        TAG,
                        "saved summary id="
                                + id
                                + ", covers events "
                                + summary.fromEventId
                                + ".."
                                + summary.toEventId
                );

                Log.d(
                        TAG,
                        "summary: "
                                + summary.text
                );
                Log.d(
                        TAG,
                        "delta summary: "
                                + deltaSummary
                );

                Log.d(
                        TAG,
                        "merged summary: "
                                + summary.text
                );

                listener.onCompleted(summary);

            } catch (Throwable throwable) {
                working.set(false);

                Log.e(
                        TAG,
                        "Failed to save summary",
                        throwable
                );

                listener.onError(throwable);
            }
        });
    }

    private String cleanSummary(
            String text
    ) {
        if (text == null) {
            return "";
        }

        String result =
                text
                        .replaceAll(
                                "(?i)```(?:json)?",
                                ""
                        )
                        .trim();

        StringBuilder cleaned =
                new StringBuilder();

        String[] lines =
                result.split("\\R");

        for (String rawLine : lines) {

            String line =
                    rawLine.trim();

            if (line.isEmpty()) {
                continue;
            }

            // Убираем маркеры списков.
            line =
                    line.replaceFirst(
                            "^[*•\\-]+\\s*",
                            ""
                    );

            // Убираем заголовок, если модель всё-таки его написала.
            line =
                    line.replaceFirst(
                            "(?i)^\\s*(SUMMARY|СВОДКА|ИТОГ|ОТВЕТ)\\s*:\\s*",
                            ""
                    );

            line =
                    line.trim();

            if (line.isEmpty()
                    || isServiceGarbageLine(line)) {

                continue;
            }

            if (cleaned.length() > 0) {
                cleaned.append(' ');
            }

            cleaned.append(line);
        }

        return cleaned
                .toString()
                .replaceAll(
                        "\\s+",
                        " "
                )
                .trim();
    }
    private boolean isServiceGarbageLine(
            String line
    ) {
        String lower =
                line
                        .toLowerCase(
                                Locale.ROOT
                        )
                        .trim();

        return lower.startsWith("оценка:")
                || lower.startsWith("временной период:")
                || lower.startsWith("период:")
                || lower.startsWith("место:")
                || lower.startsWith("действия:")
                || lower.startsWith("time period:")
                || lower.startsWith("location:")
                || lower.startsWith("actions:")
                || lower.equals("события:")
                || lower.equals("events:")
                || lower.equals("summary:")
                || lower.equals("task:")
                || lower.equals("system:")
                || lower.contains(
                "коротких предложений"
        )
                || lower.contains(
                "кратко что нового произошло"
        );
    }

    public boolean isWorking() {
        return working.get();
    }

    public interface Listener {

        void onStarted(int eventCount);

        void onSkipped(int eventCount);

        void onCompleted(SummaryEntity summary);

        void onError(Throwable throwable);
    }
    private boolean isAcceptableSummary(String text) {
        if (text == null) {
            return false;
        }

        String trimmed = text.trim();

        if (trimmed.length() < 40) {
            Log.w(
                    TAG,
                    "Rejected summary: too short"
            );

            return false;
        }

        if (trimmed.length() > 1200) {
            Log.w(
                    TAG,
                    "Rejected summary: too long"
            );

            return false;
        }

        String lower =
                trimmed.toLowerCase(Locale.ROOT);

        String[] forbiddenFragments = {
                "сжимаешь память",
                "память кампании настольной rpg",
                "коротких предложений",
                "используй только факты",
                "ничего не придумывай",
                "начни сразу",
                "инструкция",
                "system:",
                "task:",
                "summary:",
                "new_events",
                "new events",
                "after the dm",
                "краткий пересказ:",
                "оценка:",
                "временной период:",
                "time period:",
                "```"
        };

        for (String forbidden : forbiddenFragments) {
            if (lower.contains(forbidden)) {
                Log.w(
                        TAG,
                        "Rejected summary: contains forbidden fragment: "
                                + forbidden
                );

                return false;
            }
        }

        return true;
    }

    private String shortenEventForSummary(
            String text
    ) {
        if (text == null) {
            return "";
        }

        String trimmed =
                text
                        .replaceAll(
                                "\\s+",
                                " "
                        )
                        .trim();

        if (trimmed.length()
                <= MAX_EVENT_CHARS_FOR_SUMMARY) {

            return trimmed;
        }

        String[] sentences =
                trimmed.split(
                        "(?<=[.!?])\\s+"
                );

        StringBuilder result =
                new StringBuilder();

        for (String sentence : sentences) {

            String cleanSentence =
                    sentence.trim();

            if (cleanSentence.isEmpty()) {
                continue;
            }

            int extraLength =
                    result.length() == 0
                            ? cleanSentence.length()
                            : cleanSentence.length() + 1;

            if (result.length()
                    + extraLength
                    > MAX_EVENT_CHARS_FOR_SUMMARY) {

                break;
            }

            if (result.length() > 0) {
                result.append(' ');
            }

            result.append(
                    cleanSentence
            );
        }

        if (result.length() > 0) {

            return result
                    .toString()
                    .trim();
        }

        // На случай одного гигантского предложения.
        String cut =
                trimmed.substring(
                        0,
                        MAX_EVENT_CHARS_FOR_SUMMARY
                );

        int lastSpace =
                cut.lastIndexOf(' ');

        if (lastSpace > 100) {

            cut =
                    cut.substring(
                            0,
                            lastSpace
                    );
        }

        return cut.trim()
                + "…";
    }

    private String mergeSummary(
            SummaryEntity latest,
            String deltaSummary
    ) {
        String previousSummary = "";

        if (latest != null
                && latest.text != null) {

            previousSummary =
                    latest.text.trim();
        }

        String delta =
                deltaSummary == null
                        ? ""
                        : deltaSummary.trim();

        String merged;

        if (previousSummary.isEmpty()) {

            merged = delta;

        } else if (delta.isEmpty()) {

            merged = previousSummary;

        } else {

            merged =
                    previousSummary
                            + "\n"
                            + delta;
        }

        return limitStoredSummary(
                merged
        );
    }
    private String limitStoredSummary(
            String text
    ) {
        if (text == null) {
            return "";
        }

        String trimmed =
                text.trim();

        if (trimmed.length()
                <= MAX_STORED_SUMMARY_CHARS) {

            return trimmed;
        }

        int startIndex =
                trimmed.length()
                        - MAX_STORED_SUMMARY_CHARS;

        // Стараемся не начинать новую память
        // с середины предложения.
        int nextSentence =
                trimmed.indexOf(
                        ". ",
                        startIndex
                );

        if (nextSentence >= 0
                && nextSentence
                < startIndex + 200) {

            startIndex =
                    nextSentence + 2;
        }

        return trimmed
                .substring(startIndex)
                .trim();
    }

    private String wrapGemmaPrompt(
            String userPrompt
    ) {
        return "<start_of_turn>user\n"
                + userPrompt.trim()
                + "\n<end_of_turn>\n"
                + "<start_of_turn>model\n";
    }
}