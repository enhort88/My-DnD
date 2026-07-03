package com.example.mydnd;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import com.example.mydnd.game.GameEvent;
import com.example.mydnd.llm.GenerationProfile;
import com.example.mydnd.llm.LlmCallback;
import com.example.mydnd.llm.LlmEngine;
import com.example.mydnd.llm.LocalLlmEngine;
import android.widget.CheckBox;

import com.example.mydnd.llm.ResponseCleaner;
import com.example.mydnd.llm.ThinkBlockFilter;
import com.example.mydnd.prompt.PromptBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import android.os.Handler;
import android.os.Looper;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import com.example.mydnd.db.AppDatabase;
import com.example.mydnd.db.DbExecutor;
import com.example.mydnd.db.entity.CampaignEntity;
import com.example.mydnd.db.entity.GameEventEntity;



public class MainActivity extends Activity {

    private AppDatabase database;
    private long currentCampaignId = 0L;
    private Button loadGameButton;

    private final ResponseCleaner responseCleaner = new ResponseCleaner();

    private boolean generationCancelledByUser = false;
    private TextView chatTextView;
    private EditText inputEditText;
    private ScrollView chatScrollView;
    private Button sendButton;

    private LlmEngine llmEngine;

    private final StringBuilder chatHistory = new StringBuilder();

    private boolean masterStreamingStarted = false;
    private boolean generationInProgress = false;

    private CheckBox useThinkingCheckBox;
    private CheckBox showThinkingCheckBox;

    private final ThinkBlockFilter thinkBlockFilter = new ThinkBlockFilter();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder pendingTokenBuffer = new StringBuilder();

    private boolean streamFlushScheduled = false;

    private static final long STREAM_FLUSH_DELAY_MS = 80;


    private static final String SYSTEM_PROMPT =
            "Ты — мастер настольной RPG в духе DnD. " +
                    "Веди сцену, описывай мир, играй NPC и реагируй на действия игрока. " +
                    "Не решай за персонажа игрока. " +
                    "Если нужна проверка, попроси бросок кубика. " +
                    "Не бросай кубики сам. " +
                    "Отвечай на русском языке. " +
                    "Пиши атмосферно, но кратко.";

    private View welcomeLayout;
    private View gameLayout;

    private Button startGameButton;
    private Button settingsButton;
    private Button exitButton;

    private final SpannableStringBuilder chatDisplay = new SpannableStringBuilder();
    private final StringBuilder promptHistory = new StringBuilder();

    private static final int COLOR_MASTER = Color.rgb(232, 224, 208);
    private static final int COLOR_PLAYER = Color.rgb(150, 190, 255);
    private static final int COLOR_SYSTEM = Color.rgb(120, 120, 120);

    private boolean thinkingIndicatorVisible = false;
    private int thinkingIndicatorStart = -1;
    private int thinkingFrameIndex = 0;

    private final String[] thinkingFrames = {
            "⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"
    };

    private final Runnable thinkingIndicatorRunnable = new Runnable() {
        @Override
        public void run() {
            if (!thinkingIndicatorVisible) {
                return;
            }

            updateThinkingIndicatorFrame();
            uiHandler.postDelayed(this, 120);
        }
    };

    private final List<GameEvent> gameEvents = new ArrayList<>();
    private final PromptBuilder promptBuilder = new PromptBuilder();

    private GenerationProfile generationProfile = GenerationProfile.normal();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        database = AppDatabase.getInstance(this);
        initDefaultCampaign();

        welcomeLayout = findViewById(R.id.welcomeLayout);
        gameLayout = findViewById(R.id.gameLayout);

        startGameButton = findViewById(R.id.startGameButton);
        loadGameButton = findViewById(R.id.loadGameButton);
        settingsButton = findViewById(R.id.settingsButton);
        exitButton = findViewById(R.id.exitButton);

        chatTextView = findViewById(R.id.chatTextView);
        inputEditText = findViewById(R.id.inputEditText);
        chatScrollView = findViewById(R.id.chatScrollView);
        sendButton = findViewById(R.id.sendButton);
        sendButton.setEnabled(true);
        sendButton.setText("Отправить");
        useThinkingCheckBox = findViewById(R.id.useThinkingCheckBox);
        showThinkingCheckBox = findViewById(R.id.showThinkingCheckBox);

        File modelFile = new File(
                getExternalFilesDir("models"),
                "Qwen3-4B-Q4_K_M.gguf"
        );

//        chatHistory.append("Система: путь к модели:\n");
//        chatHistory.append(modelFile.getAbsolutePath());
//        chatHistory.append("\n\n");

        llmEngine = new LocalLlmEngine(modelFile.getAbsolutePath());

        showWelcomeMenu();

        startGameButton.setOnClickListener(v -> {
            showGameScreen();
            createNewCampaignAndStart();
        });

        loadGameButton.setOnClickListener(v -> {
            showGameScreen();
            loadCampaignOrStartFirstScene();
        });

        exitButton.setOnClickListener(v -> finish());


        sendButton.setOnClickListener(v -> {
            if (generationInProgress) {
                generationCancelledByUser = true;
                removeThinkingIndicator();
                flushStreamingTokens();
                llmEngine.cancel();

                sendButton.setEnabled(false);
                sendButton.setText("Останавливаю...");

                appendSystemMessage("Останавливаю генерацию...");
                return;
            }

            sendPlayerMessage();
        });
    }

    private void startFirstScene() {

        chatDisplay.clear();
        gameEvents.clear();
        promptHistory.setLength(0);

        appendMasterMessage(
                "Ты стоишь у входа в старую придорожную таверну. " +
                        "За мутными окнами дрожит жёлтый свет. " +
                        "Дождь стучит по крыше, а где-то рядом воет пёс. " +
                        "Дверь приоткрыта, но внутри подозрительно тихо."
        );

        sendButton.setEnabled(true);
        sendButton.setText("Отправить");
    }

    private void sendPlayerMessage() {
        String playerText = inputEditText.getText().toString().trim();

        if (playerText.isEmpty()) {
            return;
        }

        generationCancelledByUser = false;
        generationInProgress = true;
        masterStreamingStarted = false;

        inputEditText.setText("");
        hideKeyboard();

        appendPlayerMessage(playerText);

        sendButton.setEnabled(true);
        sendButton.setText("Стоп");

        String prompt = buildPrompt(playerText);

        showThinkingIndicator();

        llmEngine.generate(prompt, generationProfile, new LlmCallback() {
            @Override
            public void onToken(String token) {
                runOnUiThread(() -> {
                    if (token == null || token.isEmpty()) {
                        return;
                    }

                    if (token.startsWith("[Система]")) {
                        flushStreamingTokens();
                        appendSystemMessage(token.replace("[Система]", "").trim());
                        return;
                    }

                    if (!masterStreamingStarted) {
                        removeThinkingIndicator();
                        masterStreamingStarted = true;
                    }

                    appendStreamingToken(token);
                });
            }

            @Override
            public void onComplete(String fullText) {
                runOnUiThread(() -> {
                    removeThinkingIndicator();
                    flushStreamingTokens();

                    if (generationCancelledByUser) {
                        removeLastPlayerEvent();
                        appendColoredText("\n\n", COLOR_MASTER);
                        //appendSystemMessage("Генерация остановлена.");

                        generationInProgress = false;
                        generationCancelledByUser = false;
                        masterStreamingStarted = false;

                        sendButton.setEnabled(true);
                        sendButton.setText("Отправить");

                        updateChat();
                        return;
                    }

                    String visibleText = fullText;

                    if (!showThinkingCheckBox.isChecked()) {
                        visibleText = thinkBlockFilter.removeThinkBlocks(fullText);
                    }
                    visibleText = responseCleaner.clean(visibleText);

                    if (masterStreamingStarted) {
                        appendColoredText("\n\n", COLOR_MASTER);
                        GameEvent masterEvent = GameEvent.master(visibleText);
                        gameEvents.add(masterEvent);
                        saveEventToDb(masterEvent);
                    } else {
                        appendMasterMessage(visibleText);
                    }

                    promptHistory.append("Мастер: ");
                    promptHistory.append(visibleText);
                    promptHistory.append("\n\n");

                    generationInProgress = false;
                    masterStreamingStarted = false;

                    sendButton.setEnabled(true);
                    sendButton.setText("Отправить");

                    updateChat();
                });
            }

            @Override
            public void onError(Throwable throwable) {
                runOnUiThread(() -> {
                    removeThinkingIndicator();
                    flushStreamingTokens();

                    generationInProgress = false;
                    generationCancelledByUser = false;
                    masterStreamingStarted = false;

                    appendSystemMessage("Ошибка LLM: " + throwable.getMessage());

                    sendButton.setEnabled(true);
                    sendButton.setText("Отправить");

                    updateChat();
                });
            }
        });
    }

    private String buildPrompt(String playerText) {
        return promptBuilder.buildPrompt(
                playerText,
                gameEvents,
                useThinkingCheckBox.isChecked(),
                generationProfile
        );
    }

    private void updateChat() {
        chatTextView.setText(chatDisplay);

        chatScrollView.post(() ->
                chatScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        );
    }

    private void hideKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        if (imm != null) {
            imm.hideSoftInputFromWindow(inputEditText.getWindowToken(), 0);
        }
    }
    private String getRecentGameHistoryForPrompt(int maxChars) {
        String history = promptHistory.toString();

        if (history.length() <= maxChars) {
            return history;
        }

        return history.substring(history.length() - maxChars);
    }

    private void appendStreamingToken(String token) {
        synchronized (pendingTokenBuffer) {
            pendingTokenBuffer.append(token);
        }

        scheduleStreamFlush();
    }

    private void scheduleStreamFlush() {
        if (streamFlushScheduled) {
            return;
        }

        streamFlushScheduled = true;

        uiHandler.postDelayed(() -> {
            streamFlushScheduled = false;
            flushStreamingTokens();
        }, STREAM_FLUSH_DELAY_MS);
    }

    private void flushStreamingTokens() {
        String chunk;

        synchronized (pendingTokenBuffer) {
            if (pendingTokenBuffer.length() == 0) {
                return;
            }

            chunk = pendingTokenBuffer.toString();
            pendingTokenBuffer.setLength(0);
        }

        int start = chatDisplay.length();
        chatDisplay.append(chunk);
        int end = chatDisplay.length();

        chatDisplay.setSpan(
                new ForegroundColorSpan(COLOR_MASTER),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );

        updateChat();
    }

    private void showWelcomeMenu() {
        welcomeLayout.setVisibility(View.VISIBLE);
        gameLayout.setVisibility(View.GONE);
    }

    private void showGameScreen() {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;

        gameLayout.setAlpha(0f);
        gameLayout.setTranslationX(screenWidth);
        gameLayout.setVisibility(View.VISIBLE);

        welcomeLayout.animate()
                .alpha(0f)
                .translationX(-screenWidth * 0.25f)
                .setDuration(260)
                .withEndAction(() -> welcomeLayout.setVisibility(View.GONE))
                .start();

        gameLayout.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(300)
                .start();
    }

    @Override
    public void onBackPressed() {
        if (gameLayout.getVisibility() == View.VISIBLE) {
            showWelcomeScreen();
        } else {
            super.onBackPressed();
        }
    }

    private void showWelcomeScreen() {
        welcomeLayout.setAlpha(0f);
        welcomeLayout.setTranslationX(-welcomeLayout.getWidth() * 0.25f);
        welcomeLayout.setVisibility(View.VISIBLE);

        gameLayout.animate()
                .alpha(0f)
                .translationX(gameLayout.getWidth())
                .setDuration(240)
                .withEndAction(() -> gameLayout.setVisibility(View.GONE))
                .start();

        welcomeLayout.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(260)
                .start();
    }


    private void appendColoredText(String text, int color) {
        int start = chatDisplay.length();
        chatDisplay.append(text);
        int end = chatDisplay.length();

        chatDisplay.setSpan(
                new ForegroundColorSpan(color),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );

        updateChat();
    }

    private void appendMasterMessage(String text) {
        appendColoredText(text + "\n\n", COLOR_MASTER);

        GameEvent event = GameEvent.master(text);
        gameEvents.add(event);
        saveEventToDb(event);
    }

    private void appendPlayerMessage(String text) {
        appendColoredText("› " + text + "\n\n", COLOR_PLAYER);

        GameEvent event = GameEvent.player(text);
        gameEvents.add(event);
        saveEventToDb(event);
    }

    private void appendSystemMessage(String text) {
//        appendColoredText("Система: " + text + "\n\n", COLOR_SYSTEM);
        gameEvents.add(GameEvent.system(text));
    }
    private void appendMasterStreamingToken(String token) {
        int start = chatDisplay.length();
        chatDisplay.append(token);
        int end = chatDisplay.length();

        chatDisplay.setSpan(
                new ForegroundColorSpan(COLOR_MASTER),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );

        updateChat();
    }

    private void showThinkingIndicator() {
        removeThinkingIndicator();

        thinkingIndicatorVisible = true;
        thinkingFrameIndex = 0;

        thinkingIndicatorStart = chatDisplay.length();

        appendThinkingIndicatorText("Мастер думает " + thinkingFrames[thinkingFrameIndex]);

        uiHandler.postDelayed(thinkingIndicatorRunnable, 120);
    }

    private void updateThinkingIndicatorFrame() {
        if (!thinkingIndicatorVisible || thinkingIndicatorStart < 0) {
            return;
        }

        int end = chatDisplay.length();

        if (thinkingIndicatorStart > end) {
            removeThinkingIndicator();
            return;
        }

        chatDisplay.delete(thinkingIndicatorStart, end);

        thinkingFrameIndex = (thinkingFrameIndex + 1) % thinkingFrames.length;

        appendThinkingIndicatorText("Мастер думает " + thinkingFrames[thinkingFrameIndex]);
    }

    private void removeThinkingIndicator() {
        if (!thinkingIndicatorVisible || thinkingIndicatorStart < 0) {
            return;
        }

        int end = chatDisplay.length();

        if (thinkingIndicatorStart <= end) {
            chatDisplay.delete(thinkingIndicatorStart, end);
        }

        thinkingIndicatorVisible = false;
        thinkingIndicatorStart = -1;

        uiHandler.removeCallbacks(thinkingIndicatorRunnable);

        updateChat();
    }

    private void appendThinkingIndicatorText(String text) {
        int start = chatDisplay.length();

        chatDisplay.append(text);

        int end = chatDisplay.length();

        chatDisplay.setSpan(
                new ForegroundColorSpan(COLOR_SYSTEM),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );

        updateChat();
    }

    private void removeLastPlayerEvent() {
        for (int i = gameEvents.size() - 1; i >= 0; i--) {
            GameEvent event = gameEvents.get(i);

            if (event.getSpeaker() == GameEvent.Speaker.PLAYER) {
                gameEvents.remove(i);
                return;
            }
        }
    }
    private void initDefaultCampaign() {
        DbExecutor.execute(() -> {
            CampaignEntity lastCampaign = database.campaignDao().getLastCampaign();

            if (lastCampaign != null) {
                currentCampaignId = lastCampaign.id;
                return;
            }

            CampaignEntity campaign = new CampaignEntity();
            campaign.title = "Первая кампания";
            campaign.createdAt = System.currentTimeMillis();
            campaign.updatedAt = campaign.createdAt;

            currentCampaignId = database.campaignDao().insert(campaign);
        });
    }
    private void saveEventToDb(GameEvent event) {
        if (currentCampaignId == 0L) {
            return;
        }

        DbExecutor.execute(() -> {
            GameEventEntity entity = new GameEventEntity();
            entity.campaignId = currentCampaignId;
            entity.speaker = event.getSpeaker().name();
            entity.text = event.getText();
            entity.includeInPrompt = event.isIncludeInPrompt();
            entity.createdAt = event.getCreatedAtMillis();

            database.gameEventDao().insert(entity);
        });
    }
    private void loadCampaignOrStartFirstScene() {
        if (currentCampaignId == 0L) {
            uiHandler.postDelayed(this::loadCampaignOrStartFirstScene, 200);
            return;
        }

        DbExecutor.execute(() -> {
            List<GameEventEntity> savedEvents =
                    database.gameEventDao().getEventsForCampaign(currentCampaignId);

            runOnUiThread(() -> {
                chatDisplay.clear();
                gameEvents.clear();

                if (savedEvents == null || savedEvents.isEmpty()) {
                    startFirstScene();
                    return;
                }

                for (GameEventEntity entity : savedEvents) {
                    GameEvent event = toGameEvent(entity);
                    gameEvents.add(event);
                    renderLoadedEvent(event);
                }

                sendButton.setEnabled(true);
                sendButton.setText("Отправить");

                updateChat();
            });
        });
    }

    private GameEvent toGameEvent(GameEventEntity entity) {
        GameEvent.Speaker speaker;

        try {
            speaker = GameEvent.Speaker.valueOf(entity.speaker);
        } catch (Exception e) {
            speaker = GameEvent.Speaker.SYSTEM;
        }

        return new GameEvent(
                speaker,
                entity.text,
                entity.createdAt,
                entity.includeInPrompt
        );
    }

    private void renderLoadedEvent(GameEvent event) {
        if (event.getSpeaker() == GameEvent.Speaker.PLAYER) {
            appendColoredText("› " + event.getText() + "\n\n", COLOR_PLAYER);
            return;
        }

        if (event.getSpeaker() == GameEvent.Speaker.MASTER) {
            appendColoredText(event.getText() + "\n\n", COLOR_MASTER);
            return;
        }

        // Системные сообщения пока не рисуем.
    }
    private void createNewCampaignAndStart() {
        DbExecutor.execute(() -> {
            CampaignEntity campaign = new CampaignEntity();
            campaign.title = "Кампания " + System.currentTimeMillis();
            campaign.createdAt = System.currentTimeMillis();
            campaign.updatedAt = campaign.createdAt;

            long newCampaignId = database.campaignDao().insert(campaign);

            runOnUiThread(() -> {
                currentCampaignId = newCampaignId;
                startFirstScene();
            });
        });
    }
}