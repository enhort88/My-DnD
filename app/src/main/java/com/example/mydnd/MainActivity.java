package com.example.mydnd;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import com.example.mydnd.game.GameEvent;
import com.example.mydnd.game.InventoryRepository;
import com.example.mydnd.llm.GenerationProfile;
import com.example.mydnd.llm.GemmaToolCallParser;
import com.example.mydnd.llm.LlmCallback;
import com.example.mydnd.llm.NativeToolCallback;
import com.example.mydnd.llm.ResponseCleaner;
import com.example.mydnd.prompt.PromptBuilder;
import com.example.mydnd.prompt.GemmaToolPromptBuilder;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
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
import android.util.Log;
import com.example.mydnd.memory.CampaignMemory;
import com.example.mydnd.memory.MemoryContext;
import com.example.mydnd.llm.LlmModelManager;
import com.example.mydnd.llm.ModelRole;
import com.example.mydnd.memory.SummaryService;
import com.example.mydnd.db.entity.SummaryEntity;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import android.widget.ImageView;
import com.example.mydnd.memory.MemoryFactExtractor;
import com.example.mydnd.memory.ImportanceFilter;
import com.example.mydnd.memory.ChangeClassifier;
import com.example.mydnd.memory.ChangeOperationClassifier;
import com.example.mydnd.memory.EntityExtractor;

public class MainActivity extends ComponentActivity {

    private ImportanceFilter importanceFilter;
    private ChangeClassifier changeClassifier;
    private EntityExtractor entityExtractor;

    private ChangeOperationClassifier changeOperationClassifier;

    private MemoryFactExtractor memoryFactExtractor;
    private SummaryService summaryService;

    private static final String TAG_MEMORY = "MyDND_MEMORY";

    private CampaignMemory campaignMemory;

    private InventoryRepository inventoryRepository;

    private AppDatabase database;
    private long currentCampaignId = 0L;
    private Button loadGameButton;

    private final ResponseCleaner responseCleaner = new ResponseCleaner();

    private boolean generationCancelledByUser = false;
    private TextView chatTextView;
    private EditText inputEditText;
    private ScrollView chatScrollView;
    private Button sendButton;
    private Button inventoryButton;

    private LlmModelManager modelManager;

    private final StringBuilder chatHistory = new StringBuilder();

    private boolean masterStreamingStarted = false;
    private int masterStreamingStartPosition = -1;
    private boolean generationInProgress = false;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder pendingTokenBuffer = new StringBuilder();

    private boolean streamFlushScheduled = false;

    private static final long STREAM_FLUSH_DELAY_MS = 80;

    private static final String MASTER_MODEL_FILE =
            "gemma-4-E2B_q4_0-it.gguf";


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

    private ImageView welcomeBackground;
    private ImageView gameBackground;

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

    private final GemmaToolPromptBuilder gemmaToolPromptBuilder =
            new GemmaToolPromptBuilder();

    private final GemmaToolCallParser gemmaToolCallParser =
            new GemmaToolCallParser();
    private GenerationProfile generationProfile = GenerationProfile.normal();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        welcomeBackground =
                findViewById(R.id.welcomeBackground);

        gameBackground =
                findViewById(R.id.gameBackground);

        database = AppDatabase.getInstance(this);
        campaignMemory = new CampaignMemory(database);
        inventoryRepository = new InventoryRepository(database);

        initDefaultCampaign();


        getOnBackPressedDispatcher().addCallback(
                this,
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (gameLayout.getVisibility() == View.VISIBLE) {
                            showWelcomeScreen();
                        } else {
                            setEnabled(false);
                            getOnBackPressedDispatcher().onBackPressed();
                        }
                    }
                }
        );

        startGameButton = findViewById(R.id.startGameButton);
        loadGameButton = findViewById(R.id.loadGameButton);
        settingsButton = findViewById(R.id.settingsButton);
//        settingsButton.setEnabled(true);
//        settingsButton.setText("ТЕСТ ФАКТОВ");
//
//        settingsButton.setOnClickListener(v -> {
//            runMemoryFactExtractorV1();
//        });
        exitButton = findViewById(R.id.exitButton);

        chatTextView = findViewById(R.id.chatTextView);
        inputEditText = findViewById(R.id.inputEditText);
        chatScrollView = findViewById(R.id.chatScrollView);
        sendButton = findViewById(R.id.sendButton);
        inventoryButton = findViewById(R.id.inventoryButton);

        sendButton.setEnabled(true);
        sendButton.setText("Отправить");

        inventoryButton.setOnClickListener(v ->
                showInventoryDialog()
        );

        File modelsDirectory =
                getExternalFilesDir("models");

        File masterModelFile = new File(
                modelsDirectory,
                MASTER_MODEL_FILE
        );

        File serviceModelFile = new File(
                modelsDirectory,
                "gemma-3-1b-it-q4_0.gguf"
        );

        modelManager = new LlmModelManager(
                masterModelFile.getAbsolutePath(),
                serviceModelFile.getAbsolutePath()
        );
        summaryService =
                new SummaryService(
                        database,
                        modelManager
                );
        memoryFactExtractor =
                new MemoryFactExtractor(
                        database,
                        modelManager
                );
        importanceFilter =
                new ImportanceFilter(
                        database,
                        modelManager
                );
        changeClassifier =
                new ChangeClassifier(
                        modelManager
                );
        entityExtractor =
                new EntityExtractor(
                        modelManager
                );
        changeOperationClassifier =
                new ChangeOperationClassifier(
                        modelManager
                );
        welcomeLayout =
                findViewById(R.id.welcomeLayout);

        gameLayout =
                findViewById(R.id.gameLayout);

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
                modelManager.cancelCurrent();

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

        if (playerText.startsWith("/cont ")) {
            String action =
                    playerText
                            .substring("/cont ".length())
                            .trim();

            if (action.isEmpty()) {
                return;
            }

            generationCancelledByUser = false;
            generationInProgress = true;
            masterStreamingStarted = false;

            inputEditText.setText("");
            hideKeyboard();

            appendPlayerMessage(action);

            sendButton.setEnabled(true);
            sendButton.setText("Стоп");

            showThinkingIndicator();

            processOnePassInventoryAndGenerate(
                    action
            );

            return;
        }

        if (playerText.startsWith("/tool ")) {
            String action =
                    playerText
                            .substring("/tool ".length())
                            .trim();

            inputEditText.setText("");
            hideKeyboard();

            runInventoryToolTest(
                    action
            );

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

        showThinkingIndicator();

        processInventoryAndGenerate(playerText);




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
        welcomeBackground.setVisibility(View.VISIBLE);
        gameBackground.setVisibility(View.GONE);

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
        welcomeBackground.setVisibility(View.GONE);
        gameBackground.setVisibility(View.VISIBLE);
    }


    private void showWelcomeScreen() {
        welcomeBackground.setVisibility(View.VISIBLE);
        gameBackground.setVisibility(View.GONE);
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
    private void saveEventToDb(
            GameEvent event
    ) {
        saveEventToDb(
                event,
                null
        );
    }


    private void saveEventToDb(
            GameEvent event,
            Runnable onSaved
    ) {
        if (currentCampaignId == 0L) {

            Log.w(
                    "MyDND_DB",
                    "Event not saved: campaignId=0"
            );

            return;
        }

        DbExecutor.execute(() -> {

            try {
                GameEventEntity entity =
                        new GameEventEntity();

                entity.campaignId =
                        currentCampaignId;

                entity.speaker =
                        event.getSpeaker().name();

                entity.text =
                        event.getText();

                entity.includeInPrompt =
                        event.isIncludeInPrompt();

                entity.createdAt =
                        event.getCreatedAtMillis();

                database.gameEventDao()
                        .insert(entity);

                Log.d(
                        "MyDND_DB",
                        "Event saved: speaker="
                                + entity.speaker
                );

                if (onSaved != null) {

                    runOnUiThread(
                            onSaved
                    );
                }

            } catch (Throwable throwable) {

                Log.e(
                        "MyDND_DB",
                        "Failed to save event",
                        throwable
                );

                runOnUiThread(() -> {
                    sendButton.setEnabled(true);
                    sendButton.setText(
                            "Отправить"
                    );
                });
            }
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

            MemoryContext memoryContext =
                    campaignMemory.buildContext(
                            currentCampaignId,
                            ""
                    );

            Log.d(
                    TAG_MEMORY,
                    "Summary: " + memoryContext.getLatestSummary()
            );

            Log.d(
                    TAG_MEMORY,
                    "Recent events: "
                            + memoryContext.getRecentEvents().size()
            );

            Log.d(
                    TAG_MEMORY,
                    "Relevant facts: "
                            + memoryContext.getRelevantFacts().size()
            );

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



    /**
     * Эксперимент /cont:
     * один prompt decode, затем tool call -> Java/Room -> tool response
     * и художественное продолжение в том же native context.
     */
    private void processOnePassInventoryAndGenerate(
            String playerText
    ) {
        if (currentCampaignId <= 0L) {
            finishOnePassPreparationError(
                    new IllegalStateException(
                            "campaignId <= 0"
                    )
            );

            return;
        }

        final long campaignId =
                currentCampaignId;

        DbExecutor.execute(() -> {
            try {
                List<String> inventoryBefore =
                        inventoryRepository.getItemNames(
                                campaignId
                        );

                MemoryContext memoryContext =
                        campaignMemory.buildContext(
                                campaignId,
                                playerText
                        );

                String rawPrompt =
                        promptBuilder.buildInventoryToolAwarePrompt(
                                playerText,
                                memoryContext,
                                inventoryBefore
                        );

                String preparedPrompt =
                        prepareMasterPrompt(
                                rawPrompt
                        );

                Log.d(
                        "MyDND_ONEPASS",
                        "START campaignId="
                                + campaignId
                                + " | inventoryBefore="
                                + inventoryBefore
                                + " | promptChars="
                                + preparedPrompt.length()
                );

                Log.d(
                        "MyDND_ONEPASS_PROMPT",
                        "\n" + preparedPrompt
                );

                startOnePassInventoryGeneration(
                        preparedPrompt,
                        campaignId
                );

            } catch (Throwable throwable) {
                finishOnePassPreparationError(
                        throwable
                );
            }
        });
    }


    private void startOnePassInventoryGeneration(
            String preparedPrompt,
            long campaignId
    ) {
        masterStreamingStartPosition =
                -1;

        modelManager.generateInventoryToolAware(
                ModelRole.MASTER,
                preparedPrompt,
                generationProfile,
                new NativeToolCallback() {

                    @Override
                    public String onToolCall(
                            String rawToolCall
                    ) {
                        return executeInventoryToolCallForContinuation(
                                campaignId,
                                rawToolCall
                        );
                    }
                },
                createMasterGenerationCallback()
        );
    }


    /**
     * Вызывается прямо из native generation thread.
     * Здесь синхронно выполняется Room-команда, после чего строка
     * tool response возвращается обратно в тот же llama_context.
     */
    private String executeInventoryToolCallForContinuation(
            long campaignId,
            String rawToolCall
    ) {
        Log.d(
                "MyDND_ONEPASS",
                "TOOL RAW:\n" + rawToolCall
        );

        try {
            GemmaToolCallParser.Result result =
                    gemmaToolCallParser.parse(
                            rawToolCall
                    );

            if (!result.hasToolCall()) {
                List<String> inventoryNow =
                        inventoryRepository.getItemNames(
                                campaignId
                        );

                String response =
                        buildGemmaInventoryToolResponse(
                                "no_inventory_change",
                                "REJECTED",
                                "NO_TOOL_CALL_PARSED",
                                "",
                                inventoryNow
                        );

                Log.e(
                        "MyDND_ONEPASS",
                        "Parser did not find tool call; returning safe response"
                );

                return response;
            }

            String functionName =
                    result.getFunctionName();

            if ("no_inventory_change".equals(
                    functionName
            )) {
                List<String> inventoryNow =
                        inventoryRepository.getItemNames(
                                campaignId
                        );

                String response =
                        buildGemmaInventoryToolResponse(
                                functionName,
                                "OK",
                                "NO_INVENTORY_CHANGE",
                                "",
                                inventoryNow
                        );

                Log.d(
                        "MyDND_ONEPASS",
                        "TOOL NO CHANGE | AFTER="
                                + inventoryNow
                );

                return response;
            }

            InventoryRepository.ApplyResult applyResult =
                    inventoryRepository.applyToolCall(
                            campaignId,
                            functionName,
                            result.getItemName()
                    );

            List<String> inventoryNow =
                    inventoryRepository.getItemNames(
                            campaignId
                    );

            String status =
                    applyResult.isApplied()
                            ? "APPLIED"
                            : "REJECTED";

            Log.d(
                    "MyDND_ONEPASS",
                    "TOOL "
                            + status
                            + " | function="
                            + functionName
                            + " | code="
                            + applyResult.getCode()
                            + " | item="
                            + applyResult.getItemName()
                            + " | AFTER="
                            + inventoryNow
            );

            return buildGemmaInventoryToolResponse(
                    functionName,
                    status,
                    applyResult.getCode(),
                    applyResult.getItemName(),
                    inventoryNow
            );

        } catch (Throwable throwable) {
            Log.e(
                    "MyDND_ONEPASS",
                    "Tool execution failed",
                    throwable
            );

            List<String> inventoryNow;

            try {
                inventoryNow =
                        inventoryRepository.getItemNames(
                                campaignId
                        );
            } catch (Throwable ignored) {
                inventoryNow =
                        Collections.emptyList();
            }

            return buildGemmaInventoryToolResponse(
                    "no_inventory_change",
                    "ERROR",
                    "JAVA_TOOL_EXCEPTION",
                    "",
                    inventoryNow
            );
        }
    }


    private String buildGemmaInventoryToolResponse(
            String functionName,
            String status,
            String code,
            String itemName,
            List<String> inventoryAfter
    ) {
        String safeFunctionName =
                sanitizeToolField(
                        functionName
                );

        if (safeFunctionName.isEmpty()) {
            safeFunctionName =
                    "no_inventory_change";
        }

        String inventoryText =
                inventoryAfter == null
                || inventoryAfter.isEmpty()
                        ? "пусто"
                        : String.join(
                                " | ",
                                inventoryAfter
                        );

        return "<|tool_response>response:"
                + safeFunctionName
                + "{status:<|\"|>"
                + sanitizeToolField(status)
                + "<|\"|>,code:<|\"|>"
                + sanitizeToolField(code)
                + "<|\"|>,item:<|\"|>"
                + sanitizeToolField(itemName)
                + "<|\"|>,inventory_after:<|\"|>"
                + sanitizeToolField(inventoryText)
                + "<|\"|>}<tool_response|>";
    }


    private String sanitizeToolField(
            String value
    ) {
        if (value == null) {
            return "";
        }

        return value
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .replace("<|", "‹")
                .replace("|>", "›")
                .replace('{', '(')
                .replace('}', ')')
                .trim()
                .replaceAll(
                        "\\s+",
                        " "
                );
    }


    private void finishOnePassPreparationError(
            Throwable throwable
    ) {
        Log.e(
                "MyDND_ONEPASS",
                "Failed to prepare one-pass generation",
                throwable
        );

        runOnUiThread(() -> {
            removeThinkingIndicator();

            generationInProgress =
                    false;

            generationCancelledByUser =
                    false;

            masterStreamingStarted =
                    false;

            sendButton.setEnabled(
                    true
            );

            sendButton.setText(
                    "Отправить"
            );
        });
    }


    /**
     * Обычный игровой ход теперь сначала проверяет только изменение инвентаря.
     * Tool-call скрыт от игрока. После применения команды запускается обычный MASTER.
     */
    private void processInventoryAndGenerate(
            String playerText
    ) {
        if (currentCampaignId <= 0L) {
            buildMemoryAndGenerate(
                    playerText,
                    Collections.emptyList(),
                    ""
            );

            return;
        }

        DbExecutor.execute(() -> {
            try {
                List<String> inventoryBefore =
                        inventoryRepository.getItemNames(
                                currentCampaignId
                        );

                String toolPrompt =
                        gemmaToolPromptBuilder
                                .buildInventoryPrompt(
                                        playerText,
                                        inventoryBefore
                                );

                runOnUiThread(() ->
                        startInventoryRouting(
                                playerText,
                                inventoryBefore,
                                toolPrompt
                        )
                );

            } catch (Throwable throwable) {
                Log.e(
                        "MyDND_INVENTORY_FLOW",
                        "Failed to prepare inventory routing",
                        throwable
                );

                runOnUiThread(() ->
                        buildMemoryAndGenerate(
                                playerText,
                                Collections.emptyList(),
                                ""
                        )
                );
            }
        });
    }


    private void startInventoryRouting(
            String playerText,
            List<String> inventoryBefore,
            String toolPrompt
    ) {
        Log.d(
                "MyDND_INVENTORY_FLOW",
                "BEFORE=" + inventoryBefore
        );

        modelManager.generate(
                ModelRole.MASTER,
                toolPrompt,
                GenerationProfile.toolCallTest(),
                new LlmCallback() {

                    @Override
                    public void onToken(
                            String token
                    ) {
                        // Служебный tool-call игроку не показываем.
                    }


                    @Override
                    public void onComplete(
                            String fullText
                    ) {
                        if (generationCancelledByUser) {
                            runOnUiThread(() ->
                                    finishCancelledInventoryRouting()
                            );

                            return;
                        }

                        Log.d(
                                "MyDND_INVENTORY_FLOW",
                                "RAW TOOL RESULT:\n" + fullText
                        );

                        GemmaToolCallParser.Result result =
                                gemmaToolCallParser.parse(
                                        fullText
                                );

                        if (!result.hasToolCall()) {
                            runOnUiThread(() ->
                                    buildMemoryAndGenerate(
                                            playerText,
                                            inventoryBefore,
                                            ""
                                    )
                            );

                            return;
                        }

                        String functionName =
                                result.getFunctionName();

                        String itemName =
                                result.getItemName();

                        DbExecutor.execute(() -> {
                            InventoryRepository.ApplyResult applyResult =
                                    inventoryRepository.applyToolCall(
                                            currentCampaignId,
                                            functionName,
                                            itemName
                                    );

                            List<String> inventoryNow =
                                    inventoryRepository.getItemNames(
                                            currentCampaignId
                                    );

                            String inventoryUpdate =
                                    buildInventoryUpdateForPrompt(
                                            functionName,
                                            applyResult
                                    );

                            Log.d(
                                    "MyDND_INVENTORY_FLOW",
                                    "TOOL "
                                            + (applyResult.isApplied()
                                            ? "APPLIED"
                                            : "REJECTED")
                                            + " code="
                                            + applyResult.getCode()
                                            + " | item="
                                            + applyResult.getItemName()
                                            + " | AFTER="
                                            + inventoryNow
                            );

                            runOnUiThread(() ->
                                    buildMemoryAndGenerate(
                                            playerText,
                                            inventoryNow,
                                            inventoryUpdate
                                    )
                            );
                        });
                    }


                    @Override
                    public void onError(
                            Throwable throwable
                    ) {
                        if (generationCancelledByUser) {
                            runOnUiThread(() ->
                                    finishCancelledInventoryRouting()
                            );

                            return;
                        }

                        Log.e(
                                "MyDND_INVENTORY_FLOW",
                                "Inventory routing failed; continuing without update",
                                throwable
                        );

                        runOnUiThread(() ->
                                buildMemoryAndGenerate(
                                        playerText,
                                        inventoryBefore,
                                        ""
                                )
                        );
                    }
                }
        );
    }


    private String buildInventoryUpdateForPrompt(
            String functionName,
            InventoryRepository.ApplyResult applyResult
    ) {
        if (applyResult == null
                || !applyResult.isApplied()) {

            return "";
        }

        if (InventoryRepository.TOOL_ADD_ITEM.equals(
                functionName
        )) {
            return "Персонаж получил предмет: "
                    + applyResult.getItemName()
                    + ".";
        }

        if (InventoryRepository.TOOL_REMOVE_ITEM.equals(
                functionName
        )) {
            return "Персонаж больше не имеет предмета: "
                    + applyResult.getItemName()
                    + ".";
        }

        return "";
    }


    private void finishCancelledInventoryRouting() {
        removeThinkingIndicator();
        removeLastPlayerEvent();

        generationInProgress = false;
        generationCancelledByUser = false;
        masterStreamingStarted = false;
        masterStreamingStartPosition = -1;

        sendButton.setEnabled(true);
        sendButton.setText("Отправить");
    }


    private void showInventoryDialog() {
        if (currentCampaignId <= 0L) {
            return;
        }

        DbExecutor.execute(() -> {
            try {
                List<String> inventory =
                        inventoryRepository.getItemNames(
                                currentCampaignId
                        );

                String inventoryText =
                        formatInventoryForDialog(
                                inventory
                        );

                runOnUiThread(() ->
                        new AlertDialog.Builder(
                                MainActivity.this
                        )
                                .setTitle("Инвентарь")
                                .setMessage(inventoryText)
                                .setPositiveButton(
                                        "Закрыть",
                                        null
                                )
                                .show()
                );

            } catch (Throwable throwable) {
                Log.e(
                        "MyDND_INVENTORY_UI",
                        "Failed to load inventory",
                        throwable
                );
            }
        });
    }


    private String formatInventoryForDialog(
            List<String> inventory
    ) {
        if (inventory == null
                || inventory.isEmpty()) {

            return "Пусто";
        }

        StringBuilder builder =
                new StringBuilder();

        for (String item : inventory) {
            if (item == null
                    || item.trim().isEmpty()) {

                continue;
            }

            if (builder.length() > 0) {
                builder.append('\n');
            }

            builder.append("• ");
            builder.append(item.trim());
        }

        return builder.length() == 0
                ? "Пусто"
                : builder.toString();
    }


    private void runInventoryToolTest(
            String action
    ) {
        if (action == null
                || action.trim().isEmpty()) {

            return;
        }

        if (modelManager.isBusy()) {
            Log.w(
                    "MyDND_TOOL",
                    "Skipped: model manager is busy"
            );

            return;
        }

        if (currentCampaignId <= 0L) {
            Log.w(
                    "MyDND_TOOL",
                    "Skipped: campaignId is not ready"
            );

            return;
        }

        generationInProgress = true;

        sendButton.setEnabled(false);
        sendButton.setText("Читаю инвентарь...");

        DbExecutor.execute(() -> {
            try {
                List<String> inventoryBefore =
                        inventoryRepository.getItemNames(
                                currentCampaignId
                        );

                String prompt =
                        gemmaToolPromptBuilder
                                .buildInventoryPrompt(
                                        action,
                                        inventoryBefore
                                );

                runOnUiThread(() ->
                        startInventoryToolGeneration(
                                action,
                                inventoryBefore,
                                prompt
                        )
                );

            } catch (Throwable throwable) {
                Log.e(
                        "MyDND_TOOL",
                        "Failed to read inventory before tool call",
                        throwable
                );

                runOnUiThread(() -> {
                    generationInProgress = false;
                    sendButton.setEnabled(true);
                    sendButton.setText("Отправить");

                    appendColoredText(
                            "[INVENTORY ERROR] "
                                    + throwable.getMessage()
                                    + "\n\n",
                            COLOR_SYSTEM
                    );
                });
            }
        });
    }


    private void startInventoryToolGeneration(
            String action,
            List<String> inventoryBefore,
            String prompt
    ) {
        Log.d(
                "MyDND_TOOL",
                "INVENTORY BEFORE="
                        + inventoryBefore
        );

        Log.d(
                "MyDND_TOOL",
                "PROMPT:\n"
                        + prompt
        );

        sendButton.setEnabled(false);
        sendButton.setText("Тест tool...");

        appendColoredText(
                "› [TOOL TEST] "
                        + action
                        + "\n\n",
                COLOR_PLAYER
        );

        modelManager.generate(
                ModelRole.MASTER,
                prompt,
                GenerationProfile.toolCallTest(),
                new LlmCallback() {

                    @Override
                    public void onToken(
                            String token
                    ) {
                        // Tool-call токены не показываем пользователю.
                    }


                    @Override
                    public void onComplete(
                            String fullText
                    ) {
                        Log.d(
                                "MyDND_TOOL",
                                "RAW RESULT:\n"
                                        + fullText
                        );

                        GemmaToolCallParser.Result result =
                                gemmaToolCallParser.parse(
                                        fullText
                                );

                        if (!result.hasToolCall()) {
                            DbExecutor.execute(() -> {
                                List<String> inventoryNow =
                                        inventoryRepository.getItemNames(
                                                currentCampaignId
                                        );

                                runOnUiThread(() -> {
                                    generationInProgress = false;
                                    sendButton.setEnabled(true);
                                    sendButton.setText("Отправить");

                                    Log.d(
                                            "MyDND_TOOL",
                                            "NO TOOL CALL | inventory="
                                                    + inventoryNow
                                    );

                                    appendColoredText(
                                            "[NO INVENTORY CHANGE]\n"
                                                    + "Инвентарь: "
                                                    + formatInventoryForUi(
                                                            inventoryNow
                                                    )
                                                    + "\n\n",
                                            COLOR_SYSTEM
                                    );
                                });
                            });

                            return;
                        }

                        String functionName =
                                result.getFunctionName();

                        String itemName =
                                result.getItemName();

                        Log.d(
                                "MyDND_TOOL",
                                "TOOL CALL name="
                                        + functionName
                                        + " | item="
                                        + itemName
                        );

                        DbExecutor.execute(() -> {
                            InventoryRepository.ApplyResult applyResult =
                                    inventoryRepository.applyToolCall(
                                            currentCampaignId,
                                            functionName,
                                            itemName
                                    );

                            List<String> inventoryNow =
                                    inventoryRepository.getItemNames(
                                            currentCampaignId
                                    );

                            if (applyResult.isApplied()) {
                                Log.d(
                                        "MyDND_TOOL",
                                        "TOOL APPLIED code="
                                                + applyResult.getCode()
                                                + " | item="
                                                + applyResult.getItemName()
                                                + " | inventory="
                                                + inventoryNow
                                );

                            } else {
                                Log.w(
                                        "MyDND_TOOL",
                                        "TOOL REJECTED code="
                                                + applyResult.getCode()
                                                + " | item="
                                                + applyResult.getItemName()
                                                + " | inventory="
                                                + inventoryNow
                                );
                            }

                            runOnUiThread(() -> {
                                generationInProgress = false;
                                sendButton.setEnabled(true);
                                sendButton.setText("Отправить");

                                String status =
                                        applyResult.isApplied()
                                                ? "APPLIED"
                                                : "REJECTED";

                                appendColoredText(
                                        "[TOOL "
                                                + status
                                                + "] "
                                                + functionName
                                                + "\n"
                                                + applyResult.getItemName()
                                                + "\n"
                                                + "code="
                                                + applyResult.getCode()
                                                + "\n"
                                                + "Инвентарь: "
                                                + formatInventoryForUi(
                                                        inventoryNow
                                                )
                                                + "\n\n",
                                        COLOR_SYSTEM
                                );
                            });
                        });
                    }


                    @Override
                    public void onError(
                            Throwable throwable
                    ) {
                        Log.e(
                                "MyDND_TOOL",
                                "Tool test failed",
                                throwable
                        );

                        runOnUiThread(() -> {
                            generationInProgress = false;

                            sendButton.setEnabled(true);
                            sendButton.setText("Отправить");

                            appendColoredText(
                                    "[TOOL TEST ERROR] "
                                            + throwable.getMessage()
                                            + "\n\n",
                                    COLOR_SYSTEM
                            );
                        });
                    }
                }
        );
    }


    private String formatInventoryForUi(
            List<String> inventory
    ) {
        if (inventory == null
                || inventory.isEmpty()) {

            return "(пусто)";
        }

        return String.join(
                ", ",
                inventory
        );
    }


    private void buildMemoryAndGenerate(
            String playerText,
            List<String> inventory,
            String inventoryUpdate
    ) {
        DbExecutor.execute(() -> {
            try {
                MemoryContext memoryContext =
                        campaignMemory.buildContext(
                                currentCampaignId,
                                playerText
                        );

                String prompt =
                        promptBuilder.buildPrompt(
                                playerText,
                                memoryContext,
                                inventory,
                                inventoryUpdate
                        );

                Log.d(
                        TAG_MEMORY,
                        "Prompt memory: summary="
                                + memoryContext.hasSummary()
                                + ", recentEvents="
                                + memoryContext.getRecentEvents().size()
                                + ", relevantFacts="
                                + memoryContext.getRelevantFacts().size()
                );
                for (String fact : memoryContext.getRelevantFacts()) {
                    Log.d(
                            TAG_MEMORY,
                            "Retrieved fact: " + fact
                    );
                }

                startLlmGeneration(
                        prompt
                );

            } catch (Throwable throwable) {
                runOnUiThread(() -> {
                    removeThinkingIndicator();

                    generationInProgress = false;

                    sendButton.setEnabled(true);
                    sendButton.setText("Отправить");

                    Log.e(
                            TAG_MEMORY,
                            "Failed to build memory context",
                            throwable
                    );
                });
            }
        });
    }

    private void startLlmGeneration(
            String prompt
    ) {
        masterStreamingStartPosition = -1;

        String preparedPrompt =
                prepareMasterPrompt(
                        prompt
                );

        Log.d(
                "MyDND_FINAL_PROMPT",
                "\n" + preparedPrompt
        );

        Log.d(
                "MyDND_MODEL",
                "MASTER model="
                        + MASTER_MODEL_FILE
                        + ", promptChars="
                        + preparedPrompt.length()
        );

        modelManager.generate(
                ModelRole.MASTER,
                preparedPrompt,
                generationProfile,
                createMasterGenerationCallback()
        );
    }


    private LlmCallback createMasterGenerationCallback() {
        return new LlmCallback() {

            @Override
            public void onToken(String token) {
                runOnUiThread(() -> {
                    if (token == null || token.isEmpty()) {
                        return;
                    }

                    if (token.startsWith("[Система]")) {
                        flushStreamingTokens();
                        return;
                    }

                    if (!masterStreamingStarted) {
                        removeThinkingIndicator();

                        // Запоминаем позицию, с которой начался сырой
                        // streaming-ответ мастера.
                        masterStreamingStartPosition =
                                chatTextView.getText().length();

                        masterStreamingStarted = true;
                    }

                    appendStreamingToken(token);
                });
            }


            @Override
            public void onComplete(
                    String fullText
            ) {
                Log.d(
                        "MyDND_MASTER_RAW",
                        "RAW:\n"
                                + fullText
                );


                final String narrative =
                        fullText == null
                                ? ""
                                : fullText;


                runOnUiThread(() -> {

                    removeThinkingIndicator();

                    flushStreamingTokens();


                    if (generationCancelledByUser) {

                        removeLastPlayerEvent();


                        appendColoredText(
                                "\n\n",
                                COLOR_MASTER
                        );


                        generationInProgress =
                                false;

                        generationCancelledByUser =
                                false;

                        masterStreamingStarted =
                                false;

                        masterStreamingStartPosition =
                                -1;


                        sendButton.setEnabled(
                                true
                        );

                        sendButton.setText(
                                "Отправить"
                        );


                        updateChat();

                        return;
                    }


                    /*
                     * Чистим финальный художественный ответ
                     * перед показом и сохранением.
                     */
                    String visibleText =
                            responseCleaner.clean(
                                    narrative
                            );


                    if (masterStreamingStarted) {

                        /*
                         * Streaming уже показал пользователю
                         * художественный ответ.
                         *
                         * Заменяем его финальной очищенной
                         * версией.
                         */
                        replaceStreamedMasterText(
                                visibleText
                        );

                    } else {

                        appendColoredText(
                                visibleText
                                        + "\n\n",
                                COLOR_MASTER
                        );
                    }


                    /*
                     * В game_events сохраняется только
                     * художественный текст мастера.
                     */
                    GameEvent masterEvent =
                            GameEvent.master(
                                    visibleText
                            );


                    gameEvents.add(
                            masterEvent
                    );


                    generationInProgress =
                            false;

                    masterStreamingStarted =
                            false;

                    masterStreamingStartPosition =
                            -1;


                    sendButton.setEnabled(
                            false
                    );

                    sendButton.setText(
                            "Сохраняю ход..."
                    );


                    updateChat();


                    saveEventToDb(
                            masterEvent,
                            () -> {

                                sendButton.setEnabled(
                                        true
                                );

                                sendButton.setText(
                                        "Отправить"
                                );
                            }
                    );
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

                    sendButton.setEnabled(true);
                    sendButton.setText("Отправить");

                    Log.e(
                            "MyDND_LLM",
                            "Generation failed",
                            throwable
                    );
                });
            }
        };
    }


    private void updateSummaryIfNeeded() {
        summaryService.updateIfNeeded(
                currentCampaignId,
                new SummaryService.Listener() {

                    @Override
                    public void onStarted(int eventCount) {
                        runOnUiThread(() -> {
                            sendButton.setEnabled(false);
                            sendButton.setText(
                                    "Обновляю память..."
                            );
                        });
                    }

                    @Override
                    public void onSkipped(int eventCount) {
                        Log.d(
                                "MyDND_SUMMARY",
                                "Skipped, new events="
                                        + eventCount
                        );

                        runOnUiThread(() -> {
                            if (!generationInProgress) {
                                sendButton.setEnabled(true);
                                sendButton.setText("Отправить");
                            }
                        });
                    }

                    @Override
                    public void onCompleted(
                            SummaryEntity summary
                    ) {
                        Log.d(
                                "MyDND_SUMMARY",
                                "Summary completed"
                        );

                        runOnUiThread(() -> {

                            sendButton.setEnabled(true);

                            sendButton.setText(
                                    "Отправить"
                            );
                        });
                    }

                    @Override
                    public void onError(
                            Throwable throwable
                    ) {
                        Log.e(
                                "MyDND_SUMMARY",
                                "Summary failed",
                                throwable
                        );

                        runOnUiThread(() -> {
                            sendButton.setEnabled(true);
                            sendButton.setText("Отправить");
                        });
                    }
                }
        );
    }
    private void replaceStreamedMasterText(
            String cleanedText
    ) {
        if (masterStreamingStartPosition < 0) {
            appendMasterMessage(cleanedText);
            return;
        }

        CharSequence currentText =
                chatTextView.getText();

        int safeStart =
                Math.min(
                        masterStreamingStartPosition,
                        currentText.length()
                );

        SpannableStringBuilder cleanedDisplay =
                new SpannableStringBuilder(currentText);

        // Удаляем весь сырой streaming:
        // английский think, повторы и прочий хвост.
        cleanedDisplay.delete(
                safeStart,
                cleanedDisplay.length()
        );

        // ВАЖНО:
        // синхронизируем основной буфер чата,
        // потому что updateChat() показывает именно chatDisplay.
        chatDisplay.clear();
        chatDisplay.clearSpans();
        chatDisplay.append(cleanedDisplay);

        // Добавляем уже очищенный финальный ответ
        // в основной буфер.
        appendColoredText(
                cleanedText + "\n\n",
                COLOR_MASTER
        );

        masterStreamingStartPosition = -1;
    }
    private void runMemoryFactExtractorV1(
            Runnable onFinished
    ) {
        Log.d(
                "MyDND_FACTS",
                "Extractor campaignId="
                        + currentCampaignId
        );

        memoryFactExtractor.extractLatest(
                currentCampaignId,
                new MemoryFactExtractor.Listener() {

                    @Override
                    public void onStarted(
                            int eventCount
                    ) {
                        runOnUiThread(() -> {

                            sendButton.setEnabled(
                                    false
                            );

                            sendButton.setText(
                                    "Ищу факты..."
                            );
                        });
                    }


                    @Override
                    public void onCompleted(
                            String rawCandidates
                    ) {
                        Log.d(
                                "MyDND_FACTS",
                                "Extractor result:\n"
                                        + rawCandidates
                        );

                        runOnUiThread(
                                onFinished
                        );
                    }


                    @Override
                    public void onSkipped(
                            String reason
                    ) {
                        Log.d(
                                "MyDND_FACTS",
                                "Skipped: "
                                        + reason
                        );

                        runOnUiThread(
                                onFinished
                        );
                    }


                    @Override
                    public void onError(
                            Throwable throwable
                    ) {
                        Log.e(
                                "MyDND_FACTS",
                                "Extractor failed",
                                throwable
                        );

                        // Ошибка extractor не должна
                        // ломать всю игру.
                        // Всё равно проверяем summary.
                        runOnUiThread(
                                onFinished
                        );
                    }
                }
        );
    }

    private String prepareMasterPrompt(
            String prompt
    ) {
        String cleanPrompt =
                prompt == null
                        ? ""
                        : prompt.trim();


        /*
         * Сейчас PromptBuilder строит:
         *
         * SYSTEM:
         * ...
         *
         * STYLE:
         * ...
         *
         * CURRENT_SCENE:
         * ...
         *
         * Поэтому временно разделяем его здесь.
         *
         * SYSTEM + STYLE
         *      ↓
         * настоящая system role
         *
         * CURRENT_SCENE и всё после неё
         *      ↓
         * user role
         */
        String splitMarker =
                "\n\nCURRENT_SCENE:";


        int splitIndex =
                cleanPrompt.indexOf(
                        splitMarker
                );


        String systemText;

        String userText;


        if (splitIndex >= 0) {

            systemText =
                    cleanPrompt
                            .substring(
                                    0,
                                    splitIndex
                            )
                            .trim();


            userText =
                    cleanPrompt
                            .substring(
                                    splitIndex
                            )
                            .trim();

        } else {

            /*
             * Безопасный fallback.
             *
             * Если структура PromptBuilder когда-нибудь
             * изменится, запрос хотя бы останется рабочим.
             */
            systemText =
                    "Ты мастер настольной RPG в духе DnD.";

            userText =
                    cleanPrompt;
        }


        /*
         * Само слово SYSTEM: больше не нужно:
         * теперь это настоящая роль модели.
         */
        if (systemText.startsWith("SYSTEM:")) {

            systemText =
                    systemText
                            .substring(
                                    "SYSTEM:".length()
                            )
                            .trim();
        }


        return "<|turn>system\n"
                + systemText
                + "<turn|>\n"
                + "<|turn>user\n"
                + userText
                + "<turn|>\n"
                + "<|turn>model\n";
    }

    private void runImportanceFilterV1(
            Runnable onFinished
    ) {
        importanceFilter.filterLatestTurn(
                currentCampaignId,
                new ImportanceFilter.Listener() {

                    @Override
                    public void onStarted(
                            int sentenceCount
                    ) {
                        runOnUiThread(() -> {

                            sendButton.setEnabled(false);

                            sendButton.setText(
                                    "Фильтрую память..."
                            );
                        });
                    }


                    @Override
                    public void onCompleted(
                            List<ImportanceFilter.ImportantSentence> sentences
                    ) {
                        Log.d(
                                "MyDND_IMPORTANCE",
                                "Filter completed, important="
                                        + sentences.size()
                        );


                        for (ImportanceFilter.ImportantSentence sentence
                                : sentences) {

                            Log.d(
                                    "MyDND_IMPORTANCE",
                                    "RESULT ["
                                            + sentence.getId()
                                            + "] "
                                            + sentence.getSpeaker()
                                            + ": "
                                            + sentence.getText()
                            );
                        }


                        runOnUiThread(() ->
                                runChangeClassifierV1(
                                        sentences,
                                        onFinished
                                )
                        );
                    }


                    @Override
                    public void onSkipped(
                            String reason
                    ) {
                        Log.d(
                                "MyDND_IMPORTANCE",
                                "Skipped: "
                                        + reason
                        );

                        runOnUiThread(
                                onFinished
                        );
                    }


                    @Override
                    public void onError(
                            Throwable throwable
                    ) {
                        Log.e(
                                "MyDND_IMPORTANCE",
                                "Filter failed",
                                throwable
                        );

                        runOnUiThread(
                                onFinished
                        );
                    }
                }
        );
    }
    private void runChangeClassifierV1(
            List<ImportanceFilter.ImportantSentence> sentences,
            Runnable onFinished
    ) {
        if (sentences == null
                || sentences.isEmpty()) {

            Log.d(
                    "MyDND_CLASSIFIER",
                    "No candidates from ImportanceFilter"
            );

            onFinished.run();

            return;
        }


        changeClassifier.classify(
                sentences,
                new ChangeClassifier.Listener() {

                    @Override
                    public void onStarted(
                            int candidateCount
                    ) {
                        runOnUiThread(() -> {

                            sendButton.setEnabled(false);

                            sendButton.setText(
                                    "Анализирую память..."
                            );
                        });
                    }


                    @Override
                    public void onCompleted(
                            List<ChangeClassifier.ClassifiedSentence> result
                    ) {
                        for (ChangeClassifier.ClassifiedSentence item
                                : result) {

                            Log.d(
                                    "MyDND_CLASSIFIER",
                                    "FINAL ["
                                            + item.getId()
                                            + "] "
                                            + item.getType()
                                            + ": "
                                            + item.getText()
                            );
                        }


                        runOnUiThread(() ->
                                runEntityExtractorV1(
                                        result,
                                        onFinished
                                )
                        );
                    }


                    @Override
                    public void onSkipped(
                            String reason
                    ) {
                        Log.d(
                                "MyDND_CLASSIFIER",
                                "Skipped: "
                                        + reason
                        );

                        runOnUiThread(
                                onFinished
                        );
                    }


                    @Override
                    public void onError(
                            Throwable throwable
                    ) {
                        Log.e(
                                "MyDND_CLASSIFIER",
                                "Classifier failed",
                                throwable
                        );

                        runOnUiThread(
                                onFinished
                        );
                    }
                }
        );
    }
    private void runChangeOperationClassifierV1(
            List<ChangeClassifier.ClassifiedSentence> result,
            Runnable onFinished
    ) {
        changeOperationClassifier.classify(
                result,
                new ChangeOperationClassifier.Listener() {

                    @Override
                    public void onStarted(
                            int candidateCount
                    ) {
                        runOnUiThread(() -> {

                            sendButton.setEnabled(false);

                            sendButton.setText(
                                    "Определяю изменения..."
                            );
                        });
                    }


                    @Override
                    public void onCompleted(
                            List<ChangeOperationClassifier.OperationResult> result
                    ) {
                        for (ChangeOperationClassifier.OperationResult item
                                : result) {

                            Log.d(
                                    "MyDND_OPERATION",
                                    "FINAL ["
                                            + item.getId()
                                            + "] "
                                            + item.getType()
                                            + " / "
                                            + item.getOperation()
                                            + ": "
                                            + item.getText()
                            );
                        }


                        runOnUiThread(
                                onFinished
                        );
                    }


                    @Override
                    public void onSkipped(
                            String reason
                    ) {
                        Log.d(
                                "MyDND_OPERATION",
                                "Skipped: "
                                        + reason
                        );

                        runOnUiThread(
                                onFinished
                        );
                    }


                    @Override
                    public void onError(
                            Throwable throwable
                    ) {
                        Log.e(
                                "MyDND_OPERATION",
                                "Operation classifier failed",
                                throwable
                        );

                        runOnUiThread(
                                onFinished
                        );
                    }
                }
        );
    }
    private void runEntityExtractorV1(
            List<ChangeClassifier.ClassifiedSentence> result,
            Runnable onFinished
    ) {
        entityExtractor.extract(
                result,
                new EntityExtractor.Listener() {

                    @Override
                    public void onStarted(
                            int candidateCount
                    ) {
                        runOnUiThread(() -> {

                            sendButton.setEnabled(false);

                            sendButton.setText(
                                    "Ищу объекты..."
                            );
                        });
                    }


                    @Override
                    public void onCompleted(
                            List<EntityExtractor.EntityResult> result
                    ) {
                        for (EntityExtractor.EntityResult item
                                : result) {

                            Log.d(
                                    "MyDND_ENTITY",
                                    "FINAL ["
                                            + item.getId()
                                            + "] "
                                            + item.getType()
                                            + " | entity="
                                            + item.getEntityName()
                                            + " | source="
                                            + item.getSourceText()
                            );
                        }


                        runOnUiThread(
                                onFinished
                        );
                    }


                    @Override
                    public void onSkipped(
                            String reason
                    ) {
                        Log.d(
                                "MyDND_ENTITY",
                                "Skipped: "
                                        + reason
                        );

                        runOnUiThread(
                                onFinished
                        );
                    }


                    @Override
                    public void onError(
                            Throwable throwable
                    ) {
                        Log.e(
                                "MyDND_ENTITY",
                                "Entity extraction failed",
                                throwable
                        );

                        runOnUiThread(
                                onFinished
                        );
                    }
                }
        );
    }
}