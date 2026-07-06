package com.example.mydnd.game.world;

import android.util.Log;

import com.example.mydnd.db.AppDatabase;
import com.example.mydnd.db.DbExecutor;
import com.example.mydnd.db.entity.CampaignEntity;
import com.example.mydnd.db.entity.WorldEntity;
import com.example.mydnd.db.entity.WorldEventEntity;
import com.example.mydnd.db.entity.WorldTimelineEntity;
import com.example.mydnd.llm.GenerationProfile;
import com.example.mydnd.llm.LlmCallback;
import com.example.mydnd.llm.LlmModelManager;
import com.example.mydnd.llm.ModelRole;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class WorldMaintenanceService {

    private static final String TAG = "MyDND_WORLD_MAINT";

    private static final int WORLD_SUMMARY_TURN_INTERVAL = 30;
    private static final int MAX_WORLD_EVENTS_FOR_SUMMARY = 24;
    private static final int RECENT_WORLD_EVENTS_FOR_RANDOM = 4;

    private static final int RANDOM_EVENT_MIN_DELAY = 12;
    private static final int RANDOM_EVENT_MAX_DELAY = 25;

    private static final int MAX_SUMMARY_CHARS = 700;
    private static final int MAX_RANDOM_EVENT_CHARS = 220;

    private final AppDatabase database;
    private final LlmModelManager modelManager;
    private final WorldMemoryRepository worldMemoryRepository;
    private final SecureRandom random = new SecureRandom();
    private final AtomicBoolean working = new AtomicBoolean(false);

    public WorldMaintenanceService(
            AppDatabase database,
            LlmModelManager modelManager,
            WorldMemoryRepository worldMemoryRepository
    ) {
        this.database = database;
        this.modelManager = modelManager;
        this.worldMemoryRepository = worldMemoryRepository;
    }

    public void onMasterTurnCompleted(
            long campaignId,
            Listener listener
    ) {
        run(
                campaignId,
                true,
                listener
        );
    }

    public void runIfDue(
            long campaignId,
            Listener listener
    ) {
        run(
                campaignId,
                false,
                listener
        );
    }

    private void run(
            long campaignId,
            boolean incrementWorldTurn,
            Listener listener
    ) {
        if (campaignId <= 0L) {
            listener.onIdle();
            return;
        }

        if (!working.compareAndSet(false, true)) {
            listener.onIdle();
            return;
        }

        DbExecutor.execute(() -> {
            try {
                CampaignEntity campaign =
                        database.campaignDao().getById(campaignId);

                if (campaign == null || campaign.worldTimelineId <= 0L) {
                    finishIdle(listener);
                    return;
                }

                WorldTimelineEntity timeline =
                        database.worldTimelineDao().getById(
                                campaign.worldTimelineId
                        );

                if (timeline == null) {
                    finishIdle(listener);
                    return;
                }

                if (incrementWorldTurn) {
                    database.worldTimelineDao().incrementWorldTurn(
                            timeline.id,
                            System.currentTimeMillis()
                    );

                    timeline = database.worldTimelineDao().getById(
                            timeline.id
                    );
                }

                int masterTurn = timeline.worldTurnCount;

                List<WorldEventEntity> unsummarized =
                        database.worldEventDao().getAfterIdAsc(
                                timeline.id,
                                timeline.lastWorldSummaryEventId,
                                MAX_WORLD_EVENTS_FOR_SUMMARY
                        );

                int maxImportance =
                        database.worldEventDao().getMaxImportanceAfterId(
                                timeline.id,
                                timeline.lastWorldSummaryEventId
                        );

                boolean intervalDue =
                        masterTurn - timeline.lastWorldSummaryTurn
                                >= WORLD_SUMMARY_TURN_INTERVAL;

                boolean urgentDue = maxImportance >= 3;

                Log.d(
                        TAG,
                        "check turn=" + masterTurn
                                + " summaryTurn=" + timeline.lastWorldSummaryTurn
                                + " unsummarized=" + unsummarized.size()
                                + " maxImportance=" + maxImportance
                                + " nextRandom=" + timeline.nextRandomEventTurn
                );

                if (intervalDue || urgentDue) {
                    if (unsummarized.isEmpty()) {
                        database.worldTimelineDao()
                                .markWorldSummaryCheckpoint(
                                        timeline.id,
                                        masterTurn,
                                        System.currentTimeMillis()
                                );

                        timeline.lastWorldSummaryTurn = masterTurn;
                        runRandomEventIfDue(
                                campaign,
                                timeline,
                                masterTurn,
                                listener
                        );
                        return;
                    }

                    if (modelManager.isBusy()) {
                        finishIdle(listener);
                        return;
                    }

                    startWorldSummary(
                            campaign,
                            timeline,
                            masterTurn,
                            unsummarized,
                            listener
                    );
                    return;
                }

                runRandomEventIfDue(
                        campaign,
                        timeline,
                        masterTurn,
                        listener
                );

            } catch (Throwable throwable) {
                finishError(listener, throwable);
            }
        });
    }

    public boolean isWorking() {
        return working.get();
    }

    private void runRandomEventIfDue(
            CampaignEntity campaign,
            WorldTimelineEntity timeline,
            int masterTurn,
            Listener listener
    ) {
        int nextTurn = timeline.nextRandomEventTurn;

        if (nextTurn <= 0) {
            int scheduled = masterTurn + randomDelay();

            database.worldTimelineDao().updateNextRandomEventTurn(
                    timeline.id,
                    scheduled,
                    System.currentTimeMillis()
            );

            Log.d(TAG, "random event scheduled for turn=" + scheduled);
            finishIdle(listener);
            return;
        }

        if (masterTurn < nextTurn) {
            finishIdle(listener);
            return;
        }

        if (modelManager.isBusy()) {
            finishIdle(listener);
            return;
        }

        WorldEntity world = campaign.worldId > 0L
                ? database.worldDao().getWorld(campaign.worldId)
                : null;

        List<WorldEventEntity> recentEvents =
                database.worldEventDao().getRecent(
                        timeline.id,
                        RECENT_WORLD_EVENTS_FOR_RANDOM
                );

        int importance = randomImportance();
        String tone = randomTone();

        String prompt = buildRandomEventPrompt(
                world,
                timeline,
                recentEvents,
                tone,
                importance
        );

        listener.onStarted("RANDOM_EVENT");

        modelManager.generate(
                ModelRole.MASTER,
                prompt,
                GenerationProfile.randomWorldEvent(),
                new LlmCallback() {
                    @Override
                    public void onToken(String token) {
                        // Служебная генерация игроку не стримится.
                    }

                    @Override
                    public void onComplete(String fullText) {
                        String eventText = cleanText(
                                fullText,
                                MAX_RANDOM_EVENT_CHARS
                        );

                        DbExecutor.execute(() -> {
                            try {
                                boolean stored = !eventText.isEmpty()
                                        && worldMemoryRepository
                                        .rememberRandomForCampaign(
                                                campaign.id,
                                                eventText,
                                                importance,
                                                tone
                                        );

                                int next = masterTurn + randomDelay();

                                database.worldTimelineDao()
                                        .updateNextRandomEventTurn(
                                                timeline.id,
                                                next,
                                                System.currentTimeMillis()
                                        );

                                Log.d(
                                        TAG,
                                        "random event stored=" + stored
                                                + " importance=" + importance
                                                + " tone=" + tone
                                                + " next=" + next
                                                + " text=" + eventText
                                );

                                if (stored && importance >= 3) {
                                    // Сначала показываем игроку само редкое событие.
                                    // Кнопка отправки не разблокируется, пока working=true.
                                    listener.onRandomEventGenerated(
                                            eventText,
                                            importance,
                                            tone
                                    );

                                    WorldTimelineEntity refreshed =
                                            database.worldTimelineDao()
                                                    .getById(timeline.id);

                                    List<WorldEventEntity> unsummarized =
                                            database.worldEventDao()
                                                    .getAfterIdAsc(
                                                            timeline.id,
                                                            refreshed.lastWorldSummaryEventId,
                                                            MAX_WORLD_EVENTS_FOR_SUMMARY
                                                    );

                                    if (!unsummarized.isEmpty()
                                            && !modelManager.isBusy()) {
                                        startWorldSummary(
                                                campaign,
                                                refreshed,
                                                masterTurn,
                                                unsummarized,
                                                listener
                                        );
                                        return;
                                    }
                                }

                                working.set(false);

                                if (stored) {
                                    listener.onRandomEventGenerated(
                                            eventText,
                                            importance,
                                            tone
                                    );
                                } else {
                                    listener.onIdle();
                                }

                            } catch (Throwable throwable) {
                                finishError(listener, throwable);
                            }
                        });
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        finishError(listener, throwable);
                    }
                }
        );
    }

    private void startWorldSummary(
            CampaignEntity campaign,
            WorldTimelineEntity timeline,
            int masterTurn,
            List<WorldEventEntity> events,
            Listener listener
    ) {
        if (events == null || events.isEmpty()) {
            finishIdle(listener);
            return;
        }

        String prompt = buildWorldSummaryPrompt(
                timeline,
                events
        );

        listener.onStarted("WORLD_SUMMARY");

        modelManager.generate(
                ModelRole.MASTER,
                prompt,
                GenerationProfile.worldSummary(),
                new LlmCallback() {
                    @Override
                    public void onToken(String token) {
                        // Служебная генерация игроку не стримится.
                    }

                    @Override
                    public void onComplete(String fullText) {
                        String summary = cleanText(
                                fullText,
                                MAX_SUMMARY_CHARS
                        );

                        if (summary.length() < 20) {
                            finishError(
                                    listener,
                                    new IllegalStateException(
                                            "WORLD SUMMARY слишком короткий"
                                    )
                            );
                            return;
                        }

                        long lastEventId = events.get(events.size() - 1).id;

                        DbExecutor.execute(() -> {
                            try {
                                database.worldTimelineDao().updateWorldSummary(
                                        timeline.id,
                                        summary,
                                        lastEventId,
                                        masterTurn,
                                        System.currentTimeMillis()
                                );

                                working.set(false);

                                Log.d(
                                        TAG,
                                        "world summary updated, turn="
                                                + masterTurn
                                                + " lastEventId="
                                                + lastEventId
                                );

                                listener.onWorldSummaryUpdated(summary);

                            } catch (Throwable throwable) {
                                finishError(listener, throwable);
                            }
                        });
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        finishError(listener, throwable);
                    }
                }
        );
    }

    private String buildWorldSummaryPrompt(
            WorldTimelineEntity timeline,
            List<WorldEventEntity> events
    ) {
        StringBuilder user = new StringBuilder();

        user.append("ПРЕДЫДУЩАЯ СВОДКА МИРА:\n");
        user.append(limit(timeline.stateSummary, 700));
        user.append("\n\nНОВЫЕ ВАЖНЫЕ СОБЫТИЯ:\n");

        for (WorldEventEntity event : events) {
            user.append("- [важность ")
                    .append(event.importance)
                    .append("] ")
                    .append(limit(event.text, 220))
                    .append('\n');
        }

        user.append(
                "\nПерепиши текущую сводку мира с учётом новых событий. "
                        + "Сохрани только устойчивые факты, последствия и текущее состояние. "
                        + "Не добавляй сведения, которых нет во входных данных. "
                        + "Максимум один короткий абзац на русском без заголовка."
        );

        return wrapGemma(
                "Ты обновляешь долговременную сводку живого RPG-мира. "
                        + "Это база фактов, не художественный текст.",
                user.toString()
        );
    }

    private String buildRandomEventPrompt(
            WorldEntity world,
            WorldTimelineEntity timeline,
            List<WorldEventEntity> recentEvents,
            String tone,
            int importance
    ) {
        StringBuilder user = new StringBuilder();

        user.append("МИР: ")
                .append(world == null ? "неизвестный мир" : world.name)
                .append('\n');

        if (world != null && !world.genre.isEmpty()) {
            user.append("ЖАНР: ")
                    .append(limit(world.genre, 80))
                    .append('\n');
        }

        user.append("ТЕКУЩЕЕ СОСТОЯНИЕ: ")
                .append(limit(timeline.stateSummary, 500))
                .append('\n');

        if (recentEvents != null && !recentEvents.isEmpty()) {
            user.append("НЕДАВНИЕ СОБЫТИЯ:\n");

            for (WorldEventEntity event : recentEvents) {
                user.append("- ")
                        .append(limit(event.text, 160))
                        .append('\n');
            }
        }

        user.append("\nСОЗДАЙ НОВОЕ СОБЫТИЕ МИРА.\n");
        user.append("Тон: ").append(toneRu(tone)).append(".\n");
        user.append("Важность: ")
                .append(importance)
                .append(" — ")
                .append(importanceRu(importance))
                .append(".\n");

        user.append(
                "Событие может происходить независимо от текущего героя. "
                        + "Не повторяй известные события. "
                        + "Для важности 1 не меняй судьбу мира; "
                        + "для важности 2 дай заметное региональное последствие; "
                        + "только важность 3 может резко изменить власть, войну, город или крупную организацию. "
                        + "Ответь одной конкретной фактической фразой на русском без заголовка."
        );

        return wrapGemma(
                "Ты создаёшь редкое фоновое событие живого RPG-мира. "
                        + "Не управляй персонажем игрока и не переписывай прошлое.",
                user.toString()
        );
    }

    private String wrapGemma(String system, String user) {
        return "<|turn>system\n"
                + system
                + "<turn|>\n"
                + "<|turn>user\n"
                + user
                + "<turn|>\n"
                + "<|turn>model\n";
    }

    private String cleanText(String raw, int maxChars) {
        if (raw == null) {
            return "";
        }

        String text = raw
                .replaceAll("(?s)<think>.*?</think>", " ")
                .replaceAll("(?s)<\\|tool_call>.*?<tool_call\\|>", " ")
                .replace("```", " ")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim()
                .replaceAll("\\s+", " ");

        text = text.replaceFirst(
                "(?i)^(СОБЫТИЕ|СВОДКА|SUMMARY|EVENT)\\s*:\\s*",
                ""
        ).trim();

        if (text.length() <= maxChars) {
            return text;
        }

        return text.substring(0, maxChars).trim();
    }

    private int randomDelay() {
        return RANDOM_EVENT_MIN_DELAY
                + random.nextInt(
                RANDOM_EVENT_MAX_DELAY
                        - RANDOM_EVENT_MIN_DELAY
                        + 1
        );
    }

    private int randomImportance() {
        int roll = random.nextInt(100);

        if (roll < 70) {
            return 1;
        }

        if (roll < 95) {
            return 2;
        }

        return 3;
    }

    private String randomTone() {
        int roll = random.nextInt(3);

        if (roll == 0) {
            return WorldMemoryRepository.TONE_POSITIVE;
        }

        if (roll == 1) {
            return WorldMemoryRepository.TONE_NEGATIVE;
        }

        return WorldMemoryRepository.TONE_NEUTRAL;
    }

    private String toneRu(String tone) {
        if (WorldMemoryRepository.TONE_POSITIVE.equals(tone)) {
            return "позитивный";
        }

        if (WorldMemoryRepository.TONE_NEGATIVE.equals(tone)) {
            return "негативный";
        }

        return "нейтральный";
    }

    private String importanceRu(int importance) {
        if (importance >= 3) {
            return "очень крупное, переломное событие";
        }

        if (importance == 2) {
            return "важное заметное событие";
        }

        return "небольшое локальное событие";
    }

    private String limit(String value, int maxChars) {
        if (value == null) {
            return "";
        }

        String safe = value
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim()
                .replaceAll("\\s+", " ");

        if (safe.length() <= maxChars) {
            return safe;
        }

        return safe.substring(0, maxChars).trim();
    }

    private void finishIdle(Listener listener) {
        working.set(false);
        listener.onIdle();
    }

    private void finishError(
            Listener listener,
            Throwable throwable
    ) {
        working.set(false);
        Log.e(TAG, "maintenance failed", throwable);
        listener.onError(throwable);
    }

    public interface Listener {
        void onStarted(String task);
        void onWorldSummaryUpdated(String summary);
        void onRandomEventGenerated(
                String text,
                int importance,
                String tone
        );
        void onIdle();
        void onError(Throwable throwable);
    }
}
