package com.example.mydnd;
import android.animation.AnimatorSet;
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
import com.example.mydnd.game.CharacterLifeRepository;
import com.example.mydnd.game.CharacterLifeState;
import com.example.mydnd.game.GameEvent;
import com.example.mydnd.game.InventoryRepository;
import com.example.mydnd.game.InventoryActionHintResolver;
import com.example.mydnd.game.StateChangeRepository;
import com.example.mydnd.game.CampaignPromptRepository;
import com.example.mydnd.game.CampaignPromptState;
import com.example.mydnd.director.DirectorAction;
import com.example.mydnd.director.DirectorActionParser;
import com.example.mydnd.director.DirectorActionType;
import com.example.mydnd.director.DirectorFlowController;
import com.example.mydnd.director.DirectorMode;
import com.example.mydnd.director.DirectorPromptState;
import com.example.mydnd.director.DirectorPromptStateRepository;
import com.example.mydnd.director.DirectorResult;
import com.example.mydnd.director.RoomDirectorStore;
import com.example.mydnd.diagnostics.BehaviorTestCase;
import com.example.mydnd.diagnostics.BehaviorTestDataset;
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
import com.example.mydnd.llm.NarrativeDirectiveParser;
import com.example.mydnd.prompt.PromptBuilder;
import com.example.mydnd.prompt.GemmaToolPromptBuilder;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Date;
import java.util.Locale;
import java.text.SimpleDateFormat;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.ClickableSpan;
import android.text.style.LineBackgroundSpan;
import android.text.style.LeadingMarginSpan;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.graphics.Typeface;
import android.view.View;
import android.view.MotionEvent;
import android.view.LayoutInflater;
import android.view.Window;
import android.view.ViewGroup;
import com.example.mydnd.db.AppDatabase;
import com.example.mydnd.db.DbExecutor;
import com.example.mydnd.db.entity.CampaignEntity;
import com.example.mydnd.db.entity.GameEventEntity;
import com.example.mydnd.db.entity.CharacterEntity;
import com.example.mydnd.db.entity.StateChangeEntity;
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
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.text.Editable;
import android.text.TextWatcher;
import com.example.mydnd.util.GameBackgroundManager;

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
    private static final String TAG_BEHAVIOR_TEST = "MyDND_BEHAVIOR_TEST";
    private static final String DIRECTOR_CHECK_PENDING_MARKER =
            "__MYDND_CHECK_PENDING__";

    private CampaignMemory campaignMemory;

    private InventoryRepository inventoryRepository;
    private final InventoryActionHintResolver inventoryActionHintResolver =
            new InventoryActionHintResolver();
    private StateChangeRepository stateChangeRepository;
    private CharacterLifeRepository characterLifeRepository;
    private CampaignPromptRepository campaignPromptRepository;
    private DirectorFlowController directorFlowController;
    private DirectorPromptStateRepository directorPromptStateRepository;
    private SavedGameRepository savedGameRepository;
    private WorldMemoryRepository worldMemoryRepository;
    private WorldMaintenanceService worldMaintenanceService;
    private final DiceService diceService = new DiceService();

    private AppDatabase database;
    private long currentCampaignId = 0L;
    private Button continueGameButton;
    private Button loadGamesButton;

    private final ResponseCleaner responseCleaner = new ResponseCleaner();
    private final NarrativeDirectiveParser narrativeDirectiveParser =
            new NarrativeDirectiveParser();

    private boolean generationCancelledByUser = false;
    private TextView chatTextView;
    private EditText inputEditText;
    private ScrollView chatScrollView;

    private Button sendButton;
    private AnimatorSet sendButtonBusyAnimator;
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
    private volatile boolean directorTurnActive = false;
    private volatile long activeDirectorCampaignId = 0L;
    private volatile long pendingDirectorCheckId = 0L;
    private volatile long pendingDirectorCheckCampaignId = 0L;
    private volatile boolean diceContinuationTurnActive = false;
    private volatile boolean behaviorTestInProgress = false;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder pendingTokenBuffer = new StringBuilder();

    private boolean streamFlushScheduled = false;

    private boolean chatAutoFollowEnabled = true;
    private boolean chatUserTouching = false;
    private boolean chatProgrammaticScroll = false;

    private static final long STREAM_FLUSH_DELAY_MS = 80;
    private static final int CHAT_BOTTOM_THRESHOLD_DP = 72;

    private static final String MASTER_MODEL_FILE =
            "gemma-4-E2B_q4_0-it.gguf";


    private View welcomeLayout;
    private View gameLayout;

    private Button startGameButton;
    private Button settingsButton;
    private Button exitButton;
    private Button helpButton;

    private final SpannableStringBuilder chatDisplay = new SpannableStringBuilder();
    private final StringBuilder promptHistory = new StringBuilder();

    private int COLOR_MASTER;
    private int COLOR_PLAYER;
    private int COLOR_SYSTEM;

    private int COLOR_CARD_BG;
    private int COLOR_CARD_BORDER;
    private int COLOR_CARD_GOOD;
    private int COLOR_CARD_BAD;
    private int COLOR_CARD_NEUTRAL;

    private int COLOR_CARD_REVERTED_BG;
    private int COLOR_CARD_REVERTED_TEXT;

    private int COLOR_INPUT_TEXT;
    private int COLOR_INPUT_HINT;
    private int COLOR_INPUT_TINT;

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


        GameBackgroundManager.apply(
                this,
                gameBackground
        );

        database = AppDatabase.getInstance(this);
        campaignMemory = new CampaignMemory(database);
        inventoryRepository = new InventoryRepository(database);
        stateChangeRepository = new StateChangeRepository(database);
        characterLifeRepository = new CharacterLifeRepository(
                database,
                stateChangeRepository
        );
        campaignPromptRepository = new CampaignPromptRepository(database);
        directorPromptStateRepository = new DirectorPromptStateRepository(
                database,
                inventoryRepository
        );
        directorFlowController = new DirectorFlowController(
                new RoomDirectorStore(
                        database,
                        inventoryRepository,
                        stateChangeRepository
                ),
                this::onDirectorResult
        );
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
        helpButton = findViewById(R.id.helpButton);

        chatTextView = findViewById(R.id.chatTextView);
        inputEditText = findViewById(R.id.inputEditText);
        chatScrollView = findViewById(R.id.chatScrollView);
        sendButton = findViewById(R.id.sendButton);
        characterButton = findViewById(R.id.characterButton);
        inventoryButton = findViewById(R.id.inventoryButton);
        journalButton = findViewById(R.id.journalButton);
        diceButton = findViewById(R.id.diceButton);
        gameSettingsButton = findViewById(R.id.gameSettingsButton);

        applyGamePalette();

        chatTextView.setMovementMethod(LinkMovementMethod.getInstance());
        chatTextView.setHighlightColor(Color.TRANSPARENT);

        chatScrollView.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();

            if (action == MotionEvent.ACTION_DOWN
                    || action == MotionEvent.ACTION_MOVE) {
                chatUserTouching = true;
            } else if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                chatUserTouching = false;
                chatAutoFollowEnabled = isChatNearBottom();
            }

            return false;
        });

        chatScrollView.setOnScrollChangeListener(
                (view, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                    if (!chatProgrammaticScroll && chatUserTouching) {
                        chatAutoFollowEnabled = isChatNearBottom();
                    }
                }
        );

        sendButton.setEnabled(true);
        sendButton.setText("Отправить");
        setupSendButtonBusyAnimation();

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
                        modelManager
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


        helpButton.setOnClickListener(v ->
                showAboutDialog()
        );

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
        chatDisplay.clearSpans();
        gameEvents.clear();
        promptHistory.setLength(0);

        appendMasterMessage(
                "Ты стоишь у входа в старую придорожную таверну. " +
                        "За мутными окнами дрожит жёлтый свет. " +
                        "Дождь стучит по крыше, а где-то рядом воет пёс. " +
                        "Дверь приоткрыта, но внутри подозрительно тихо."
        );

        inputEditText.setEnabled(true);
        sendButton.setEnabled(true);
        sendButton.setText("Отправить");
    }

    private void sendPlayerMessage() {
        if (!inputEditText.isEnabled()) {
            tryOpenDeathSaveFromLifeState();
            return;
        }

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

            processDirectorAndGenerate(action);
            return;
        }

        if (playerText.startsWith("/tool ")) {
            String action =
                    playerText
                            .substring("/tool ".length())
                            .trim();

            inputEditText.setText("");
            hideKeyboard();

            runInventoryToolTest(action);
            return;
        }

        routePlayerActionByLifeState(playerText);
    }

    private void routePlayerActionByLifeState(String playerText) {
        final long campaignId = currentCampaignId;

        if (campaignId <= 0L) {
            startNormalPlayerTurn(playerText);
            return;
        }

        sendButton.setEnabled(false);
        sendButton.setText("Проверяю состояние...");

        DbExecutor.execute(() -> {
            CharacterEntity character =
                    characterLifeRepository.getForCampaign(campaignId);

            runOnUiThread(() -> {
                if (currentCampaignId != campaignId) {
                    refreshCharacterLifeUi();
                    return;
                }

                if (character == null) {
                    startNormalPlayerTurn(playerText);
                    return;
                }

                CharacterLifeState state = CharacterLifeState.from(
                        character.lifeState,
                        character.hp
                );

                if (state == CharacterLifeState.ALIVE) {
                    startNormalPlayerTurn(playerText);
                    return;
                }

                if (state == CharacterLifeState.DOWNED) {
                    showDeathSaveDialog(campaignId);
                    return;
                }

                if (state == CharacterLifeState.STABLE) {
                    Toast.makeText(
                            this,
                            "Персонаж стабилен, но без сознания. Нужна помощь или лечение.",
                            Toast.LENGTH_LONG
                    ).show();
                    refreshCharacterLifeUi();
                    return;
                }

                Toast.makeText(
                        this,
                        "Этот персонаж погиб. Обычные действия недоступны.",
                        Toast.LENGTH_LONG
                ).show();
                refreshCharacterLifeUi();
            });
        });
    }

    private void startNormalPlayerTurn(String playerText) {
        generationCancelledByUser = false;
        generationInProgress = true;
        masterStreamingStarted = false;

        inputEditText.setText("");
        hideKeyboard();

        chatAutoFollowEnabled = true;
        appendPlayerMessage(playerText);

        sendButton.setEnabled(true);
        sendButton.setText("Стоп");

        showThinkingIndicator();
        processDirectorAndGenerate(playerText);
    }

    private void tryOpenDeathSaveFromLifeState() {
        final long campaignId = currentCampaignId;
        if (campaignId <= 0L || generationInProgress) {
            return;
        }

        DbExecutor.execute(() -> {
            CharacterEntity character =
                    characterLifeRepository.getForCampaign(campaignId);

            runOnUiThread(() -> {
                if (currentCampaignId != campaignId) {
                    return;
                }

                CharacterLifeState state = character == null
                        ? CharacterLifeState.ALIVE
                        : CharacterLifeState.from(
                                character.lifeState,
                                character.hp
                        );

                if (state == CharacterLifeState.DOWNED) {
                    showDeathSaveDialog(campaignId);
                } else {
                    refreshCharacterLifeUi();
                }
            });
        });
    }


    private void refreshCharacterLifeUi() {
        if (generationInProgress) {
            return;
        }

        final long campaignId = currentCampaignId;
        if (campaignId <= 0L) {
            inputEditText.setEnabled(true);
            sendButton.setEnabled(true);
            sendButton.setText("Отправить");
            return;
        }

        DbExecutor.execute(() -> {
            CharacterEntity character =
                    characterLifeRepository.getForCampaign(campaignId);

            runOnUiThread(() -> {
                if (currentCampaignId != campaignId || generationInProgress) {
                    return;
                }

                CharacterLifeState state = character == null
                        ? CharacterLifeState.ALIVE
                        : CharacterLifeState.from(
                                character.lifeState,
                                character.hp
                        );

                if (state == CharacterLifeState.ALIVE) {
                    inputEditText.setEnabled(true);
                    sendButton.setEnabled(true);
                    sendButton.setText("Отправить");
                } else if (state == CharacterLifeState.DOWNED) {
                    inputEditText.setEnabled(false);
                    sendButton.setEnabled(true);
                    sendButton.setText("Спасбросок смерти");
                } else if (state == CharacterLifeState.STABLE) {
                    inputEditText.setEnabled(false);
                    sendButton.setEnabled(false);
                    sendButton.setText("Стабилен без сознания");
                } else {
                    inputEditText.setEnabled(false);
                    sendButton.setEnabled(false);
                    sendButton.setText("Персонаж погиб");
                }

                Log.d(
                        "MyDND_DEATH",
                        "UI STATE | campaignId=" + campaignId
                                + " | state=" + state
                                + (character == null
                                ? ""
                                : " | hp=" + character.hp + "/" + character.maxHp
                                + " | saves=" + character.deathSaveSuccesses
                                + "/3," + character.deathSaveFailures + "/3")
                );
            });
        });
    }

    private void showDeathSaveDialog(long campaignId) {
        if (campaignId <= 0L
                || currentCampaignId != campaignId
                || generationInProgress) {
            return;
        }

        sendButton.setEnabled(false);
        sendButton.setText("Бросок смерти...");

        final DiceService.RollResult roll = diceService.roll(20, 0);

        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_dice_roll);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setCancelable(false);

        TextView titleText = dialog.findViewById(R.id.diceTitleText);
        TextView valueText = dialog.findViewById(R.id.diceValueText);
        TextView hintText = dialog.findViewById(R.id.diceHintText);
        Button closeButton = dialog.findViewById(R.id.diceCloseButton);

        titleText.setText("СПАСБРОСОК СМЕРТИ");
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

            long elapsed = System.currentTimeMillis() - animationStartedAt;
            if (elapsed < animationDurationMs) {
                valueText.setText(String.valueOf(diceService.animationFace(20)));
                uiHandler.postDelayed(animation[0], 70L);
                return;
            }

            valueText.setText(String.valueOf(roll.getNatural()));
            hintText.setText("Сохраняю результат...");

            DbExecutor.execute(() -> {
                CharacterLifeRepository.DeathSaveOutcome outcome =
                        characterLifeRepository.resolveDeathSave(
                                campaignId,
                                roll.getNatural()
                        );

                StateChangeEntity change = outcome.getStateChangeId() > 0L
                        ? stateChangeRepository.get(outcome.getStateChangeId())
                        : null;

                Log.d(
                        "MyDND_DEATH",
                        "DEATH SAVE | roll=" + roll.getNatural()
                                + " | applied=" + outcome.isApplied()
                                + " | code=" + outcome.getCode()
                                + " | state=" + outcome.getState()
                                + " | saves=" + outcome.getSuccesses()
                                + "/3," + outcome.getFailures() + "/3"
                );

                runOnUiThread(() -> {
                    if (!dialog.isShowing()) {
                        return;
                    }

                    if (!outcome.isApplied()) {
                        hintText.setText("Не удалось применить спасбросок: " + outcome.getCode());
                        closeButton.setText("ЗАКРЫТЬ");
                        closeButton.setEnabled(true);
                        return;
                    }

                    if (currentCampaignId == campaignId && change != null) {
                        appendStateChangeCard(change);
                        updateChat();
                    }

                    hintText.setText(deathSaveHint(outcome));
                    closeButton.setText(
                            outcome.getState() == CharacterLifeState.ALIVE
                                    ? "ПРОДОЛЖИТЬ"
                                    : "ЗАКРЫТЬ"
                    );
                    closeButton.setEnabled(true);
                });
            });
        };

        closeButton.setOnClickListener(v -> dialog.dismiss());

        dialog.setOnShowListener(ignored -> {
            configureFantasyDialogWindow(dialog, 0.80f, 0f);
            uiHandler.post(animation[0]);
        });

        dialog.setOnDismissListener(ignored -> {
            uiHandler.removeCallbacks(animation[0]);
            refreshCharacterLifeUi();
        });

        dialog.show();
    }

    private String lifeStateLabel(CharacterLifeState state) {
        if (state == CharacterLifeState.DOWNED) {
            return "Без сознания";
        }
        if (state == CharacterLifeState.STABLE) {
            return "Стабилен без сознания";
        }
        if (state == CharacterLifeState.DEAD) {
            return "Погиб";
        }
        return "Жив";
    }

    private String deathSaveHint(
            CharacterLifeRepository.DeathSaveOutcome outcome
    ) {
        if (outcome.getState() == CharacterLifeState.DEAD) {
            return "СМЕРТЬ • 3/3 провалов";
        }

        if (outcome.getState() == CharacterLifeState.STABLE) {
            return "СТАБИЛЕН • 3/3 успехов";
        }

        if (outcome.getState() == CharacterLifeState.ALIVE) {
            return "НАТУРАЛЬНАЯ 20 • 1 HP";
        }

        return "Успехи " + outcome.getSuccesses() + "/3"
                + " • Провалы " + outcome.getFailures() + "/3";
    }

    private void updateChat() {
        chatTextView.setText(chatDisplay);

        if (!chatAutoFollowEnabled) {
            return;
        }

        chatScrollView.post(() -> {
            if (!chatAutoFollowEnabled) {
                return;
            }

            chatProgrammaticScroll = true;
            chatScrollView.fullScroll(ScrollView.FOCUS_DOWN);
            chatScrollView.post(() -> chatProgrammaticScroll = false);
        });
    }


    private boolean isChatNearBottom() {
        int contentHeight = chatTextView.getHeight();
        int viewportBottom = chatScrollView.getScrollY()
                + chatScrollView.getHeight();
        int thresholdPx = dp(CHAT_BOTTOM_THRESHOLD_DP);

        return contentHeight <= 0
                || contentHeight - viewportBottom <= thresholdPx;
    }


    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density
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

    private void showStateChangeBeforeNarrative(
            long campaignId,
            StateChangeEntity change
    ) {
        if (change == null) {
            return;
        }

        runOnUiThread(() -> {
            if (currentCampaignId != campaignId) {
                return;
            }

            removeThinkingIndicator();
            appendStateChangeCard(change);
            updateChat();
        });
    }


    private void appendStateChangeCard(
            StateChangeEntity change
    ) {
        if (change == null) {
            return;
        }

        boolean reverted =
                StateChangeRepository.STATUS_REVERTED.equals(change.status);

        int accentColor = reverted
                ? COLOR_CARD_REVERTED_TEXT
                : stateChangeColor(change.type);

        int backgroundColor = reverted
                ? COLOR_CARD_REVERTED_BG
                : COLOR_CARD_BG;

        int cardStart = chatDisplay.length();

        chatDisplay.append("\u200A\n");

        int titleStart = chatDisplay.length();
        chatDisplay.append(stateChangeMark(change.type));
        chatDisplay.append("  ");
        chatDisplay.append(change.title);
        int titleEnd = chatDisplay.length();

        int actionStart = -1;
        int actionEnd = -1;

        if (reverted) {
            chatDisplay.append("   ");
            actionStart = chatDisplay.length();
            chatDisplay.append("отменено");
            actionEnd = chatDisplay.length();

        } else if (StateChangeRepository.TYPE_DICE_CHECK.equals(change.type)
                && StateChangeRepository.STATUS_PENDING.equals(change.status)) {
            chatDisplay.append("   ");
            actionStart = chatDisplay.length();
            chatDisplay.append("БРОСИТЬ d20");
            actionEnd = chatDisplay.length();

        } else if (change.canUndo
                && StateChangeRepository.STATUS_APPLIED.equals(change.status)) {
            chatDisplay.append("   ");
            actionStart = chatDisplay.length();
            chatDisplay.append("↶");
            actionEnd = chatDisplay.length();
        }

        chatDisplay.append("\n");

        int descriptionStart = chatDisplay.length();
        chatDisplay.append(buildStateChangeDescription(change));
        int descriptionEnd = chatDisplay.length();

        chatDisplay.append("\n\u200A");
        int cardEnd = chatDisplay.length();
        chatDisplay.append("\n\n");

        chatDisplay.setSpan(
                new StateCardSpan(
                        backgroundColor,
                        accentColor,
                        COLOR_CARD_BORDER,
                        dp(12),
                        dp(4),
                        dp(1)
                ),
                cardStart,
                cardEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );

        chatDisplay.setSpan(
                new ForegroundColorSpan(
                        reverted
                                ? COLOR_CARD_REVERTED_TEXT
                                : accentColor
                ),
                titleStart,
                titleEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );

        chatDisplay.setSpan(
                new StyleSpan(Typeface.BOLD),
                titleStart,
                titleEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );

        chatDisplay.setSpan(
                new RelativeSizeSpan(0.86f),
                titleStart,
                titleEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );

        chatDisplay.setSpan(
                new ForegroundColorSpan(
                        reverted
                                ? COLOR_CARD_REVERTED_TEXT
                                : COLOR_MASTER
                ),
                descriptionStart,
                descriptionEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );

        chatDisplay.setSpan(
                new RelativeSizeSpan(0.96f),
                descriptionStart,
                descriptionEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );

        if (actionStart >= 0 && actionEnd > actionStart) {
            if (reverted) {
                chatDisplay.setSpan(
                        new ForegroundColorSpan(COLOR_CARD_REVERTED_TEXT),
                        actionStart,
                        actionEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                );
                chatDisplay.setSpan(
                        new RelativeSizeSpan(0.76f),
                        actionStart,
                        actionEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                );
            } else {
                final long changeId = change.id;
                final boolean diceAction =
                        StateChangeRepository.TYPE_DICE_CHECK.equals(change.type);

                chatDisplay.setSpan(
                        new ClickableSpan() {
                            @Override
                            public void onClick(View widget) {
                                if (diceAction) {
                                    openDiceCheck(changeId);
                                } else {
                                    undoStateChange(changeId);
                                }
                            }

                            @Override
                            public void updateDrawState(TextPaint ds) {
                                ds.setColor(COLOR_CARD_BORDER);
                                ds.setUnderlineText(false);
                                ds.setFakeBoldText(true);
                            }
                        },
                        actionStart,
                        actionEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                );

                chatDisplay.setSpan(
                        new RelativeSizeSpan(
                                diceAction ? 0.82f : 1.05f
                        ),
                        actionStart,
                        actionEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                );
            }
        }
    }


    private String stateChangeMark(String type) {
        if (StateChangeRepository.TYPE_INVENTORY_ADD.equals(type)) {
            return "+";
        }

        if (StateChangeRepository.TYPE_INVENTORY_REMOVE.equals(type)) {
            return "−";
        }

        if (StateChangeRepository.TYPE_DICE_CHECK.equals(type)) {
            return "◆";
        }

        if (StateChangeRepository.TYPE_WORLD_EVENT.equals(type)
                || StateChangeRepository.TYPE_WORLD_EVENT_UPDATE.equals(type)
                || StateChangeRepository.TYPE_WORLD_EVENT_RESOLVE.equals(type)) {
            return "✦";
        }

        if (StateChangeRepository.TYPE_CHARACTER_DEAD.equals(type)) {
            return "†";
        }

        if (StateChangeRepository.TYPE_HEALTH_DAMAGE.equals(type)
                || StateChangeRepository.TYPE_HEALTH_HEAL.equals(type)
                || StateChangeRepository.TYPE_CHARACTER_DOWNED.equals(type)
                || StateChangeRepository.TYPE_DEATH_SAVE_SUCCESS.equals(type)
                || StateChangeRepository.TYPE_DEATH_SAVE_FAILURE.equals(type)
                || StateChangeRepository.TYPE_CHARACTER_STABLE.equals(type)
                || StateChangeRepository.TYPE_CHARACTER_REVIVED.equals(type)) {
            return "♥";
        }

        if (StateChangeRepository.TYPE_MONEY_GAIN.equals(type)
                || StateChangeRepository.TYPE_MONEY_SPEND.equals(type)) {
            return "¤";
        }

        if (StateChangeRepository.TYPE_QUEST_START.equals(type)
                || StateChangeRepository.TYPE_QUEST_UPDATE.equals(type)
                || StateChangeRepository.TYPE_QUEST_COMPLETE.equals(type)
                || StateChangeRepository.TYPE_QUEST_FAIL.equals(type)) {
            return "◆";
        }

        if (StateChangeRepository.TYPE_ABILITY_ADD.equals(type)
                || StateChangeRepository.TYPE_ABILITY_UPDATE.equals(type)
                || StateChangeRepository.TYPE_ABILITY_REMOVE.equals(type)) {
            return "✧";
        }

        if (StateChangeRepository.TYPE_EFFECT_ADD.equals(type)
                || StateChangeRepository.TYPE_EFFECT_REMOVE.equals(type)) {
            return "○";
        }

        if (StateChangeRepository.TYPE_LOCATION.equals(type)) {
            return "⌖";
        }

        return "◇";
    }


    private static final class StateCardSpan
            implements LineBackgroundSpan, LeadingMarginSpan {

        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int leadingMargin;
        private final int accentWidth;

        private StateCardSpan(
                int backgroundColor,
                int accentColor,
                int borderColor,
                int leadingMargin,
                int accentWidth,
                int borderWidth
        ) {
            this.leadingMargin = leadingMargin;
            this.accentWidth = accentWidth;

            backgroundPaint.setStyle(Paint.Style.FILL);
            backgroundPaint.setColor(backgroundColor);

            accentPaint.setStyle(Paint.Style.FILL);
            accentPaint.setColor(accentColor);

            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(borderWidth);
            borderPaint.setColor(borderColor);
            borderPaint.setAlpha(110);
        }

        @Override
        public int getLeadingMargin(boolean first) {
            return leadingMargin;
        }

        @Override
        public void drawLeadingMargin(
                Canvas canvas,
                Paint paint,
                int x,
                int dir,
                int top,
                int baseline,
                int bottom,
                CharSequence text,
                int start,
                int end,
                boolean first,
                android.text.Layout layout
        ) {
            // Отступ создаёт место между левой полосой и текстом.
        }

        @Override
        public void drawBackground(
                Canvas canvas,
                Paint paint,
                int left,
                int right,
                int top,
                int baseline,
                int bottom,
                CharSequence text,
                int start,
                int end,
                int lineNumber
        ) {
            int cardLeft = left + 2;
            int cardRight = right - 2;

            canvas.drawRect(
                    cardLeft,
                    top,
                    cardRight,
                    bottom,
                    backgroundPaint
            );

            canvas.drawRect(
                    cardLeft,
                    top,
                    cardLeft + accentWidth,
                    bottom,
                    accentPaint
            );

            canvas.drawLine(
                    cardRight,
                    top,
                    cardRight,
                    bottom,
                    borderPaint
            );

            if (text instanceof Spanned) {
                Spanned spanned = (Spanned) text;
                int spanStart = spanned.getSpanStart(this);
                int spanEnd = spanned.getSpanEnd(this);

                boolean firstLine = spanStart >= start && spanStart < end;
                boolean lastLine = spanEnd > start && spanEnd <= end;

                if (firstLine) {
                    canvas.drawLine(
                            cardLeft,
                            top,
                            cardRight,
                            top,
                            borderPaint
                    );
                }

                if (lastLine) {
                    canvas.drawLine(
                            cardLeft,
                            bottom,
                            cardRight,
                            bottom,
                            borderPaint
                    );
                }
            }
        }
    }


    private String buildStateChangeDescription(StateChangeEntity change) {
        if (StateChangeRepository.TYPE_DICE_CHECK.equals(change.type)) {
            String attribute = displayAttribute(change.subjectName);

            if (StateChangeRepository.STATUS_RESOLVED.equals(change.status)) {
                return attribute
                        + " • d20: "
                        + change.afterNumber
                        + " • СЛ "
                        + change.beforeNumber
                        + " • "
                        + change.afterText;
            }

            String reason = change.description == null
                    ? ""
                    : change.description.trim();

            return attribute
                    + " • СЛ "
                    + change.beforeNumber
                    + (reason.isEmpty() ? "" : " • " + reason);
        }

        return change.description == null
                ? ""
                : change.description;
    }




    private int stateChangeColor(String type) {
        if (StateChangeRepository.TYPE_NPC_MEMORY_GOOD.equals(type)
                || StateChangeRepository.TYPE_HEALTH_HEAL.equals(type)
                || StateChangeRepository.TYPE_MONEY_GAIN.equals(type)
                || StateChangeRepository.TYPE_QUEST_COMPLETE.equals(type)
                || StateChangeRepository.TYPE_DEATH_SAVE_SUCCESS.equals(type)
                || StateChangeRepository.TYPE_CHARACTER_STABLE.equals(type)
                || StateChangeRepository.TYPE_CHARACTER_REVIVED.equals(type)) {
            return COLOR_CARD_GOOD;
        }

        if (StateChangeRepository.TYPE_NPC_MEMORY_BAD.equals(type)
                || StateChangeRepository.TYPE_HEALTH_DAMAGE.equals(type)
                || StateChangeRepository.TYPE_MONEY_SPEND.equals(type)
                || StateChangeRepository.TYPE_QUEST_FAIL.equals(type)
                || StateChangeRepository.TYPE_CHARACTER_DOWNED.equals(type)
                || StateChangeRepository.TYPE_DEATH_SAVE_FAILURE.equals(type)
                || StateChangeRepository.TYPE_CHARACTER_DEAD.equals(type)) {
            return COLOR_CARD_BAD;
        }

        return COLOR_CARD_NEUTRAL;
    }


    private void undoStateChange(long changeId) {
        if (generationInProgress
                || (modelManager != null && modelManager.isBusy())) {
            Toast.makeText(
                    this,
                    "Дождитесь завершения текущего хода.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        DbExecutor.execute(() -> {
            StateChangeEntity originalChange =
                    stateChangeRepository.get(changeId);

            StateChangeRepository.UndoResult result =
                    stateChangeRepository.undo(changeId);

            if (result.isSuccess() && originalChange != null) {
                saveUndoCorrectionForPrompt(originalChange);

                Log.d(
                        "MyDND_STATE_CHANGE",
                        "UNDO APPLIED | type="
                                + originalChange.type
                                + " | subject="
                                + originalChange.subjectName
                );
            }

            runOnUiThread(() -> {
                Toast.makeText(
                        MainActivity.this,
                        result.getMessage(),
                        Toast.LENGTH_SHORT
                ).show();

                if (result.isSuccess()) {
                    reloadChatTimeline();
                }
            });
        });
    }


    private void saveUndoCorrectionForPrompt(
            StateChangeEntity change
    ) {
        String correction = buildUndoCorrection(change);

        if (correction.isEmpty()) {
            return;
        }

        GameEventEntity entity = new GameEventEntity();
        entity.campaignId = change.campaignId;
        entity.speaker = GameEvent.Speaker.SYSTEM.name();
        entity.text = correction;
        entity.includeInPrompt = true;
        entity.createdAt = System.currentTimeMillis();

        database.gameEventDao().insert(entity);
    }


    private String buildUndoCorrection(
            StateChangeEntity change
    ) {
        String subject = change.subjectName == null
                ? ""
                : change.subjectName.trim();

        if (StateChangeRepository.TYPE_INVENTORY_ADD.equals(change.type)) {
            return "Исправление состояния: получение предмета «"
                    + subject
                    + "» отменено пользователем. Предмета «"
                    + subject
                    + "» нет в инвентаре. INVENTORY BEFORE является истиной.";
        }

        if (StateChangeRepository.TYPE_INVENTORY_REMOVE.equals(change.type)) {
            return "Исправление состояния: потеря предмета «"
                    + subject
                    + "» отменена пользователем. Предмет «"
                    + subject
                    + "» снова находится в инвентаре. INVENTORY BEFORE является истиной.";
        }

        if (StateChangeRepository.TYPE_NPC_MEMORY_GOOD.equals(change.type)
                || StateChangeRepository.TYPE_NPC_MEMORY_BAD.equals(change.type)
                || StateChangeRepository.TYPE_NPC_MEMORY_NEUTRAL.equals(change.type)) {
            String fact = change.description == null
                    ? ""
                    : change.description.trim();

            return "Исправление состояния: память NPC «"
                    + subject
                    + "» о факте «"
                    + fact
                    + "» отменена пользователем. Этот факт не считается известным NPC. "
                    + "Текущее состояние ACTIVE_NPCS является истиной.";
        }

        if (StateChangeRepository.TYPE_HEALTH_DAMAGE.equals(change.type)
                || StateChangeRepository.TYPE_HEALTH_HEAL.equals(change.type)) {
            return "Исправление состояния: изменение HP у «"
                    + subject
                    + "» отменено пользователем. HP восстановлено к значению до этого изменения. "
                    + "STATE BEFORE является истиной.";
        }

        if (StateChangeRepository.TYPE_LOCATION.equals(change.type)) {
            String previousLocation = change.beforeText == null
                    ? ""
                    : change.beforeText.trim();

            return "Исправление состояния: смена локации на «"
                    + subject
                    + "» отменена пользователем. Текущая локация снова «"
                    + previousLocation
                    + "». STATE BEFORE является истиной.";
        }

        return "";
    }


    private void reloadChatTimeline() {
        if (currentCampaignId <= 0L) {
            return;
        }

        final long campaignId = currentCampaignId;

        DbExecutor.execute(() -> {
            List<GameEventEntity> events =
                    database.gameEventDao().getEventsForCampaign(campaignId);

            List<StateChangeEntity> changes =
                    stateChangeRepository.getForCampaign(campaignId);

            runOnUiThread(() -> {
                if (currentCampaignId != campaignId || generationInProgress) {
                    return;
                }

                chatDisplay.clear();
                chatDisplay.clearSpans();
                gameEvents.clear();

                renderLoadedTimeline(events, changes);
                updateChat();
            });
        });
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

            List<StateChangeEntity> savedChanges =
                    stateChangeRepository.getForCampaign(currentCampaignId);

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
                chatAutoFollowEnabled = true;
                chatDisplay.clear();
                chatDisplay.clearSpans();
                gameEvents.clear();

                if ((savedEvents == null || savedEvents.isEmpty())
                        && (savedChanges == null || savedChanges.isEmpty())) {
                    startFirstScene();
                    refreshCharacterLifeUi();
                    return;
                }

                renderLoadedTimeline(
                        savedEvents,
                        savedChanges
                );

                updateChat();
                refreshCharacterLifeUi();
            });
        });
    }

    private void renderLoadedTimeline(
            List<GameEventEntity> events,
            List<StateChangeEntity> changes
    ) {
        List<GameEventEntity> safeEvents =
                events == null ? Collections.emptyList() : events;

        List<StateChangeEntity> safeChanges =
                changes == null ? Collections.emptyList() : changes;

        int eventIndex = 0;
        int changeIndex = 0;

        while (eventIndex < safeEvents.size()
                || changeIndex < safeChanges.size()) {

            boolean useChange;

            if (eventIndex >= safeEvents.size()) {
                useChange = true;
            } else if (changeIndex >= safeChanges.size()) {
                useChange = false;
            } else {
                useChange = safeChanges.get(changeIndex).createdAt
                        <= safeEvents.get(eventIndex).createdAt;
            }

            if (useChange) {
                appendStateChangeCard(
                        safeChanges.get(changeIndex)
                );
                changeIndex++;
            } else {
                GameEvent event = toGameEvent(
                        safeEvents.get(eventIndex)
                );
                gameEvents.add(event);
                renderLoadedEvent(event);
                eventIndex++;
            }
        }
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
    private void processDirectorAndGenerate(
            String playerText
    ) {
        if (currentCampaignId <= 0L) {
            finishOnePassPreparationError(
                    new IllegalStateException("campaignId <= 0")
            );
            return;
        }

        final long campaignId = currentCampaignId;

        DbExecutor.execute(() -> {
            try {
                List<String> inventoryBefore =
                        inventoryRepository.getItemNames(campaignId);

                MemoryContext memoryContext =
                        campaignMemory.buildContext(campaignId, playerText);

                CampaignPromptState campaignState =
                        campaignPromptRepository.build(campaignId);

                InventoryActionHintResolver.Result actionHint =
                        inventoryActionHintResolver.resolve(
                                playerText,
                                inventoryBefore
                        );

                DirectorPromptState directorState =
                        directorPromptStateRepository.build(
                                campaignId,
                                actionHint.getPromptValue()
                        );

                int masterEventCount =
                        database.gameEventDao().countEventsBySpeaker(
                                campaignId,
                                "MASTER"
                        );

                /*
                 * Rare autonomous events are scheduled only by
                 * WorldMaintenanceService (12-25 world turns). The old
                 * every-5-master-turn native world-event phase is disabled so
                 * ordinary PLAYER_ACTION turns cannot invent background events.
                 */
                boolean runWorldEventPhase = false;
                String worldEventBatchText = "";

                Log.d(
                        "MyDND_DIRECTOR",
                        "ACTION_HINT=" + actionHint.getPromptValue()
                                + " | reason=" + actionHint.getReason()
                );

                String rawPrompt =
                        promptBuilder.buildDirectorToolAwarePrompt(
                                playerText,
                                memoryContext,
                                directorState,
                                campaignState
                        );

                String preparedPrompt = prepareMasterPrompt(rawPrompt);

                Log.d(
                        "MyDND_DIRECTOR",
                        "START campaignId=" + campaignId
                                + " | promptChars=" + preparedPrompt.length()
                                + " | masterEventsBefore=" + masterEventCount
                                + " | worldEventCheck=" + runWorldEventPhase
                );

                Log.d(
                        "MyDND_DIRECTOR_PROMPT",
                        "\n" + preparedPrompt
                );

                startDirectorGeneration(
                        preparedPrompt,
                        campaignId,
                        runWorldEventPhase,
                        worldEventBatchText,
                        actionHint.getPromptValue(),
                        playerText
                );

            } catch (Throwable throwable) {
                finishOnePassPreparationError(throwable);
            }
        });
    }


    private void startDirectorGeneration(
            String preparedPrompt,
            long campaignId,
            boolean runWorldEventPhase,
            String worldEventBatchText,
            String actionHint,
            String playerText
    ) {
        masterStreamingStartPosition = -1;
        pendingDirectorCheckId = 0L;
        pendingDirectorCheckCampaignId = 0L;
        activeDirectorCampaignId = campaignId;
        directorTurnActive = true;
        directorFlowController.startTurn(
                DirectorMode.PLAYER_ACTION,
                actionHint,
                playerText
        );

        modelManager.generateDirectorAware(
                ModelRole.MASTER,
                preparedPrompt,
                DirectorMode.PLAYER_ACTION.name(),
                generationProfile,
                runWorldEventPhase,
                worldEventBatchText,
                new NativeToolCallback() {
                    @Override
                    public String onToolCall(String rawToolCall) {
                        return executeDirectorToolCallForContinuation(
                                campaignId,
                                rawToolCall
                        );
                    }
                },
                createMasterGenerationCallback()
        );
    }


    /**
     * Runs synchronously on the native generation thread. Every structural
     * mutation goes through the current Director mode; autonomous world events
     * use WorldMaintenanceService and the RANDOM_WORLD_EVENT mode separately.
     */
    private String executeDirectorToolCallForContinuation(
            long campaignId,
            String rawToolCall
    ) {
        Log.d(
                "MyDND_DIRECTOR",
                "TOOL RAW:\n" + rawToolCall
        );

        try {
            return directorFlowController.onToolCall(
                    campaignId,
                    rawToolCall
            );

        } catch (Throwable throwable) {
            Log.e(
                    "MyDND_DIRECTOR",
                    "Director tool execution failed",
                    throwable
            );

            return "<|tool_response>response:director_action{status:<|\"|>REJECTED<|\"|>,"
                    + "code:<|\"|>JAVA_DIRECTOR_EXCEPTION<|\"|>,"
                    + "state_after:<|\"|><|\"|>,"
                    + "next:<|\"|>DIRECT_OR_DONE<|\"|>}<tool_response|>";
        }
    }


    private void onDirectorResult(DirectorResult result) {
        if (result == null) {
            return;
        }

        Log.d(
                "MyDND_DIRECTOR",
                "RESULT status=" + result.getStatus()
                        + " | type=" + result.getAction().getType().getToolCode()
                        + " | code=" + result.getCode()
                        + " | cardId=" + result.getStateChangeId()
                        + " | after=" + result.getStateAfter()
        );

        if ("CHECK_REQUESTED".equals(result.getCode())
                && result.getStateChangeId() > 0L) {
            pendingDirectorCheckId = result.getStateChangeId();
            pendingDirectorCheckCampaignId = activeDirectorCampaignId;

            Log.d(
                    "MyDND_DICE_FLOW",
                    "CHECK PAUSE ARMED | changeId=" + pendingDirectorCheckId
                            + " | campaignId=" + pendingDirectorCheckCampaignId
            );
        }

        if (result.getStateChangeId() <= 0L) {
            return;
        }

        StateChangeEntity change =
                stateChangeRepository.get(result.getStateChangeId());

        long campaignId = activeDirectorCampaignId;

        if (campaignId <= 0L) {
            return;
        }

        showStateChangeBeforeNarrative(
                campaignId,
                change
        );
    }


    private void finishDirectorCheckPause() {
        removeThinkingIndicator();
        flushStreamingTokens();

        generationInProgress = false;
        directorTurnActive = false;
        activeDirectorCampaignId = 0L;
        generationCancelledByUser = false;
        masterStreamingStarted = false;
        masterStreamingStartPosition = -1;

        final long changeId = pendingDirectorCheckId;
        final long campaignId = pendingDirectorCheckCampaignId;

        Log.d(
                "MyDND_DICE_FLOW",
                "GENERATION PAUSED FOR CHECK | changeId=" + changeId
                        + " | campaignId=" + campaignId
        );

        updateChat();

        if (changeId <= 0L
                || campaignId <= 0L
                || currentCampaignId != campaignId) {
            sendButton.setEnabled(true);
            sendButton.setText("Отправить");
            return;
        }

        sendButton.setEnabled(false);
        sendButton.setText("Бросьте d20");
        openDiceCheck(changeId);
    }


    private void continueAfterResolvedDiceCheck(
            long campaignId,
            StateChangeEntity resolvedCheck,
            int roll,
            boolean success
    ) {
        if (campaignId <= 0L
                || resolvedCheck == null
                || currentCampaignId != campaignId
                || generationInProgress
                || modelManager.isBusy()) {
            return;
        }

        pendingDirectorCheckId = 0L;
        pendingDirectorCheckCampaignId = 0L;
        generationCancelledByUser = false;
        generationInProgress = true;
        diceContinuationTurnActive = true;
        masterStreamingStarted = false;
        masterStreamingStartPosition = -1;

        sendButton.setEnabled(true);
        sendButton.setText("Стоп");
        showThinkingIndicator();

        final long startedAt = System.currentTimeMillis();

        Log.d(
                "MyDND_DICE_FLOW",
                "CONTINUATION START | campaignId=" + campaignId
                        + " | roll=" + roll
                        + " | success=" + success
        );

        DbExecutor.execute(() -> {
            try {
                CampaignPromptState campaignState =
                        campaignPromptRepository.build(campaignId);

                final String prompt;
                if (success) {
                    prompt = promptBuilder.buildFastDiceSuccessNarrativePrompt(
                            campaignState,
                            resolvedCheck.subjectName,
                            resolvedCheck.beforeNumber,
                            resolvedCheck.description,
                            roll
                    );
                } else {
                    DirectorPromptState directorState =
                            directorPromptStateRepository.build(campaignId, "NONE");

                    prompt = promptBuilder.buildFastDiceContinuationDirectorPrompt(
                            directorState,
                            campaignState,
                            resolvedCheck.subjectName,
                            resolvedCheck.beforeNumber,
                            resolvedCheck.description,
                            roll,
                            false
                    );
                }

                Log.d(
                        "MyDND_DICE_FLOW",
                        (success
                                ? "SUCCESS NARRATIVE PROMPT | chars="
                                : "FAILURE DIRECTOR PROMPT | chars=")
                                + prompt.length()
                );

                runOnUiThread(() -> {
                    if (currentCampaignId != campaignId) {
                        generationInProgress = false;
                        diceContinuationTurnActive = false;
                        removeThinkingIndicator();
                        refreshCharacterLifeUi();
                        return;
                    }

                    Log.d(
                            "MyDND_DICE_FLOW",
                            "CONTINUATION GENERATE | prepareMs="
                                    + (System.currentTimeMillis() - startedAt)
                                    + " | success=" + success
                    );

                    if (success) {
                        startDiceSuccessNarrativeGeneration(prompt);
                    } else {
                        startDiceContinuationGeneration(prompt);
                    }
                });

            } catch (Throwable throwable) {
                Log.e(
                        "MyDND_DICE_FLOW",
                        "Failed to build fast dice continuation",
                        throwable
                );

                runOnUiThread(() -> {
                    generationInProgress = false;
                    diceContinuationTurnActive = false;
                    removeThinkingIndicator();
                    refreshCharacterLifeUi();
                });
            }
        });
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

            directorTurnActive =
                    false;

            activeDirectorCampaignId =
                    0L;

            generationCancelledByUser =
                    false;

            masterStreamingStarted =
                    false;

            refreshCharacterLifeUi();
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


    private void showAboutDialog() {
        showFantasyTextDialog(
                "СПРАВКА / ОБ ИГРЕ",
                buildAboutText(),
                0.88f
        );
    }


    private String buildAboutText() {
        return "POCKET D&D\n\n"
                + "Локальный RPG-мастер, работающий прямо на телефоне. "
                + "Игра не требует интернета: модель и данные кампании находятся на устройстве.\n\n"

                + "КАК ИГРАТЬ\n"
                + "Просто описывайте действия героя обычным языком. "
                + "Мастер продолжит сцену и отыграет мир и NPC.\n\n"

                + "ПРЕДМЕТЫ\n"
                + "Для более точного действия с предметом можно выделить его название звёздочками.\n"
                + "Пример: Я беру *железный ключ*.\n"
                + "Пример: Я выбрасываю *старый меч*.\n\n"

                + "ПЛАШКИ СОСТОЯНИЯ\n"
                + "Важные изменения показываются отдельными плашками. "
                + "Если мастер ошибся, безопасное изменение можно отменить кнопкой ↶. "
                + "Отменённая плашка станет серой и останется в истории.\n\n"

                + "NPC\n"
                + "NPC могут запоминать важные поступки. Игра покажет, что именно NPC запомнил, "
                + "но его числовое отношение к герою остаётся скрытым.\n\n"

                + "ПРОВЕРКИ И КУБИКИ\n"
                + "Если действие рискованное, мастер может запросить проверку. "
                + "Нажмите «БРОСИТЬ d20» прямо на плашке. Любой кубик можно бросить вручную кнопкой «Кубик».\n\n"

                + "ЖИВОЙ МИР\n"
                + "Мир может меняться независимо от текущего героя. Важные последствия сохраняются между играми в одной истории мира.\n\n"

                + "О ЛОКАЛЬНОМ ИИ\n"
                + "Нейросеть может ошибаться. Поэтому каноническое состояние хранит Java/Room, "
                + "а важные изменения делаются видимыми и, где это безопасно, отменяемыми.\n\n"

                + "Автор: Поникаров Артём\n"
                + "enhort@gmail.com";
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
        Button backgroundButton =
                dialog.findViewById(R.id.settingsBackgroundButton);
        backgroundButton.setText(
                "ФОН ИГРЫ: "
                        + GameBackgroundManager.getSelectedBackgroundTitle(this)
        );

        backgroundButton.setOnClickListener(v ->
                showBackgroundChooser(backgroundButton)
        );

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

        Button benchmarkButton =
                dialog.findViewById(R.id.settingsBenchmarkButton);

        Button resetDataButton =
                dialog.findViewById(R.id.settingsResetDataButton);

        Button closeButton =
                dialog.findViewById(R.id.settingsCloseButton);

        benchmarkButton.setOnClickListener(v -> {
            if (generationInProgress
                    || modelManager.isBusy()
                    || behaviorTestInProgress) {
                Toast.makeText(
                        this,
                        "Сначала завершите текущую генерацию.",
                        Toast.LENGTH_SHORT
                ).show();
                return;
            }

            dialog.dismiss();
            runBehaviorTestSuite();
        });

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


    private void runBehaviorTestSuite() {
        if (behaviorTestInProgress
                || generationInProgress
                || modelManager.isBusy()) {
            return;
        }

        List<BehaviorTestCase> cases = BehaviorTestDataset.create();
        if (cases.isEmpty()) {
            return;
        }

        behaviorTestInProgress = true;
        generationInProgress = true;
        generationCancelledByUser = false;

        sendButton.setEnabled(false);
        sendButton.setText("Тест 1/" + cases.size() + "...");

        long suiteStartedAt = System.currentTimeMillis();

        Log.i(
                TAG_BEHAVIOR_TEST,
                "SUITE START | cases=" + cases.size()
                        + " | model=" + MASTER_MODEL_FILE
                        + " | database=UNTOUCHED"
        );

        runBehaviorTestCase(
                cases,
                0,
                suiteStartedAt
        );
    }


    private void runBehaviorTestCase(
            List<BehaviorTestCase> cases,
            int index,
            long suiteStartedAt
    ) {
        if (cases == null || index >= cases.size()) {
            finishBehaviorTestSuite(
                    true,
                    suiteStartedAt,
                    null
            );
            return;
        }

        final BehaviorTestCase testCase = cases.get(index);

        final String rawPrompt = testCase.getMode() == DirectorMode.CHECK_RESULT
                ? promptBuilder.buildDiceContinuationDirectorPrompt(
                        testCase.getMemoryContext(),
                        testCase.getState(),
                        BehaviorTestDataset.campaignState()
                )
                : promptBuilder.buildDirectorToolAwarePrompt(
                        testCase.getPlayerAction(),
                        testCase.getMemoryContext(),
                        testCase.getState(),
                        BehaviorTestDataset.campaignState()
                );

        final String preparedPrompt = prepareMasterPrompt(rawPrompt);
        final List<String> actions = Collections.synchronizedList(new ArrayList<>());
        final Set<String> seenFingerprints = Collections.synchronizedSet(new HashSet<>());
        final int[] toolNumber = {0};
        final long caseStartedAt = System.currentTimeMillis();

        runOnUiThread(() -> {
            sendButton.setEnabled(false);
            sendButton.setText("Тест " + (index + 1) + "/" + cases.size() + "...");
        });

        Log.i(
                TAG_BEHAVIOR_TEST,
                "CASE START | index=" + (index + 1)
                        + " | id=" + testCase.getId()
                        + " | mode=" + testCase.getMode()
                        + " | promptChars=" + preparedPrompt.length()
        );
        Log.i(
                TAG_BEHAVIOR_TEST,
                "INPUT | id=" + testCase.getId()
                        + " | text=" + compactBehaviorLog(testCase.getPlayerAction())
        );
        Log.i(
                TAG_BEHAVIOR_TEST,
                "EXPECT | id=" + testCase.getId()
                        + " | " + testCase.expectedSummary()
        );

        modelManager.generateDirectorAware(
                ModelRole.MASTER,
                preparedPrompt,
                testCase.getMode().name(),
                GenerationProfile.fast(),
                false,
                "",
                rawToolCall -> {
                    toolNumber[0]++;

                    try {
                        DirectorAction action = new DirectorActionParser().parse(rawToolCall);
                        String toolCode = action.getType().getToolCode();
                        actions.add(toolCode);

                        String fingerprint = toolCode
                                + "\u001F" + action.getName()
                                + "\u001F" + action.getValue()
                                + "\u001F" + action.getDetails();

                        boolean duplicate = !seenFingerprints.add(fingerprint);

                        Log.i(
                                TAG_BEHAVIOR_TEST,
                                "TOOL | id=" + testCase.getId()
                                        + " | #=" + toolNumber[0]
                                        + " | type=" + toolCode
                                        + " | name=" + compactBehaviorLog(action.getName())
                                        + " | value=" + compactBehaviorLog(action.getValue())
                                        + " | details=" + compactBehaviorLog(action.getDetails())
                                        + (duplicate ? " | duplicate=YES" : "")
                        );

                        if (duplicate) {
                            return buildBehaviorToolResponse(
                                    "REJECTED",
                                    "DUPLICATE_ACTION_IN_TEST",
                                    "",
                                    false,
                                    false
                            );
                        }

                        if (action.getType() == DirectorActionType.NO_CHANGE) {
                            return buildBehaviorToolResponse(
                                    "NO_CHANGE",
                                    "NO_CHANGE",
                                    "",
                                    false,
                                    false
                            );
                        }

                        if (action.getType() == DirectorActionType.CHECK_REQUEST) {
                            return buildBehaviorToolResponse(
                                    "APPLIED",
                                    "CHECK_REQUESTED",
                                    action.getName() + " DC " + action.getValue(),
                                    true,
                                    false
                            );
                        }

                        return buildBehaviorToolResponse(
                                "APPLIED",
                                "TEST_APPLIED",
                                toolCode + " " + action.getName() + " " + action.getValue(),
                                false,
                                testCase.getMode() == DirectorMode.CHECK_RESULT
                        );

                    } catch (Throwable throwable) {
                        Log.e(
                                TAG_BEHAVIOR_TEST,
                                "TOOL PARSE ERROR | id=" + testCase.getId(),
                                throwable
                        );

                        return buildBehaviorToolResponse(
                                "REJECTED",
                                "TEST_PARSE_ERROR",
                                "",
                                false,
                                false
                        );
                    }
                },
                new LlmCallback() {
                    @Override
                    public void onToken(String token) {
                        // Behaviour test stays out of the visible chat.
                    }

                    @Override
                    public void onComplete(String fullText) {
                        long totalMs = System.currentTimeMillis() - caseStartedAt;
                        boolean passed = behaviorCasePassed(testCase, actions);

                        Log.i(
                                TAG_BEHAVIOR_TEST,
                                "CASE END | index=" + (index + 1)
                                        + " | id=" + testCase.getId()
                                        + " | pass=" + (passed ? "YES" : "NO")
                                        + " | totalMs=" + totalMs
                                        + " | actions=" + actions
                        );

                        String narrative = fullText == null
                                ? ""
                                : fullText;

                        if (DIRECTOR_CHECK_PENDING_MARKER.equals(narrative.trim())) {
                            narrative = "[PAUSED_FOR_CHECK]";
                        }

                        Log.d(
                                TAG_BEHAVIOR_TEST,
                                "NARRATIVE | id=" + testCase.getId()
                                        + " | text=" + compactBehaviorLog(narrative)
                        );

                        runBehaviorTestCase(
                                cases,
                                index + 1,
                                suiteStartedAt
                        );
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        Log.e(
                                TAG_BEHAVIOR_TEST,
                                "CASE ERROR | index=" + (index + 1)
                                        + " | id=" + testCase.getId(),
                                throwable
                        );

                        finishBehaviorTestSuite(
                                false,
                                suiteStartedAt,
                                throwable
                        );
                    }
                }
        );
    }


    private boolean behaviorCasePassed(
            BehaviorTestCase testCase,
            List<String> actions
    ) {
        List<String> safeActions = actions == null
                ? Collections.emptyList()
                : new ArrayList<>(actions);

        if (testCase.isOnlyDoneExpected()) {
            return safeActions.size() == 1
                    && "DONE".equals(safeActions.get(0));
        }

        if (!safeActions.containsAll(testCase.getRequiredAll())) {
            return false;
        }

        if (!testCase.getRequiredAny().isEmpty()) {
            boolean anyFound = false;
            for (String expected : testCase.getRequiredAny()) {
                if (safeActions.contains(expected)) {
                    anyFound = true;
                    break;
                }
            }
            if (!anyFound) {
                return false;
            }
        }

        for (String forbidden : testCase.getForbiddenAny()) {
            if (safeActions.contains(forbidden)) {
                return false;
            }
        }

        return true;
    }


    private String buildBehaviorToolResponse(
            String status,
            String code,
            String stateAfter,
            boolean checkRequested,
            boolean forceDoneNext
    ) {
        StringBuilder response = new StringBuilder();
        response.append("<|tool_response>response:director_action{")
                .append("status:<|\"|>").append(safeBehaviorToolValue(status)).append("<|\"|>,")
                .append("code:<|\"|>").append(safeBehaviorToolValue(code)).append("<|\"|>,")
                .append("state_after:<|\"|>").append(safeBehaviorToolValue(stateAfter)).append("<|\"|>");

        if ("APPLIED".equals(status) && !checkRequested) {
            response.append(forceDoneNext
                    ? ",next:<|\"|>DONE_ONLY<|\"|>"
                    : ",next:<|\"|>DIRECT_OR_DONE<|\"|>");
        } else if ("REJECTED".equals(status)) {
            response.append(",next:<|\"|>DIRECT_FIX_OR_DONE<|\"|>");
        }

        response.append("}<tool_response|>");
        return response.toString();
    }


    private String safeBehaviorToolValue(String value) {
        if (value == null) {
            return "";
        }

        String safe = value
                .replace("<", "")
                .replace(">", "")
                .replace("{", "(")
                .replace("}", ")")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim();

        return safe.length() <= 160
                ? safe
                : safe.substring(0, 160).trim();
    }


    private String compactBehaviorLog(String value) {
        if (value == null) {
            return "";
        }

        String compact = value
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();

        return compact.length() <= 260
                ? compact
                : compact.substring(0, 260) + "...";
    }


    private void finishBehaviorTestSuite(
            boolean success,
            long suiteStartedAt,
            Throwable error
    ) {
        long totalMs = System.currentTimeMillis() - suiteStartedAt;

        Log.i(
                TAG_BEHAVIOR_TEST,
                "SUITE END | success=" + success
                        + " | totalMs=" + totalMs
                        + (error == null ? "" : " | error=" + error.getClass().getSimpleName())
        );

        runOnUiThread(() -> {
            behaviorTestInProgress = false;
            generationInProgress = false;
            generationCancelledByUser = false;
            sendButton.setEnabled(true);
            sendButton.setText("Отправить");

            Toast.makeText(
                    this,
                    success
                            ? "Тест поведения завершён. Лог: MyDND_BEHAVIOR_TEST"
                            : "Тест поведения завершился с ошибкой. Лог: MyDND_BEHAVIOR_TEST",
                    Toast.LENGTH_LONG
            ).show();
        });
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
                    directorTurnActive = false;
                    activeDirectorCampaignId = 0L;
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

        CharacterLifeState lifeState = CharacterLifeState.from(
                character.lifeState,
                character.hp
        );

        text.append("HP: ")
                .append(character.hp)
                .append('/')
                .append(character.maxHp)
                .append("\nСостояние: ")
                .append(lifeStateLabel(lifeState));

        if (lifeState == CharacterLifeState.DOWNED) {
            text.append("\nСпасброски смерти: ")
                    .append(character.deathSaveSuccesses)
                    .append("/3 успехов, ")
                    .append(character.deathSaveFailures)
                    .append("/3 провалов");
        }

        text.append("\nСила: ")
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
                        Log.d(
                                "MyDND_WORLD_MAINT",
                                "Rare world event candidate"
                                        + " | importance=" + importance
                                        + " | tone=" + tone
                                        + " | text=" + text
                        );

                        applyRareWorldEventThroughDirector(
                                campaignId,
                                text,
                                importance,
                                tone
                        );
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


    /**
     * A rare autonomous event is generated by WorldMaintenanceService, but it
     * enters Room and the visible chat only through the Director. This keeps
     * the same invariant as normal turns: no APPLIED DirectorResult -> no card.
     */
    private void applyRareWorldEventThroughDirector(
            long campaignId,
            String rawText,
            int importance,
            String tone
    ) {
        if (campaignId <= 0L || rawText == null || rawText.trim().isEmpty()) {
            restoreSendButtonAfterMaintenance(campaignId);
            return;
        }

        if (currentCampaignId != campaignId) {
            restoreSendButtonAfterMaintenance(campaignId);
            return;
        }

        String eventText = sanitizeDirectorField(rawText, 220);
        String eventName = buildRareWorldEventName(eventText);
        String eventDetails = buildRareWorldEventDetails(eventText, eventName);
        String safeImportance = String.valueOf(Math.max(1, Math.min(3, importance)));

        String rawToolCall =
                "<|tool_call>call:director_action{type:<|\"|>WORLD_ADD<|\"|>,"
                        + "name:<|\"|>" + eventName + "<|\"|>,"
                        + "value:<|\"|>" + safeImportance + "<|\"|>,"
                        + "details:<|\"|>" + eventDetails + "<|\"|>}<tool_call|>";

        activeDirectorCampaignId = campaignId;
        directorFlowController.startTurn(DirectorMode.RANDOM_WORLD_EVENT);

        String response;
        try {
            response = directorFlowController.onToolCall(
                    campaignId,
                    rawToolCall
            );
        } finally {
            activeDirectorCampaignId = 0L;
        }

        boolean applied = response != null
                && response.contains("status:<|\"|>APPLIED<|\"|>");

        Log.d(
                "MyDND_RANDOM_DIRECTOR",
                "mode=RANDOM_WORLD_EVENT"
                        + " | applied=" + applied
                        + " | importance=" + safeImportance
                        + " | tone=" + tone
                        + " | response=" + response
        );

        if (!applied) {
            restoreSendButtonAfterMaintenance(campaignId);
            return;
        }

        runOnUiThread(() -> {
            if (currentCampaignId != campaignId) {
                restoreSendButtonAfterMaintenance(campaignId);
                return;
            }

            GameEvent worldEventMessage = GameEvent.master(eventText);
            gameEvents.add(worldEventMessage);

            appendColoredText(
                    eventText + "\n\n",
                    COLOR_MASTER
            );

            sendButton.setEnabled(false);
            sendButton.setText("Сохраняю событие...");
            updateChat();

            saveEventToDb(
                    worldEventMessage,
                    () -> restoreSendButtonAfterMaintenance(campaignId)
            );
        });
    }


    private String buildRareWorldEventName(String eventText) {
        String safe = sanitizeDirectorField(eventText, 90);
        if (safe.isEmpty()) {
            return "Редкое событие мира";
        }

        int sentenceEnd = safe.indexOf('.');
        if (sentenceEnd >= 18) {
            safe = safe.substring(0, sentenceEnd).trim();
        }

        if (safe.length() > 80) {
            safe = safe.substring(0, 80).trim();
        }

        return safe.isEmpty()
                ? "Редкое событие мира"
                : safe;
    }


    private String buildRareWorldEventDetails(
            String eventText,
            String eventName
    ) {
        String text = sanitizeDirectorField(eventText, 220);
        String name = sanitizeDirectorField(eventName, 90);

        if (!name.isEmpty() && text.startsWith(name)) {
            String remainder = text.substring(name.length()).trim();
            while (remainder.startsWith(".")
                    || remainder.startsWith(":")
                    || remainder.startsWith("—")
                    || remainder.startsWith("-")) {
                remainder = remainder.substring(1).trim();
            }
            if (!remainder.isEmpty()) {
                return remainder;
            }
        }

        return text;
    }


    private String sanitizeDirectorField(String value, int maxChars) {
        if (value == null) {
            return "";
        }

        String safe = value
                .replace('<', '‹')
                .replace('>', '›')
                .replace('{', '(')
                .replace('}', ')')
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim()
                .replaceAll("\\s+", " ");

        return safe.length() <= maxChars
                ? safe
                : safe.substring(0, maxChars).trim();
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
                showDiceRollDialog(selectedSides, null);
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


    private void showDiceRollDialog(
            int sides,
            StateChangeEntity requestedCheck
    ) {
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
        dialog.setCancelable(requestedCheck == null);

        TextView titleText =
                dialog.findViewById(R.id.diceTitleText);

        TextView valueText =
                dialog.findViewById(R.id.diceValueText);

        TextView hintText =
                dialog.findViewById(R.id.diceHintText);

        Button closeButton =
                dialog.findViewById(R.id.diceCloseButton);

        titleText.setText(
                requestedCheck == null
                        ? "БРОСОК D" + sides
                        : "ПРОВЕРКА: " + displayAttribute(requestedCheck.subjectName)
        );
        valueText.setText("—");
        hintText.setText("Кубик катится...");
        closeButton.setEnabled(false);

        final boolean[] checkResolved = {requestedCheck == null};
        final boolean[] continuationStarted = {false};
        final boolean[] resolvedSuccess = {false};

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
                if (requestedCheck == null) {
                    hintText.setText(
                            result.getNatural() == sides
                                    ? "Критический максимум"
                                    : "Результат"
                    );
                } else {
                    boolean success =
                            result.getTotal() >= requestedCheck.beforeNumber;
                    resolvedSuccess[0] = success;

                    hintText.setText(
                            (success ? "УСПЕХ" : "ПРОВАЛ")
                                    + " • СЛ "
                                    + requestedCheck.beforeNumber
                    );

                    final long campaignId = requestedCheck.campaignId;
                    final int total = result.getTotal();

                    DbExecutor.execute(() -> {
                        boolean resolved =
                                stateChangeRepository.resolveDiceCheck(
                                        requestedCheck.id,
                                        total,
                                        success
                                );

                        List<GameEventEntity> refreshedEvents =
                                Collections.emptyList();
                        List<StateChangeEntity> refreshedChanges =
                                Collections.emptyList();

                        if (resolved) {
                            saveDiceResultForPrompt(
                                    campaignId,
                                    requestedCheck,
                                    total,
                                    success
                            );

                            refreshedEvents =
                                    database.gameEventDao().getEventsForCampaign(campaignId);
                            refreshedChanges =
                                    stateChangeRepository.getForCampaign(campaignId);
                        }

                        final List<GameEventEntity> eventsForUi = refreshedEvents;
                        final List<StateChangeEntity> changesForUi = refreshedChanges;

                        runOnUiThread(() -> {
                            if (resolved) {
                                if (currentCampaignId == campaignId
                                        && !generationInProgress) {
                                    chatDisplay.clear();
                                    chatDisplay.clearSpans();
                                    gameEvents.clear();
                                    renderLoadedTimeline(eventsForUi, changesForUi);
                                    updateChat();
                                }

                                checkResolved[0] = true;
                                closeButton.setText("ПРОДОЛЖИТЬ");
                            } else {
                                hintText.setText("Не удалось сохранить результат");
                            }

                            closeButton.setEnabled(true);
                        });
                    });
                }

                if (requestedCheck == null) {
                    closeButton.setEnabled(true);
                }
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

        closeButton.setOnClickListener(v -> {
            if (requestedCheck != null && !checkResolved[0]) {
                return;
            }

            dialog.dismiss();

            if (requestedCheck != null
                    && !continuationStarted[0]) {
                continuationStarted[0] = true;
                continueAfterResolvedDiceCheck(
                        requestedCheck.campaignId,
                        requestedCheck,
                        result.getTotal(),
                        resolvedSuccess[0]
                );
            }
        });

        dialog.setOnShowListener(ignored -> {
            configureFantasyDialogWindow(
                    dialog,
                    0.80f,
                    0f
            );

            uiHandler.post(animation[0]);

            if (requestedCheck == null) {
                runWorldMaintenanceIfDue();
            }
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

    private void startDiceSuccessNarrativeGeneration(
            String prompt
    ) {
        masterStreamingStartPosition = -1;
        activeDirectorCampaignId = currentCampaignId;

        String preparedPrompt = prepareMasterPrompt(prompt);

        Log.d(
                "MyDND_DICE_FLOW",
                "SUCCESS DIRECT NARRATIVE START | promptChars="
                        + preparedPrompt.length()
        );

        modelManager.generate(
                ModelRole.MASTER,
                preparedPrompt,
                GenerationProfile.fast(),
                createMasterGenerationCallback()
        );
    }


    private void startDiceContinuationGeneration(
            String prompt
    ) {
        masterStreamingStartPosition = -1;

        final long campaignId = currentCampaignId;
        activeDirectorCampaignId = campaignId;
        directorFlowController.startTurn(DirectorMode.CHECK_RESULT);

        String preparedPrompt =
                prepareMasterPrompt(prompt);

        Log.d(
                "MyDND_DICE_FLOW",
                "CONTINUATION DIRECTOR START | promptChars="
                        + preparedPrompt.length()
                        + " | mode=CHECK_RESULT"
        );

        modelManager.generateDirectorAware(
                ModelRole.MASTER,
                preparedPrompt,
                DirectorMode.CHECK_RESULT.name(),
                GenerationProfile.fast(),
                false,
                "",
                rawToolCall -> executeDirectorToolCallForContinuation(
                        campaignId,
                        rawToolCall
                ),
                createMasterGenerationCallback()
        );
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

                final boolean isDirectorTurn = directorTurnActive;
                final boolean isDiceContinuationTurn = diceContinuationTurnActive;

                if (isDirectorTurn
                        && DIRECTOR_CHECK_PENDING_MARKER.equals(narrative.trim())) {
                    runOnUiThread(() -> finishDirectorCheckPause());
                    return;
                }

                final NarrativeDirectiveParser.ParseResult directiveResult =
                        narrativeDirectiveParser.parse(narrative);

                for (NarrativeDirectiveParser.Directive directive
                        : directiveResult.getDirectives()) {
                    Log.d(
                            "MyDND_DIRECTIVE",
                            "PARSED | type="
                                    + directive.getType()
                                    + " | subject="
                                    + directive.getSubject()
                                    + " | tone="
                                    + directive.getTone()
                                    + " | difficulty="
                                    + directive.getDifficulty()
                                    + " | text="
                                    + directive.getText()
                    );
                }

                for (String rejectedLine
                        : directiveResult.getRejectedDirectiveLines()) {
                    Log.w(
                            "MyDND_DIRECTIVE",
                            "REJECTED | raw=" + rejectedLine
                    );
                }


                runOnUiThread(() -> {

                    removeThinkingIndicator();

                    flushStreamingTokens();


                    if (generationCancelledByUser) {

                        if (!isDiceContinuationTurn) {
                            removeLastPlayerEvent();
                        }

                        diceContinuationTurnActive = false;

                        appendColoredText(
                                "\n\n",
                                COLOR_MASTER
                        );


                        generationInProgress =
                                false;

                        directorTurnActive =
                                false;

                        activeDirectorCampaignId =
                                0L;

                        generationCancelledByUser =
                                false;

                        masterStreamingStarted =
                                false;

                        masterStreamingStartPosition =
                                -1;


                        refreshCharacterLifeUi();


                        updateChat();

                        return;
                    }


                    /*
                     * Чистим финальный художественный ответ
                     * перед показом и сохранением.
                     */
                    String visibleText =
                            responseCleaner.clean(
                                    directiveResult.getNarrativeText()
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
                                if (isDirectorTurn) {
                                    directorTurnActive = false;
                                    activeDirectorCampaignId = 0L;
                                    runWorldMaintenanceAfterMasterTurn();
                                    refreshCharacterLifeUi();
                                } else if (isDiceContinuationTurn) {
                                    diceContinuationTurnActive = false;
                                    activeDirectorCampaignId = 0L;
                                    runWorldMaintenanceAfterMasterTurn();
                                    refreshCharacterLifeUi();
                                } else {
                                    applyNarrativeDirectives(
                                            currentCampaignId,
                                            directiveResult.getDirectives(),
                                            () -> {
                                                runWorldMaintenanceAfterMasterTurn();
                                                refreshCharacterLifeUi();
                                            }
                                    );
                                }
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
                    directorTurnActive = false;
                    activeDirectorCampaignId = 0L;
                    diceContinuationTurnActive = false;
                    generationCancelledByUser = false;
                    masterStreamingStarted = false;

                    refreshCharacterLifeUi();

                    Log.e(
                            "MyDND_LLM",
                            "Generation failed",
                            throwable
                    );
                });
            }
        };
    }


    private void applyNarrativeDirectives(
            long campaignId,
            List<NarrativeDirectiveParser.Directive> directives,
            Runnable onFinished
    ) {
        if (directives == null || directives.isEmpty()) {
            onFinished.run();
            return;
        }

        DbExecutor.execute(() -> {
            List<StateChangeEntity> created = new ArrayList<>();

            for (NarrativeDirectiveParser.Directive directive : directives) {
                if (NarrativeDirectiveParser.Directive.NPC_MEMORY.equals(
                        directive.getType()
                )) {
                    StateChangeEntity change =
                            stateChangeRepository.rememberNpc(
                                    campaignId,
                                    directive.getSubject(),
                                    directive.getTone(),
                                    directive.getText()
                            );

                    if (change != null) {
                        created.add(change);
                    }

                } else if (NarrativeDirectiveParser.Directive.DICE_CHECK.equals(
                        directive.getType()
                )) {
                    StateChangeEntity change =
                            stateChangeRepository.createDiceCheck(
                                    campaignId,
                                    directive.getSubject(),
                                    directive.getDifficulty(),
                                    directive.getText()
                            );

                    if (change != null) {
                        created.add(change);
                    }
                }
            }

            runOnUiThread(() -> {
                if (currentCampaignId == campaignId) {
                    for (StateChangeEntity change : created) {
                        appendStateChangeCard(change);
                    }
                    updateChat();
                }

                onFinished.run();
            });
        });
    }


    private void openDiceCheck(long changeId) {
        if (generationInProgress) {
            Toast.makeText(
                    this,
                    "Дождитесь завершения текущего хода.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        DbExecutor.execute(() -> {
            StateChangeEntity change = stateChangeRepository.get(changeId);

            runOnUiThread(() -> {
                if (change == null
                        || !StateChangeRepository.STATUS_PENDING.equals(
                        change.status
                )) {
                    return;
                }

                showDiceRollDialog(20, change);
            });
        });
    }


    private String displayAttribute(String code) {
        String safe = code == null
                ? ""
                : code.trim().toUpperCase(Locale.ROOT);

        if ("STR".equals(safe) || "СИЛ".equals(safe) || "СИЛА".equals(safe)) {
            return "СИЛА";
        }

        if ("DEX".equals(safe) || "ЛОВ".equals(safe) || "ЛОВКОСТЬ".equals(safe)) {
            return "ЛОВКОСТЬ";
        }

        if ("INT".equals(safe) || "ИНТ".equals(safe) || "ИНТЕЛЛЕКТ".equals(safe)) {
            return "ИНТЕЛЛЕКТ";
        }

        if ("CHA".equals(safe) || "ХАР".equals(safe) || "ХАРИЗМА".equals(safe)) {
            return "ХАРИЗМА";
        }

        return safe.isEmpty() ? "ПРОВЕРКА" : safe;
    }


    private void saveDiceResultForPrompt(
            long campaignId,
            StateChangeEntity check,
            int total,
            boolean success
    ) {
        GameEventEntity entity = new GameEventEntity();
        entity.campaignId = campaignId;
        entity.speaker = GameEvent.Speaker.SYSTEM.name();
        entity.text = "Результат проверки "
                + displayAttribute(check.subjectName)
                + ": d20="
                + total
                + " против СЛ "
                + check.beforeNumber
                + " — "
                + (success ? "УСПЕХ" : "ПРОВАЛ")
                + ".";
        entity.includeInPrompt = true;
        entity.createdAt = System.currentTimeMillis();

        database.gameEventDao().insert(entity);
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
    private void setupSendButtonBusyAnimation() {
        ObjectAnimator alphaAnimator = ObjectAnimator.ofFloat(
                sendButton,
                View.ALPHA,
                1.0f,
                0.55f
        );

        alphaAnimator.setDuration(600L);
        alphaAnimator.setRepeatMode(ValueAnimator.REVERSE);
        alphaAnimator.setRepeatCount(ValueAnimator.INFINITE);

        ObjectAnimator scaleXAnimator = ObjectAnimator.ofFloat(
                sendButton,
                View.SCALE_X,
                0.94f,
                1.00f
        );

        scaleXAnimator.setDuration(600L);
        scaleXAnimator.setRepeatMode(ValueAnimator.REVERSE);
        scaleXAnimator.setRepeatCount(ValueAnimator.INFINITE);

        ObjectAnimator scaleYAnimator = ObjectAnimator.ofFloat(
                sendButton,
                View.SCALE_Y,
                0.94f,
                1.00f
        );

        scaleYAnimator.setDuration(600L);
        scaleYAnimator.setRepeatMode(ValueAnimator.REVERSE);
        scaleYAnimator.setRepeatCount(ValueAnimator.INFINITE);

        sendButtonBusyAnimator = new AnimatorSet();
        sendButtonBusyAnimator.playTogether(
                alphaAnimator,
                scaleXAnimator,
                scaleYAnimator
        );

        sendButton.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(
                    CharSequence text,
                    int start,
                    int count,
                    int after
            ) {
            }

            @Override
            public void onTextChanged(
                    CharSequence text,
                    int start,
                    int before,
                    int count
            ) {
                updateSendButtonBusyAnimation(text);
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        });

        updateSendButtonBusyAnimation(sendButton.getText());
    }

    private void updateSendButtonBusyAnimation(
            CharSequence buttonText
    ) {
        boolean busy = buttonText != null
                && !"Отправить".contentEquals(buttonText);

        if (busy) {
            if (!sendButtonBusyAnimator.isRunning()) {
                sendButton.setAlpha(1.0f);
                sendButton.setScaleX(1.0f);
                sendButton.setScaleY(1.0f);

                sendButtonBusyAnimator.start();
            }

            return;
        }

        sendButtonBusyAnimator.cancel();

        sendButton.setAlpha(1.0f);
        sendButton.setScaleX(1.0f);
        sendButton.setScaleY(1.0f);
    }
    private void showBackgroundChooser(Button backgroundButton) {
        List<GameBackgroundManager.BackgroundOption> backgrounds =
                GameBackgroundManager.getAvailableBackgrounds(this);

        if (backgrounds.isEmpty()) {
            return;
        }

        String[] titles = new String[backgrounds.size()];

        for (int i = 0; i < backgrounds.size(); i++) {
            titles[i] = backgrounds.get(i).getTitle();
        }

        int selectedIndex =
                GameBackgroundManager.getSelectedBackgroundIndex(this);

        new AlertDialog.Builder(this)
                .setTitle("Выберите фон")
                .setSingleChoiceItems(
                        titles,
                        selectedIndex,
                        (dialog, which) -> {
                            GameBackgroundManager.BackgroundOption selected =
                                    backgrounds.get(which);

                            GameBackgroundManager.saveSelectedBackground(
                                    this,
                                    selected.getResourceName()
                            );

                            applyGamePalette();

                            GameBackgroundManager.apply(
                                    this,
                                    gameBackground
                            );

                            backgroundButton.setText(
                                    "Фон игры: " + selected.getTitle()
                            );
                            if (currentCampaignId > 0L
                                    && gameLayout.getVisibility() == View.VISIBLE) {

                                reloadChatTimeline();
                            }

                            dialog.dismiss();
                        }
                )
                .setNegativeButton("Отмена", null)
                .show();
    }
    private void applyGamePalette() {
        String backgroundName =
                GameBackgroundManager.getSelectedBackgroundName(this);

        boolean lightTheme =
                "game_bg2".equals(backgroundName);

        if (lightTheme) {

            // Светлый фон — тёмные чернила.
            COLOR_MASTER =
                    Color.rgb(48, 36, 25);

            COLOR_PLAYER =
                    Color.rgb(35, 70, 98);

            COLOR_SYSTEM =
                    Color.rgb(100, 91, 80);

            COLOR_CARD_BG =
                    Color.argb(
                            220,
                            232,
                            216,
                            184
                    );

            COLOR_CARD_BORDER =
                    Color.rgb(112, 79, 42);

            COLOR_CARD_GOOD =
                    Color.rgb(68, 105, 60);

            COLOR_CARD_BAD =
                    Color.rgb(139, 62, 52);

            COLOR_CARD_NEUTRAL =
                    Color.rgb(112, 79, 42);

            COLOR_CARD_REVERTED_BG =
                    Color.argb(
                            205,
                            190,
                            185,
                            174
                    );

            COLOR_CARD_REVERTED_TEXT =
                    Color.rgb(100, 96, 90);

            COLOR_INPUT_TEXT =
                    Color.rgb(48, 36, 25);

            COLOR_INPUT_HINT =
                    Color.rgb(115, 100, 84);

            COLOR_INPUT_TINT =
                    Color.rgb(112, 79, 42);

        } else {

            // Исходный тёмный фон.
            COLOR_MASTER =
                    Color.rgb(232, 224, 208);

            COLOR_PLAYER =
                    Color.rgb(150, 190, 255);

            COLOR_SYSTEM =
                    Color.rgb(120, 120, 120);

            COLOR_CARD_BG =
                    Color.argb(
                            178,
                            30,
                            27,
                            22
                    );

            COLOR_CARD_BORDER =
                    Color.rgb(199, 166, 106);

            COLOR_CARD_GOOD =
                    Color.rgb(132, 176, 125);

            COLOR_CARD_BAD =
                    Color.rgb(190, 124, 112);

            COLOR_CARD_NEUTRAL =
                    Color.rgb(199, 166, 106);

            COLOR_CARD_REVERTED_BG =
                    Color.argb(
                            150,
                            48,
                            48,
                            48
                    );

            COLOR_CARD_REVERTED_TEXT =
                    Color.rgb(145, 145, 145);

            COLOR_INPUT_TEXT =
                    Color.WHITE;

            COLOR_INPUT_HINT =
                    Color.rgb(119, 119, 119);

            COLOR_INPUT_TINT =
                    Color.rgb(169, 138, 85);
        }

        if (inputEditText != null) {
            inputEditText.setTextColor(
                    COLOR_INPUT_TEXT
            );

            inputEditText.setHintTextColor(
                    COLOR_INPUT_HINT
            );

            inputEditText.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            COLOR_INPUT_TINT
                    )
            );
        }
    }
}
