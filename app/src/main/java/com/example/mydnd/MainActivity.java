package com.example.mydnd;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.Context;
import android.os.Bundle;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import com.example.mydnd.game.GameEvent;
import com.example.mydnd.game.InventoryRepository;
import com.example.mydnd.game.CampaignPromptRepository;
import com.example.mydnd.game.CampaignPromptState;
import com.example.mydnd.game.save.SavedGameRepository;
import com.example.mydnd.game.world.WorldMemoryRepository;
import com.example.mydnd.game.world.WorldMaintenanceService;
import com.example.mydnd.rules.DiceService;
import com.example.mydnd.game.save.SavedGameSummary;
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
import java.util.Date;
import java.util.Locale;
import java.text.SimpleDateFormat;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.LayoutInflater;
import android.view.Window;
import android.view.ViewGroup;
import com.example.mydnd.db.AppDatabase;
import com.example.mydnd.db.DbExecutor;
import com.example.mydnd.db.entity.CampaignEntity;
import com.example.mydnd.db.entity.GameEventEntity;
import com.example.mydnd.db.entity.CharacterEntity;
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
import com.example.mydnd.util.MusicManager;
import com.example.mydnd.util.AppSettings;

public class MainActivity extends ComponentActivity {

    public static final String EXTRA_OPEN_CAMPAIGN_ID =
            "com.example.mydnd.extra.OPEN_CAMPAIGN_ID";

    private ImportanceFilter importanceFilter;
    private ChangeClassifier changeClassifier;
    private EntityExtractor entityExtractor;

    private ChangeOperationClassifier changeOperationClassifier;

    private MemoryFactExtractor memoryFactExtractor;
    private SummaryService summaryService;

    private static final String TAG_MEMORY = "MyDND_MEMORY";

    private CampaignMemory campaignMemory;

    private InventoryRepository inventoryRepository;
    private CampaignPromptRepository campaignPromptRepository;
    private SavedGameRepository savedGameRepository;
    private WorldMemoryRepository worldMemoryRepository;
    private WorldMaintenanceService worldMaintenanceService;
    private final DiceService diceService = new DiceService();

    private AppDatabase database;
    private long currentCampaignId = 0L;
    private Button continueGameButton;
    private Button loadGamesButton;

    private final ResponseCleaner responseCleaner = new ResponseCleaner();

    private boolean generationCancelledByUser = false;
    private TextView chatTextView;
    private EditText inputEditText;
    private ScrollView chatScrollView;
    private Button sendButton;
    private Button characterButton;
    private Button inventoryButton;
    private Button journalButton;
    private Button diceButton;
    private Button gameSettingsButton;

    private LlmModelManager modelManager;

    private final StringBuilder chatHistory = new StringBuilder();

    private boolean masterStreamingStarted = false;
    private int masterStreamingStartPosition = -1;
    private boolean generationInProgress = false;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder pendingTokenBuffer = new StringBuilder();

    private boolean streamFlushScheduled = false;

    private static final long STREAM_FLUSH_DELAY_MS = 80;

    private static final int WORLD_EVENT_BATCH_SIZE = 5;
    private static final int WORLD_EVENT_PREVIOUS_MASTER_LIMIT = 4;
    private static final int WORLD_EVENT_MASTER_TEXT_MAX_CHARS = 500;

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

        generationProfile = profileForResponseMode(
                AppSettings.getResponseMode(this)
        );
        welcomeBackground =
                findViewById(R.id.welcomeBackground);

        gameBackground =
                findViewById(R.id.gameBackground);

        database = AppDatabase.getInstance(this);
        campaignMemory = new CampaignMemory(database);
        inventoryRepository = new InventoryRepository(database);
        campaignPromptRepository = new CampaignPromptRepository(database);
        savedGameRepository = new SavedGameRepository(database);
        worldMemoryRepository = new WorldMemoryRepository(database);

        long requestedCampaignId =
                getIntent().getLongExtra(
                        EXTRA_OPEN_CAMPAIGN_ID,
                        0L
                );

        if (requestedCampaignId > 0L) {
            currentCampaignId = requestedCampaignId;
        } else {
            initLastCampaign();
        }


        getOnBackPressedDispatcher().addCallback(
                this,
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (gameLayout.getVisibility() == View.VISIBLE) {
                            showWelcomeScreen();
                        } else {
                            MusicManager.stop();
                            setEnabled(false);
                            getOnBackPressedDispatcher().onBackPressed();
                        }
                    }
                }
        );

        startGameButton = findViewById(R.id.startGameButton);
        continueGameButton = findViewById(R.id.continueGameButton);
        loadGamesButton = findViewById(R.id.loadGamesButton);
        settingsButton = findViewById(R.id.settingsButton);
        settingsButton.setEnabled(true);
        settingsButton.setOnClickListener(v ->
                showSettingsDialog()
        );
        exitButton = findViewById(R.id.exitButton);

        chatTextView = findViewById(R.id.chatTextView);
        inputEditText = findViewById(R.id.inputEditText);
        chatScrollView = findViewById(R.id.chatScrollView);
        sendButton = findViewById(R.id.sendButton);
        characterButton = findViewById(R.id.characterButton);
        inventoryButton = findViewById(R.id.inventoryButton);
        journalButton = findViewById(R.id.journalButton);
        diceButton = findViewById(R.id.diceButton);
        gameSettingsButton = findViewById(R.id.gameSettingsButton);


        sendButton.setEnabled(true);
        sendButton.setText("Отправить");

        characterButton.setOnClickListener(v ->
                showCharacterDialog()
        );

        inventoryButton.setOnClickListener(v ->
                showInventoryDialog()
        );

        journalButton.setOnClickListener(v ->
                showJournalDialog()
        );

        diceButton.setOnClickListener(v ->
                showDiceChoiceDialog()
        );

        if (gameSettingsButton != null) {
            gameSettingsButton.setOnClickListener(v ->
                    showSettingsDialog()
            );
        } else {
            Log.e(
                    "MyDND_UI",
                    "gameSettingsButton not found in activity_main.xml"
            );
        }

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
        worldMaintenanceService =
                new WorldMaintenanceService(
                        database,
                        modelManager,
                        worldMemoryRepository
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
            if (modelManager != null) {
                modelManager.releaseAll();
            }

            startActivity(
                    new Intent(
                            MainActivity.this,
                            NewGameActivity.class
                    )
            );
        });

        continueGameButton.setOnClickListener(v ->
                continueLastCampaign()
        );

        loadGamesButton.setOnClickListener(v ->
                showSavedGamesDialog()
        );

        exitButton.setOnClickListener(v -> {
            MusicManager.stop();
            finish();
        });

        if (requestedCampaignId > 0L) {
            showGameScreen();
            loadCampaignOrStartFirstScene();
        }


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

        processOnePassInventoryAndGenerate(playerText);




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
        MusicManager.play(
                this,
                R.raw.menu_theme
        );
        welcomeBackground.setVisibility(View.VISIBLE);
        gameBackground.setVisibility(View.GONE);

        welcomeLayout.setVisibility(View.VISIBLE);
        gameLayout.setVisibility(View.GONE);
    }

    private void showGameScreen() {
        MusicManager.play(
                this,
                R.raw.game_theme
        );

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
        MusicManager.play(
                this,
                R.raw.menu_theme
        );
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
    private void initLastCampaign() {
        DbExecutor.execute(() -> {
            CampaignEntity lastCampaign =
                    database.campaignDao().getLastCampaign();

            if (lastCampaign != null) {
                currentCampaignId = lastCampaign.id;
            }
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

                database.campaignDao()
                        .touchCampaign(
                                currentCampaignId,
                                System.currentTimeMillis()
                        );

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

                CampaignPromptState campaignState =
                        campaignPromptRepository.build(
                                campaignId
                        );

                int masterEventCount =
                        database.gameEventDao().countEventsBySpeaker(
                                campaignId,
                                "MASTER"
                        );

                boolean runWorldEventPhase =
                        masterEventCount > 0
                                && masterEventCount % WORLD_EVENT_BATCH_SIZE == 0;

                String worldEventBatchText = "";

                if (runWorldEventPhase) {
                    List<GameEventEntity> previousMasterEvents =
                            database.gameEventDao().getRecentEventsBySpeaker(
                                    campaignId,
                                    "MASTER",
                                    WORLD_EVENT_PREVIOUS_MASTER_LIMIT
                            );

                    worldEventBatchText =
                            buildWorldEventBatchText(
                                    previousMasterEvents
                            );
                }

                String rawPrompt =
                        promptBuilder.buildInventoryToolAwarePrompt(
                                playerText,
                                memoryContext,
                                inventoryBefore,
                                campaignState
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
                                + " | masterEventsBefore="
                                + masterEventCount
                                + " | worldEventCheck="
                                + runWorldEventPhase
                );

                Log.d(
                        "MyDND_ONEPASS_PROMPT",
                        "\n" + preparedPrompt
                );

                startOnePassInventoryGeneration(
                        preparedPrompt,
                        campaignId,
                        runWorldEventPhase,
                        worldEventBatchText
                );

            } catch (Throwable throwable) {
                finishOnePassPreparationError(
                        throwable
                );
            }
        });
    }


    private String buildWorldEventBatchText(
            List<GameEventEntity> previousMasterEvents
    ) {
        if (previousMasterEvents == null
                || previousMasterEvents.isEmpty()) {
            return "";
        }

        StringBuilder batch = new StringBuilder();

        int index = 1;

        for (GameEventEntity event : previousMasterEvents) {
            if (event == null
                    || event.text == null
                    || event.text.trim().isEmpty()) {
                continue;
            }

            String text = event.text
                    .replace('<', '‹')
                    .replace('>', '›')
                    .replace('\n', ' ')
                    .replace('\r', ' ')
                    .trim()
                    .replaceAll("\\s+", " ");

            if (text.length() > WORLD_EVENT_MASTER_TEXT_MAX_CHARS) {
                text = text.substring(
                        0,
                        WORLD_EVENT_MASTER_TEXT_MAX_CHARS
                );
            }

            if (batch.length() > 0) {
                batch.append("\n");
            }

            batch.append(index)
                    .append(") ")
                    .append(text);

            index++;
        }

        return batch.toString();
    }


    private void startOnePassInventoryGeneration(
            String preparedPrompt,
            long campaignId,
            boolean runWorldEventPhase,
            String worldEventBatchText
    ) {
        masterStreamingStartPosition =
                -1;

        modelManager.generateInventoryToolAware(
                ModelRole.MASTER,
                preparedPrompt,
                generationProfile,
                runWorldEventPhase,
                worldEventBatchText,
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

            if ("remember_world_event".equals(functionName)) {
                int importance = result.getWorldEventImportance();

                boolean stored = worldMemoryRepository.rememberForCampaign(
                        campaignId,
                        result.getWorldEventText(),
                        importance
                );

                Log.d(
                        "MyDND_WORLD_MEMORY",
                        (stored ? "STORED" : "SKIPPED")
                                + " importance="
                                + importance
                                + ": "
                                + result.getWorldEventText()
                );

                return "<|tool_response>response:remember_world_event{status:<|\"|>"
                        + (stored ? "STORED" : "SKIPPED")
                        + "<|\"|>,importance:"
                        + importance
                        + "}<tool_response|>";
            }

            if ("no_world_event".equals(functionName)) {
                Log.d(
                        "MyDND_WORLD_MEMORY",
                        "NO WORLD EVENT"
                );

                return "<|tool_response>response:no_world_event{status:<|\"|>OK<|\"|>}<tool_response|>";
            }

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


    private void continueLastCampaign() {
        DbExecutor.execute(() -> {
            try {
                CampaignEntity lastCampaign =
                        database.campaignDao().getLastCampaign();

                runOnUiThread(() -> {
                    if (lastCampaign == null) {
                        Toast.makeText(
                                MainActivity.this,
                                "Сохранённой игры пока нет.",
                                Toast.LENGTH_SHORT
                        ).show();
                        return;
                    }

                    openCampaignFromMenu(lastCampaign.id);
                });

            } catch (Throwable throwable) {
                Log.e(
                        "MyDND_LOAD_GAME",
                        "Failed to continue last campaign",
                        throwable
                );

                runOnUiThread(() ->
                        Toast.makeText(
                                MainActivity.this,
                                "Не удалось открыть последнюю игру.",
                                Toast.LENGTH_SHORT
                        ).show()
                );
            }
        });
    }


    private void showSavedGamesDialog() {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_saved_games);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout gamesContainer =
                dialog.findViewById(R.id.savedGamesContainer);

        Button closeButton =
                dialog.findViewById(R.id.closeSavedGamesButton);

        closeButton.setOnClickListener(v -> dialog.dismiss());

        dialog.setOnShowListener(ignored ->
                configureSavedGamesDialogWindow(
                        dialog,
                        0.94f,
                        0.88f
                )
        );

        dialog.show();
        loadSavedGamesIntoDialog(dialog, gamesContainer);
    }


    private void loadSavedGamesIntoDialog(
            Dialog dialog,
            LinearLayout gamesContainer
    ) {
        gamesContainer.removeAllViews();

        TextView loadingText = new TextView(this);
        loadingText.setText("Загружаю сохранения...");
        loadingText.setTextColor(Color.rgb(184, 176, 160));
        loadingText.setTextSize(15f);
        loadingText.setPadding(8, 24, 8, 24);
        loadingText.setGravity(android.view.Gravity.CENTER);
        gamesContainer.addView(loadingText);

        DbExecutor.execute(() -> {
            try {
                List<SavedGameSummary> savedGames =
                        savedGameRepository.getSavedGames();

                runOnUiThread(() -> {
                    if (!dialog.isShowing()) {
                        return;
                    }

                    renderSavedGameCards(
                            dialog,
                            gamesContainer,
                            savedGames
                    );
                });

            } catch (Throwable throwable) {
                Log.e(
                        "MyDND_LOAD_GAME",
                        "Failed to load campaign list",
                        throwable
                );

                runOnUiThread(() -> {
                    if (!dialog.isShowing()) {
                        return;
                    }

                    gamesContainer.removeAllViews();

                    TextView errorText = new TextView(this);
                    errorText.setText("Не удалось загрузить список игр.");
                    errorText.setTextColor(Color.rgb(216, 154, 130));
                    errorText.setTextSize(15f);
                    errorText.setPadding(8, 24, 8, 24);
                    errorText.setGravity(android.view.Gravity.CENTER);
                    gamesContainer.addView(errorText);
                });
            }
        });
    }


    private void renderSavedGameCards(
            Dialog dialog,
            LinearLayout gamesContainer,
            List<SavedGameSummary> savedGames
    ) {
        gamesContainer.removeAllViews();

        if (savedGames == null || savedGames.isEmpty()) {
            TextView emptyText = new TextView(this);
            emptyText.setText("Сохранённых игр пока нет.");
            emptyText.setTextColor(Color.rgb(184, 176, 160));
            emptyText.setTextSize(15f);
            emptyText.setPadding(8, 28, 8, 28);
            emptyText.setGravity(android.view.Gravity.CENTER);
            gamesContainer.addView(emptyText);
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);

        for (SavedGameSummary savedGame : savedGames) {
            View card = inflater.inflate(
                    R.layout.item_saved_game,
                    gamesContainer,
                    false
            );

            TextView titleText =
                    card.findViewById(R.id.savedGameTitleText);

            TextView worldCharacterText =
                    card.findViewById(R.id.savedGameWorldCharacterText);

            TextView situationText =
                    card.findViewById(R.id.savedGameSituationText);

            TextView updatedAtText =
                    card.findViewById(R.id.savedGameUpdatedAtText);

            Button playButton =
                    card.findViewById(R.id.playSavedGameButton);

            Button deleteButton =
                    card.findViewById(R.id.deleteSavedGameButton);

            String title = valueOr(
                    savedGame.getTitle(),
                    "Кампания #" + savedGame.getCampaignId()
            );

            titleText.setText(title);

            String world = valueOr(
                    savedGame.getWorldName(),
                    "без мира"
            );

            String character = valueOr(
                    savedGame.getCharacterName(),
                    "без героя"
            );

            worldCharacterText.setText(
                    "Мир: " + world + "  •  Герой: " + character
            );

            String situation = savedGame.getSituationTitle();

            if (situation == null || situation.trim().isEmpty()) {
                situationText.setVisibility(View.GONE);
            } else {
                situationText.setVisibility(View.VISIBLE);
                situationText.setText("Сейчас: " + situation.trim());
            }

            if (savedGame.getUpdatedAt() > 0L) {
                SimpleDateFormat dateFormat =
                        new SimpleDateFormat(
                                "dd.MM.yyyy HH:mm",
                                Locale.getDefault()
                        );

                updatedAtText.setVisibility(View.VISIBLE);
                updatedAtText.setText(
                        "Последняя игра: "
                                + dateFormat.format(
                                        new Date(savedGame.getUpdatedAt())
                                )
                );
            } else {
                updatedAtText.setVisibility(View.GONE);
            }

            playButton.setOnClickListener(v -> {
                dialog.dismiss();
                openCampaignFromMenu(savedGame.getCampaignId());
            });

            deleteButton.setOnClickListener(v ->
                    showDeleteSavedGameDialog(
                            dialog,
                            gamesContainer,
                            savedGame
                    )
            );

            gamesContainer.addView(card);
        }
    }


    private void showDeleteSavedGameDialog(
            Dialog savedGamesDialog,
            LinearLayout gamesContainer,
            SavedGameSummary savedGame
    ) {
        Dialog confirmDialog = new Dialog(this);
        confirmDialog.setContentView(
                R.layout.dialog_confirm_delete_saved_game
        );
        confirmDialog.setCanceledOnTouchOutside(true);

        TextView messageText =
                confirmDialog.findViewById(
                        R.id.deleteSavedGameMessage
                );

        Button cancelButton =
                confirmDialog.findViewById(
                        R.id.cancelDeleteSavedGameButton
                );

        Button deleteButton =
                confirmDialog.findViewById(
                        R.id.confirmDeleteSavedGameButton
                );

        String title = valueOr(
                savedGame.getTitle(),
                "Кампания #" + savedGame.getCampaignId()
        );

        messageText.setText(
                "Удалить сохранение «"
                        + title
                        + "»?\n\n"
                        + "Личный журнал, инвентарь и память этой кампании будут удалены. "
                        + "Живой мир и его мировые события останутся."
        );

        cancelButton.setOnClickListener(v ->
                confirmDialog.dismiss()
        );

        deleteButton.setOnClickListener(v -> {
            deleteButton.setEnabled(false);
            deleteButton.setText("Удаляю...");

            deleteSavedGame(
                    confirmDialog,
                    savedGamesDialog,
                    gamesContainer,
                    savedGame.getCampaignId()
            );
        });

        confirmDialog.setOnShowListener(ignored ->
                configureSavedGamesDialogWindow(
                        confirmDialog,
                        0.90f,
                        0f
                )
        );

        confirmDialog.show();
    }


    private void deleteSavedGame(
            Dialog confirmDialog,
            Dialog savedGamesDialog,
            LinearLayout gamesContainer,
            long campaignId
    ) {
        DbExecutor.execute(() -> {
            try {
                SavedGameRepository.DeleteSavedGameResult result =
                        savedGameRepository.deleteSavedGame(campaignId);

                runOnUiThread(() -> {
                    if (result
                            == SavedGameRepository.DeleteSavedGameResult.DELETED) {

                        if (currentCampaignId == campaignId) {
                            currentCampaignId = 0L;
                        }

                        confirmDialog.dismiss();

                        Toast.makeText(
                                MainActivity.this,
                                "Сохранение удалено.",
                                Toast.LENGTH_SHORT
                        ).show();

                        if (savedGamesDialog.isShowing()) {
                            loadSavedGamesIntoDialog(
                                    savedGamesDialog,
                                    gamesContainer
                            );
                        }

                        return;
                    }

                    Button deleteButton =
                            confirmDialog.findViewById(
                                    R.id.confirmDeleteSavedGameButton
                            );

                    deleteButton.setEnabled(true);
                    deleteButton.setText("Удалить");

                    Toast.makeText(
                            MainActivity.this,
                            "Сохранение уже не найдено.",
                            Toast.LENGTH_SHORT
                    ).show();
                });

            } catch (Throwable throwable) {
                Log.e(
                        "MyDND_LOAD_GAME",
                        "Failed to delete campaign",
                        throwable
                );

                runOnUiThread(() -> {
                    Button deleteButton =
                            confirmDialog.findViewById(
                                    R.id.confirmDeleteSavedGameButton
                            );

                    deleteButton.setEnabled(true);
                    deleteButton.setText("Удалить");

                    Toast.makeText(
                            MainActivity.this,
                            "Не удалось удалить сохранение.",
                            Toast.LENGTH_SHORT
                    ).show();
                });
            }
        });
    }


    private void configureSavedGamesDialogWindow(
            Dialog dialog,
            float widthFraction,
            float heightFraction
    ) {
        Window window = dialog.getWindow();

        if (window == null) {
            return;
        }

        window.setBackgroundDrawable(
                new ColorDrawable(Color.TRANSPARENT)
        );

        int screenWidth =
                getResources().getDisplayMetrics().widthPixels;

        int screenHeight =
                getResources().getDisplayMetrics().heightPixels;

        int width = Math.max(
                280,
                (int) (screenWidth * widthFraction)
        );

        int height = heightFraction > 0f
                ? Math.max(
                        320,
                        (int) (screenHeight * heightFraction)
                )
                : ViewGroup.LayoutParams.WRAP_CONTENT;

        window.setLayout(width, height);
    }


    private void openCampaignFromMenu(
            long campaignId
    ) {
        if (campaignId <= 0L) {
            return;
        }

        DbExecutor.execute(() -> {
            database.campaignDao().touchCampaign(
                    campaignId,
                    System.currentTimeMillis()
            );

            runOnUiThread(() -> {
                currentCampaignId = campaignId;
                showGameScreen();
                loadCampaignOrStartFirstScene();
            });
        });
    }


    private String formatSavedGameLabel(
            SavedGameSummary savedGame
    ) {
        String title = valueOr(
                savedGame.getTitle(),
                "Кампания #" + savedGame.getCampaignId()
        );

        String world = valueOr(
                savedGame.getWorldName(),
                "без мира"
        );

        String character = valueOr(
                savedGame.getCharacterName(),
                "без героя"
        );

        StringBuilder label = new StringBuilder();
        label.append(title);
        label.append("\nМир: ").append(world);
        label.append(" · Герой: ").append(character);

        if (!savedGame.getSituationTitle().isEmpty()) {
            label.append("\nСейчас: ")
                    .append(savedGame.getSituationTitle());
        }

        if (savedGame.getUpdatedAt() > 0L) {
            SimpleDateFormat dateFormat =
                    new SimpleDateFormat(
                            "dd.MM.yyyy HH:mm",
                            Locale.getDefault()
                    );

            label.append("\nПоследняя игра: ")
                    .append(
                            dateFormat.format(
                                    new Date(
                                            savedGame.getUpdatedAt()
                                    )
                            )
                    );
        }

        return label.toString();
    }


    private void showFantasyTextDialog(
            String title,
            String content,
            float heightFraction
    ) {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_fantasy_text);
        dialog.setCanceledOnTouchOutside(true);

        TextView titleText =
                dialog.findViewById(R.id.fantasyDialogTitle);

        TextView contentText =
                dialog.findViewById(R.id.fantasyDialogContent);

        Button closeButton =
                dialog.findViewById(R.id.fantasyDialogCloseButton);

        titleText.setText(
                title == null || title.trim().isEmpty()
                        ? "POCKET D&D"
                        : title.trim()
        );

        contentText.setText(
                content == null || content.trim().isEmpty()
                        ? "Пока пусто."
                        : content.trim()
        );

        closeButton.setOnClickListener(v -> dialog.dismiss());

        dialog.setOnShowListener(ignored ->
                configureFantasyDialogWindow(
                        dialog,
                        0.94f,
                        heightFraction
                )
        );

        dialog.show();
    }


    private void showSettingsDialog() {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_settings);
        dialog.setCanceledOnTouchOutside(true);

        Button musicToggleButton =
                dialog.findViewById(R.id.settingsMusicToggleButton);

        TextView volumeText =
                dialog.findViewById(R.id.settingsVolumeText);

        SeekBar volumeSeekBar =
                dialog.findViewById(R.id.settingsVolumeSeekBar);

        Button fastButton =
                dialog.findViewById(R.id.settingsFastModeButton);

        Button normalButton =
                dialog.findViewById(R.id.settingsNormalModeButton);

        Button atmosphericButton =
                dialog.findViewById(R.id.settingsAtmosphericModeButton);

        TextView modeHintText =
                dialog.findViewById(R.id.settingsModeHintText);

        TextView modelInfoText =
                dialog.findViewById(R.id.settingsModelInfoText);

        Button resetDataButton =
                dialog.findViewById(R.id.settingsResetDataButton);

        Button closeButton =
                dialog.findViewById(R.id.settingsCloseButton);

        resetDataButton.setOnClickListener(v ->
                showResetAllDataDialog(
                        dialog
                )
        );

        Runnable refreshMusicUi = () -> {
            boolean enabled = AppSettings.isMusicEnabled(this);

            musicToggleButton.setText(
                    enabled
                            ? "МУЗЫКА: ВКЛ"
                            : "МУЗЫКА: ВЫКЛ"
            );

            int percent = Math.round(
                    AppSettings.getMusicVolume(this) * 100f
            );

            volumeText.setText(
                    "Громкость: " + percent + "%"
            );
        };

        musicToggleButton.setOnClickListener(v -> {
            boolean enabled = !AppSettings.isMusicEnabled(this);
            AppSettings.setMusicEnabled(this, enabled);
            MusicManager.refresh(this);
            refreshMusicUi.run();
        });

        int initialVolume = Math.round(
                AppSettings.getMusicVolume(this) * 100f
        );

        volumeSeekBar.setProgress(initialVolume);
        refreshMusicUi.run();

        volumeSeekBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar,
                            int progress,
                            boolean fromUser
                    ) {
                        volumeText.setText(
                                "Громкость: " + progress + "%"
                        );

                        if (fromUser) {
                            AppSettings.setMusicVolume(
                                    MainActivity.this,
                                    progress / 100f
                            );
                            MusicManager.refresh(MainActivity.this);
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                    }
                }
        );

        View.OnClickListener modeClickListener = v -> {
            String mode;

            if (v.getId() == R.id.settingsFastModeButton) {
                mode = AppSettings.RESPONSE_FAST;
            } else if (v.getId() == R.id.settingsAtmosphericModeButton) {
                mode = AppSettings.RESPONSE_ATMOSPHERIC;
            } else {
                mode = AppSettings.RESPONSE_NORMAL;
            }

            AppSettings.setResponseMode(this, mode);
            generationProfile = profileForResponseMode(mode);

            refreshResponseModeUi(
                    fastButton,
                    normalButton,
                    atmosphericButton,
                    modeHintText,
                    mode
            );
        };

        fastButton.setOnClickListener(modeClickListener);
        normalButton.setOnClickListener(modeClickListener);
        atmosphericButton.setOnClickListener(modeClickListener);

        refreshResponseModeUi(
                fastButton,
                normalButton,
                atmosphericButton,
                modeHintText,
                AppSettings.getResponseMode(this)
        );

        modelInfoText.setText(
                "Модель: " + MASTER_MODEL_FILE
        );

        closeButton.setOnClickListener(v -> dialog.dismiss());

        dialog.setOnShowListener(ignored ->
                configureFantasyDialogWindow(
                        dialog,
                        0.94f,
                        0.86f
                )
        );

        dialog.show();
    }


    private void showResetAllDataDialog(
            Dialog settingsDialog
    ) {
        Dialog confirmDialog = new Dialog(this);
        confirmDialog.setContentView(
                R.layout.dialog_confirm_reset_data
        );
        confirmDialog.setCanceledOnTouchOutside(true);

        Button cancelButton =
                confirmDialog.findViewById(
                        R.id.cancelResetDataButton
                );

        Button confirmButton =
                confirmDialog.findViewById(
                        R.id.confirmResetDataButton
                );

        cancelButton.setOnClickListener(v ->
                confirmDialog.dismiss()
        );

        confirmButton.setOnClickListener(v -> {
            if (generationInProgress
                    || (modelManager != null && modelManager.isBusy())) {

                Toast.makeText(
                        MainActivity.this,
                        "Сначала остановите генерацию.",
                        Toast.LENGTH_SHORT
                ).show();

                return;
            }

            confirmButton.setEnabled(false);
            confirmButton.setText("ОБНУЛЯЮ...");

            resetAllGameData(
                    settingsDialog,
                    confirmDialog
            );
        });

        confirmDialog.setOnShowListener(ignored ->
                configureFantasyDialogWindow(
                        confirmDialog,
                        0.90f,
                        0f
                )
        );

        confirmDialog.show();
    }


    private void resetAllGameData(
            Dialog settingsDialog,
            Dialog confirmDialog
    ) {
        DbExecutor.execute(() -> {
            try {
                database.clearAllTables();

                runOnUiThread(() -> {
                    currentCampaignId = 0L;

                    chatDisplay.clear();
                    chatDisplay.clearSpans();
                    promptHistory.setLength(0);
                    gameEvents.clear();

                    inputEditText.setText("");

                    generationInProgress = false;
                    generationCancelledByUser = false;
                    masterStreamingStarted = false;
                    masterStreamingStartPosition = -1;

                    updateChat();

                    confirmDialog.dismiss();
                    settingsDialog.dismiss();

                    welcomeLayout.animate().cancel();
                    gameLayout.animate().cancel();

                    welcomeLayout.setAlpha(1f);
                    welcomeLayout.setTranslationX(0f);

                    gameLayout.setAlpha(1f);
                    gameLayout.setTranslationX(0f);

                    showWelcomeMenu();

                    Toast.makeText(
                            MainActivity.this,
                            "Все игровые данные удалены.",
                            Toast.LENGTH_SHORT
                    ).show();
                });

            } catch (Throwable throwable) {
                Log.e(
                        "MyDND_RESET_DATA",
                        "Failed to clear Room database",
                        throwable
                );

                runOnUiThread(() -> {
                    Button confirmButton =
                            confirmDialog.findViewById(
                                    R.id.confirmResetDataButton
                            );

                    if (confirmButton != null) {
                        confirmButton.setEnabled(true);
                        confirmButton.setText("ОБНУЛИТЬ");
                    }

                    Toast.makeText(
                            MainActivity.this,
                            "Не удалось обнулить данные.",
                            Toast.LENGTH_SHORT
                    ).show();
                });
            }
        });
    }


    private void refreshResponseModeUi(
            Button fastButton,
            Button normalButton,
            Button atmosphericButton,
            TextView hintText,
            String mode
    ) {
        String safeMode = AppSettings.normalizeResponseMode(mode);

        fastButton.setText(
                AppSettings.RESPONSE_FAST.equals(safeMode)
                        ? "✓ БЫСТРО"
                        : "БЫСТРО"
        );

        normalButton.setText(
                AppSettings.RESPONSE_NORMAL.equals(safeMode)
                        ? "✓ ОБЫЧНО"
                        : "ОБЫЧНО"
        );

        atmosphericButton.setText(
                AppSettings.RESPONSE_ATMOSPHERIC.equals(safeMode)
                        ? "✓ АТМОСФЕРНО"
                        : "АТМОСФЕРНО"
        );

        if (AppSettings.RESPONSE_FAST.equals(safeMode)) {
            hintText.setText(
                    "Короткие ответы. Самый быстрый режим."
            );
        } else if (AppSettings.RESPONSE_ATMOSPHERIC.equals(safeMode)) {
            hintText.setText(
                    "Более длинные ответы. Может работать заметно медленнее."
            );
        } else {
            hintText.setText(
                    "Текущий рабочий баланс скорости и атмосферы."
            );
        }
    }


    private GenerationProfile profileForResponseMode(String mode) {
        String safeMode = AppSettings.normalizeResponseMode(mode);

        if (AppSettings.RESPONSE_FAST.equals(safeMode)) {
            return GenerationProfile.fast();
        }

        if (AppSettings.RESPONSE_ATMOSPHERIC.equals(safeMode)) {
            return GenerationProfile.atmospheric();
        }

        return GenerationProfile.normal();
    }


    private void configureFantasyDialogWindow(
            Dialog dialog,
            float widthFraction,
            float heightFraction
    ) {
        Window window = dialog.getWindow();

        if (window == null) {
            return;
        }

        window.setBackgroundDrawable(
                new ColorDrawable(Color.TRANSPARENT)
        );

        int screenWidth =
                getResources().getDisplayMetrics().widthPixels;

        int screenHeight =
                getResources().getDisplayMetrics().heightPixels;

        int width = Math.max(
                280,
                (int) (screenWidth * widthFraction)
        );

        int height = heightFraction > 0f
                ? Math.max(
                        320,
                        (int) (screenHeight * heightFraction)
                )
                : ViewGroup.LayoutParams.WRAP_CONTENT;

        window.setLayout(width, height);
    }


    private void showCharacterDialog() {
        if (currentCampaignId <= 0L) {
            return;
        }

        final long campaignId = currentCampaignId;

        DbExecutor.execute(() -> {
            try {
                CampaignEntity campaign =
                        database.campaignDao().getById(
                                campaignId
                        );

                CharacterEntity character =
                        campaign != null
                                && campaign.characterId > 0L
                                ? database.characterDao()
                                        .getCharacter(
                                                campaign.characterId
                                        )
                                : null;

                String characterText =
                        formatCharacterForDialog(
                                character
                        );

                runOnUiThread(() ->
                        showFantasyTextDialog(
                                character == null
                                        ? "ПЕРСОНАЖ"
                                        : character.name,
                                characterText,
                                0.78f
                        )
                );

            } catch (Throwable throwable) {
                Log.e(
                        "MyDND_CHARACTER_UI",
                        "Failed to load character",
                        throwable
                );
            }
        });
    }


    private String formatCharacterForDialog(
            CharacterEntity character
    ) {
        if (character == null) {
            return "У этой кампании пока нет сохранённого персонажа.";
        }

        StringBuilder text = new StringBuilder();

        appendDialogField(
                text,
                "Раса",
                character.race
        );

        appendDialogField(
                text,
                "Класс",
                character.className
        );

        appendDialogField(
                text,
                "Возраст",
                character.age
        );

        if (text.length() > 0) {
            text.append("\n\n");
        }

        text.append("HP: ")
                .append(character.hp)
                .append('/')
                .append(character.maxHp)
                .append("\nСила: ")
                .append(character.strength)
                .append("  Ловкость: ")
                .append(character.dexterity)
                .append("\nИнтеллект: ")
                .append(character.intelligence)
                .append("  Харизма: ")
                .append(character.charisma);

        appendDialogSection(
                text,
                "Описание",
                character.description
        );

        appendDialogSection(
                text,
                "Предыстория",
                character.background
        );

        appendDialogSection(
                text,
                "Характер",
                character.personality
        );

        String result = text.toString().trim();

        return result.isEmpty()
                ? "Описание персонажа пока пусто."
                : result;
    }


    private void showJournalDialog() {
        if (currentCampaignId <= 0L) {
            return;
        }

        final long campaignId = currentCampaignId;

        DbExecutor.execute(() -> {
            try {
                List<GameEventEntity> events =
                        database.gameEventDao()
                                .getRecentJournalEvents(
                                        campaignId,
                                        80
                                );

                String journalText =
                        formatJournalForDialog(
                                events
                        );

                runOnUiThread(() ->
                        showFantasyTextDialog(
                                "ЖУРНАЛ",
                                journalText,
                                0.88f
                        )
                );

            } catch (Throwable throwable) {
                Log.e(
                        "MyDND_JOURNAL_UI",
                        "Failed to load journal",
                        throwable
                );
            }
        });
    }


    private String formatJournalForDialog(
            List<GameEventEntity> events
    ) {
        if (events == null || events.isEmpty()) {
            return "Журнал пока пуст.";
        }

        StringBuilder text = new StringBuilder();

        for (GameEventEntity event : events) {
            if (event == null
                    || event.text == null
                    || event.text.trim().isEmpty()) {
                continue;
            }

            if (text.length() > 0) {
                text.append("\n\n");
            }

            text.append(getJournalSpeakerLabel(event.speaker));
            text.append("\n");
            text.append(event.text.trim());
        }

        return text.length() == 0
                ? "Журнал пока пуст."
                : text.toString();
    }


    private String getJournalSpeakerLabel(
            String speaker
    ) {
        if ("PLAYER".equals(speaker)) {
            return "Игрок";
        }

        if ("MASTER".equals(speaker)) {
            return "Мастер";
        }

        return "Событие";
    }


    private void appendDialogField(
            StringBuilder text,
            String label,
            String value
    ) {
        String safeValue = value == null
                ? ""
                : value.trim();

        if (safeValue.isEmpty()) {
            return;
        }

        if (text.length() > 0) {
            text.append('\n');
        }

        text.append(label)
                .append(": ")
                .append(safeValue);
    }


    private void appendDialogSection(
            StringBuilder text,
            String title,
            String value
    ) {
        String safeValue = value == null
                ? ""
                : value.trim();

        if (safeValue.isEmpty()) {
            return;
        }

        if (text.length() > 0) {
            text.append("\n\n");
        }

        text.append(title)
                .append(":\n")
                .append(safeValue);
    }


    private String valueOr(
            String value,
            String fallback
    ) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }

        return value.trim();
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
                        showFantasyTextDialog(
                                "ИНВЕНТАРЬ",
                                inventoryText,
                                0.68f
                        )
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



    private void runWorldMaintenanceAfterMasterTurn() {
        runWorldMaintenance(true);
    }

    private void runWorldMaintenanceIfDue() {
        runWorldMaintenance(false);
    }

    private void runWorldMaintenance(boolean completedMasterTurn) {
        if (worldMaintenanceService == null
                || currentCampaignId <= 0L) {
            return;
        }

        final long campaignId = currentCampaignId;

        WorldMaintenanceService.Listener listener =
                new WorldMaintenanceService.Listener() {
                    @Override
                    public void onStarted(String task) {
                        runOnUiThread(() -> {
                            if (currentCampaignId != campaignId) {
                                return;
                            }

                            sendButton.setEnabled(false);

                            if ("WORLD_SUMMARY".equals(task)) {
                                sendButton.setText("Обновляю мир...");
                            } else {
                                sendButton.setText("Мир живёт...");
                            }
                        });
                    }

                    @Override
                    public void onWorldSummaryUpdated(String summary) {
                        Log.d(
                                "MyDND_WORLD_SUMMARY",
                                "UPDATED: " + summary
                        );

                        restoreSendButtonAfterMaintenance(campaignId);
                    }

                    @Override
                    public void onRandomEventGenerated(
                            String text,
                            int importance,
                            String tone
                    ) {
                        runOnUiThread(() -> {
                            if (currentCampaignId == campaignId) {
                                appendColoredText(
                                        "Мир меняется: "
                                                + text
                                                + "\n\n",
                                        COLOR_SYSTEM
                                );
                            }
                        });

                        restoreSendButtonAfterMaintenance(campaignId);
                    }

                    @Override
                    public void onIdle() {
                        restoreSendButtonAfterMaintenance(campaignId);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        Log.e(
                                "MyDND_WORLD_MAINT",
                                "World maintenance failed",
                                throwable
                        );

                        restoreSendButtonAfterMaintenance(campaignId);
                    }
                };

        if (completedMasterTurn) {
            worldMaintenanceService.onMasterTurnCompleted(
                    campaignId,
                    listener
            );
        } else {
            worldMaintenanceService.runIfDue(
                    campaignId,
                    listener
            );
        }
    }

    private void restoreSendButtonAfterMaintenance(long campaignId) {
        runOnUiThread(() -> {
            if (currentCampaignId != campaignId
                    || generationInProgress
                    || (worldMaintenanceService != null
                    && worldMaintenanceService.isWorking())) {
                return;
            }

            sendButton.setEnabled(true);
            sendButton.setText("Отправить");
        });
    }

    private void showDiceChoiceDialog() {
        if (generationInProgress) {
            Toast.makeText(
                    this,
                    "Сначала завершите текущий ход.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_dice_choice);
        dialog.setCanceledOnTouchOutside(true);

        int[] buttonIds = {
                R.id.diceChoiceD4,
                R.id.diceChoiceD6,
                R.id.diceChoiceD8,
                R.id.diceChoiceD10,
                R.id.diceChoiceD12,
                R.id.diceChoiceD20,
                R.id.diceChoiceD100
        };

        int[] sides = {
                4, 6, 8, 10, 12, 20, 100
        };

        for (int i = 0; i < buttonIds.length; i++) {
            Button choiceButton =
                    dialog.findViewById(buttonIds[i]);

            final int selectedSides = sides[i];

            choiceButton.setOnClickListener(v -> {
                dialog.dismiss();
                showDiceRollDialog(selectedSides);
            });
        }

        Button closeButton =
                dialog.findViewById(R.id.diceChoiceCloseButton);

        closeButton.setOnClickListener(v -> dialog.dismiss());

        dialog.setOnShowListener(ignored ->
                configureFantasyDialogWindow(
                        dialog,
                        0.90f,
                        0f
                )
        );

        dialog.show();
    }


    private void showDiceRollDialog(int sides) {
        if (generationInProgress) {
            Toast.makeText(
                    this,
                    "Сначала завершите текущий ход.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        final DiceService.RollResult result =
                diceService.roll(sides, 0);

        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_dice_roll);
        dialog.setCanceledOnTouchOutside(false);

        TextView titleText =
                dialog.findViewById(R.id.diceTitleText);

        TextView valueText =
                dialog.findViewById(R.id.diceValueText);

        TextView hintText =
                dialog.findViewById(R.id.diceHintText);

        Button closeButton =
                dialog.findViewById(R.id.diceCloseButton);

        titleText.setText("БРОСОК D" + sides);
        valueText.setText("—");
        hintText.setText("Кубик катится...");
        closeButton.setEnabled(false);

        final long animationDurationMs = 1100L;
        final long animationStartedAt = System.currentTimeMillis();
        final Runnable[] animation = new Runnable[1];

        animation[0] = () -> {
            if (!dialog.isShowing()) {
                return;
            }

            long elapsed =
                    System.currentTimeMillis() - animationStartedAt;

            if (elapsed >= animationDurationMs) {
                valueText.setText(
                        String.valueOf(result.getTotal())
                );
                hintText.setText(
                        result.getNatural() == sides
                                ? "Критический максимум"
                                : "Результат"
                );
                closeButton.setEnabled(true);
                return;
            }

            valueText.setText(
                    String.valueOf(
                            diceService.animationFace(sides)
                    )
            );

            uiHandler.postDelayed(
                    animation[0],
                    70L
            );
        };

        closeButton.setOnClickListener(v -> dialog.dismiss());

        dialog.setOnShowListener(ignored -> {
            configureFantasyDialogWindow(
                    dialog,
                    0.80f,
                    0f
            );

            uiHandler.post(animation[0]);

            runWorldMaintenanceIfDue();
        });

        dialog.setOnDismissListener(ignored ->
                uiHandler.removeCallbacks(animation[0])
        );

        dialog.show();
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

                                runWorldMaintenanceAfterMasterTurn();
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