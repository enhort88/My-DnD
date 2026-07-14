package com.example.mydnd;

import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import com.example.mydnd.db.AppDatabase;
import com.example.mydnd.db.DbExecutor;
import com.example.mydnd.db.entity.WorldEventEntity;
import com.example.mydnd.draft.CharacterDraft;
import com.example.mydnd.draft.StartingSituationDraft;
import com.example.mydnd.draft.WorldDraft;
import com.example.mydnd.game.setup.CampaignSetupRepository;
import com.example.mydnd.game.setup.CharacterData;
import com.example.mydnd.game.setup.CharacterRepository;
import com.example.mydnd.game.setup.LivingWorldData;
import com.example.mydnd.game.setup.NewGameDraftGenerator;
import com.example.mydnd.game.setup.WorldData;
import com.example.mydnd.game.setup.WorldRepository;
import com.example.mydnd.llm.LlmModelManager;
import com.example.mydnd.util.MusicManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class NewGameActivity extends ComponentActivity {

    private static final String MASTER_MODEL_FILE =
            "gemma-4-E4B_q4_0-it.gguf";

    private static final String SERVICE_MODEL_FILE =
            "gemma-3-1b-it-q4_0.gguf";

    private static final int STAGE_WORLD = 1;
    private static final int STAGE_CHARACTER = 2;
    private static final int STAGE_SITUATION = 3;

    private TextView stageText;
    private TextView helpText;
    private EditText requestEditText;
    private Button existingWorldButton;
    private Button generateButton;
    private TextView statusText;
    private TextView previewText;
    private View decisionPanel;
    private Button confirmButton;
    private Button regenerateButton;
    private Button backButton;

    private AppDatabase database;
    private WorldRepository worldRepository;
    private CharacterRepository characterRepository;
    private CampaignSetupRepository campaignSetupRepository;

    private LlmModelManager modelManager;
    private NewGameDraftGenerator draftGenerator;

    private int stage = STAGE_WORLD;
    private boolean busy = false;

    private String worldRequest = "";
    private String characterRequest = "";
    private String situationRequest = "";

    private long worldId = 0L;
    private long worldTimelineId = 0L;
    private long characterId = 0L;

    private LivingWorldData livingWorldData;
    private WorldData worldData;
    private CharacterData characterData;

    private WorldDraft worldDraft;
    private CharacterDraft characterDraft;
    private StartingSituationDraft situationDraft;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_new_game);
        MusicManager.play(
                this,
                R.raw.menu_theme
        );

        bindViews();
        initDependencies();
        showWorldStage();

        existingWorldButton.setOnClickListener(v -> showExistingWorlds());
        generateButton.setOnClickListener(v -> generateCurrentStage());
        regenerateButton.setOnClickListener(v -> generateCurrentStage());
        confirmButton.setOnClickListener(v -> confirmCurrentStage());
        backButton.setOnClickListener(v -> {
            if (!busy) {
                finish();
            }
        });
    }

    private void bindViews() {
        stageText = findViewById(R.id.newGameStageText);
        helpText = findViewById(R.id.newGameHelpText);
        requestEditText = findViewById(R.id.newGameRequestEditText);
        existingWorldButton = findViewById(R.id.newGameExistingWorldButton);
        generateButton = findViewById(R.id.newGameGenerateButton);
        statusText = findViewById(R.id.newGameStatusText);
        previewText = findViewById(R.id.newGamePreviewText);
        decisionPanel = findViewById(R.id.newGameDecisionPanel);
        confirmButton = findViewById(R.id.newGameConfirmButton);
        regenerateButton = findViewById(R.id.newGameRegenerateButton);
        backButton = findViewById(R.id.newGameBackButton);
    }

    private void initDependencies() {
        database = AppDatabase.getInstance(this);
        worldRepository = new WorldRepository(database);
        characterRepository = new CharacterRepository(database);
        campaignSetupRepository = new CampaignSetupRepository(database);

        File modelsDirectory = getExternalFilesDir("models");

        File masterModelFile = new File(
                modelsDirectory,
                MASTER_MODEL_FILE
        );

        File serviceModelFile = new File(
                modelsDirectory,
                SERVICE_MODEL_FILE
        );

        modelManager = new LlmModelManager(
                masterModelFile.getAbsolutePath(),
                serviceModelFile.getAbsolutePath()
        );

        draftGenerator = new NewGameDraftGenerator(modelManager);
    }

    private void showExistingWorlds() {
        if (busy) {
            return;
        }

        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_living_worlds);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout worldsContainer =
                dialog.findViewById(R.id.livingWorldsContainer);

        Button closeButton =
                dialog.findViewById(R.id.closeLivingWorldsButton);

        closeButton.setOnClickListener(v -> dialog.dismiss());

        dialog.setOnShowListener(ignored ->
                configureFantasyDialogWindow(
                        dialog,
                        0.94f,
                        0.88f
                )
        );

        dialog.show();
        loadLivingWorldsIntoDialog(dialog, worldsContainer);
    }


    private void loadLivingWorldsIntoDialog(
            Dialog dialog,
            LinearLayout worldsContainer
    ) {
        worldsContainer.removeAllViews();

        TextView loadingText = new TextView(this);
        loadingText.setText("Загружаю живые миры...");
        loadingText.setTextColor(Color.rgb(184, 176, 160));
        loadingText.setTextSize(15f);
        loadingText.setPadding(8, 24, 8, 24);
        loadingText.setGravity(android.view.Gravity.CENTER);
        worldsContainer.addView(loadingText);

        DbExecutor.execute(() -> {
            try {
                List<LivingWorldData> worlds =
                        worldRepository.getAllLivingWorlds();

                runOnUiThread(() -> {
                    if (!dialog.isShowing()) {
                        return;
                    }

                    renderLivingWorldCards(
                            dialog,
                            worldsContainer,
                            worlds
                    );
                });

            } catch (Throwable throwable) {
                runOnUiThread(() -> {
                    if (!dialog.isShowing()) {
                        return;
                    }

                    worldsContainer.removeAllViews();

                    TextView errorText = new TextView(this);
                    errorText.setText("Не удалось загрузить миры.");
                    errorText.setTextColor(Color.rgb(216, 154, 130));
                    errorText.setTextSize(15f);
                    errorText.setPadding(8, 24, 8, 24);
                    errorText.setGravity(android.view.Gravity.CENTER);
                    worldsContainer.addView(errorText);
                });
            }
        });
    }


    private void renderLivingWorldCards(
            Dialog dialog,
            LinearLayout worldsContainer,
            List<LivingWorldData> worlds
    ) {
        worldsContainer.removeAllViews();

        if (worlds == null || worlds.isEmpty()) {
            TextView emptyText = new TextView(this);
            emptyText.setText("Сохранённых миров пока нет.");
            emptyText.setTextColor(Color.rgb(184, 176, 160));
            emptyText.setTextSize(15f);
            emptyText.setPadding(8, 28, 8, 28);
            emptyText.setGravity(android.view.Gravity.CENTER);
            worldsContainer.addView(emptyText);
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);

        for (LivingWorldData livingWorld : worlds) {
            View card = inflater.inflate(
                    R.layout.item_living_world,
                    worldsContainer,
                    false
            );

            TextView titleText =
                    card.findViewById(R.id.livingWorldTitleText);

            TextView genreText =
                    card.findViewById(R.id.livingWorldGenreText);

            TextView stateText =
                    card.findViewById(R.id.livingWorldStateText);

            TextView changesText =
                    card.findViewById(R.id.livingWorldChangesText);

            Button playButton =
                    card.findViewById(R.id.playLivingWorldButton);

            Button deleteButton =
                    card.findViewById(R.id.deleteLivingWorldButton);

            String worldName =
                    livingWorld.getWorldData().getWorld().name;

            String timelineName =
                    livingWorld.getTimeline().name;

            titleText.setText(
                    timelineName == null || timelineName.trim().isEmpty()
                            ? worldName
                            : worldName + " — " + timelineName.trim()
            );

            String genre =
                    livingWorld.getWorldData().getWorld().genre;

            if (genre == null || genre.trim().isEmpty()) {
                genreText.setVisibility(View.GONE);
            } else {
                genreText.setVisibility(View.VISIBLE);
                genreText.setText(genre.trim());
            }

            String state = limit(
                    livingWorld.getTimeline().stateSummary,
                    220
            );

            if (state.isEmpty()) {
                stateText.setVisibility(View.GONE);
            } else {
                stateText.setVisibility(View.VISIBLE);
                stateText.setText(state);
            }

            String changes = buildRecentWorldChanges(livingWorld);

            if (changes.isEmpty()) {
                changesText.setVisibility(View.GONE);
            } else {
                changesText.setVisibility(View.VISIBLE);
                changesText.setText("Последние изменения: " + changes);
            }

            playButton.setOnClickListener(v -> {
                dialog.dismiss();
                selectExistingWorld(livingWorld);
            });

            deleteButton.setOnClickListener(v ->
                    showDeleteLivingWorldDialog(
                            dialog,
                            worldsContainer,
                            livingWorld
                    )
            );

            worldsContainer.addView(card);
        }
    }


    private String buildRecentWorldChanges(
            LivingWorldData data
    ) {
        List<String> changes = new ArrayList<>();

        for (WorldEventEntity event : data.getRecentEvents()) {
            if (event == null
                    || event.text == null
                    || event.text.trim().isEmpty()) {
                continue;
            }

            changes.add(
                    limit(event.text.trim(), 100)
            );

            if (changes.size() >= 2) {
                break;
            }
        }

        return String.join(" • ", changes);
    }


    private void showDeleteLivingWorldDialog(
            Dialog worldsDialog,
            LinearLayout worldsContainer,
            LivingWorldData livingWorld
    ) {
        Dialog confirmDialog = new Dialog(this);
        confirmDialog.setContentView(
                R.layout.dialog_confirm_delete_living_world
        );
        confirmDialog.setCanceledOnTouchOutside(true);

        TextView messageText =
                confirmDialog.findViewById(
                        R.id.deleteLivingWorldMessage
                );

        Button cancelButton =
                confirmDialog.findViewById(
                        R.id.cancelDeleteLivingWorldButton
                );

        Button deleteButton =
                confirmDialog.findViewById(
                        R.id.confirmDeleteLivingWorldButton
                );

        String worldName =
                livingWorld.getWorldData().getWorld().name;

        messageText.setText(
                "Удалить живой мир «"
                        + worldName
                        + "»?\n\n"
                        + "Его история, события и состояние будут удалены. "
                        + "Мир с сохранёнными кампаниями удалить нельзя."
        );

        cancelButton.setOnClickListener(v ->
                confirmDialog.dismiss()
        );

        deleteButton.setOnClickListener(v -> {
            deleteButton.setEnabled(false);
            deleteButton.setText("Удаляю...");

            deleteLivingWorld(
                    confirmDialog,
                    worldsDialog,
                    worldsContainer,
                    livingWorld.getTimeline().id
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


    private void deleteLivingWorld(
            Dialog confirmDialog,
            Dialog worldsDialog,
            LinearLayout worldsContainer,
            long timelineId
    ) {
        DbExecutor.execute(() -> {
            try {
                WorldRepository.DeleteLivingWorldResult result =
                        worldRepository.deleteLivingWorld(timelineId);

                runOnUiThread(() -> {
                    if (result
                            == WorldRepository.DeleteLivingWorldResult.DELETED) {

                        confirmDialog.dismiss();

                        Toast.makeText(
                                this,
                                "Мир удалён.",
                                Toast.LENGTH_SHORT
                        ).show();

                        if (worldsDialog.isShowing()) {
                            loadLivingWorldsIntoDialog(
                                    worldsDialog,
                                    worldsContainer
                            );
                        }

                        return;
                    }

                    Button deleteButton =
                            confirmDialog.findViewById(
                                    R.id.confirmDeleteLivingWorldButton
                            );

                    deleteButton.setEnabled(true);
                    deleteButton.setText("Удалить");

                    if (result
                            == WorldRepository.DeleteLivingWorldResult.HAS_CAMPAIGNS) {

                        Toast.makeText(
                                this,
                                "В этом мире есть сохранённые игры. Сначала удалите их.",
                                Toast.LENGTH_LONG
                        ).show();

                        return;
                    }

                    Toast.makeText(
                            this,
                            "Мир уже не найден.",
                            Toast.LENGTH_SHORT
                    ).show();
                });

            } catch (Throwable throwable) {
                runOnUiThread(() -> {
                    Button deleteButton =
                            confirmDialog.findViewById(
                                    R.id.confirmDeleteLivingWorldButton
                            );

                    deleteButton.setEnabled(true);
                    deleteButton.setText("Удалить");

                    Toast.makeText(
                            this,
                            "Не удалось удалить мир.",
                            Toast.LENGTH_SHORT
                    ).show();
                });
            }
        });
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
                ? (int) (screenHeight * heightFraction)
                : ViewGroup.LayoutParams.WRAP_CONTENT;

        window.setLayout(width, height);
        window.setDimAmount(0.72f);
        window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND
        );
    }


    private void selectExistingWorld(LivingWorldData selected) {
        livingWorldData = selected;
        worldData = selected.getWorldData();
        worldId = worldData.getWorld().id;
        worldTimelineId = selected.getTimeline().id;
        worldDraft = null;

        showCharacterStage();
    }

    private void generateCurrentStage() {
        if (busy) {
            return;
        }

        String request = requestEditText.getText().toString().trim();

        if (stage != STAGE_SITUATION && request.isEmpty()) {
            Toast.makeText(
                    this,
                    "Сначала напишите пожелание.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        hideDraft();
        setBusy(true, "GM создаёт черновик...");

        if (stage == STAGE_WORLD) {
            worldRequest = request;

            draftGenerator.generateWorld(
                    request,
                    new NewGameDraftGenerator.Listener<WorldDraft>() {
                        @Override
                        public void onSuccess(WorldDraft draft, String rawText) {
                            runOnUiThread(() -> showWorldDraft(draft));
                        }

                        @Override
                        public void onError(Throwable throwable, String rawText) {
                            runOnUiThread(() -> showGenerationError(throwable, rawText));
                        }
                    }
            );

            return;
        }

        if (stage == STAGE_CHARACTER) {
            characterRequest = request;

            draftGenerator.generateCharacter(
                    livingWorldData,
                    request,
                    new NewGameDraftGenerator.Listener<CharacterDraft>() {
                        @Override
                        public void onSuccess(CharacterDraft draft, String rawText) {
                            runOnUiThread(() -> showCharacterDraft(draft));
                        }

                        @Override
                        public void onError(Throwable throwable, String rawText) {
                            runOnUiThread(() -> showGenerationError(throwable, rawText));
                        }
                    }
            );

            return;
        }

        situationRequest = request;

        draftGenerator.generateSituation(
                livingWorldData,
                characterData,
                request,
                new NewGameDraftGenerator.Listener<StartingSituationDraft>() {
                    @Override
                    public void onSuccess(StartingSituationDraft draft, String rawText) {
                        runOnUiThread(() -> showSituationDraft(draft));
                    }

                    @Override
                    public void onError(Throwable throwable, String rawText) {
                        runOnUiThread(() -> showGenerationError(throwable, rawText));
                    }
                }
        );
    }

    private void confirmCurrentStage() {
        if (busy) {
            return;
        }

        if (stage == STAGE_WORLD) {
            if (worldDraft == null) {
                return;
            }

            setBusy(true, "Сохраняю мир...");

            DbExecutor.execute(() -> {
                livingWorldData = worldRepository.createWorld(
                        worldDraft,
                        worldRequest
                );

                worldData = livingWorldData.getWorldData();
                worldId = worldData.getWorld().id;
                worldTimelineId = livingWorldData.getTimeline().id;

                runOnUiThread(this::showCharacterStage);
            });

            return;
        }

        if (stage == STAGE_CHARACTER) {
            if (characterDraft == null) {
                return;
            }

            setBusy(true, "Сохраняю героя...");

            DbExecutor.execute(() -> {
                characterId = characterRepository.createCharacter(
                        worldId,
                        characterDraft,
                        characterRequest
                );

                characterData = characterRepository.getCharacterData(characterId);

                runOnUiThread(this::showSituationStage);
            });

            return;
        }

        if (situationDraft == null) {
            return;
        }

        setBusy(true, "Создаю кампанию...");

        DbExecutor.execute(() -> {
            long campaignId = campaignSetupRepository.createCampaign(
                    livingWorldData,
                    characterData,
                    situationDraft
            );

            runOnUiThread(() -> openCampaign(campaignId));
        });
    }

    private void showWorldStage() {
        stage = STAGE_WORLD;
        stageText.setText("1 из 3 — Мир");
        helpText.setText(
                "Создайте новый мир или войдите в уже существующий. "
                        + "События такого мира переживают отдельных героев."
        );
        requestEditText.setHint(
                "Мрачное фэнтези после гражданской войны. Магия опасна, есть люди, эльфы и гномы..."
        );
        requestEditText.setText(worldRequest);
        existingWorldButton.setVisibility(View.VISIBLE);
        generateButton.setText("СГЕНЕРИРОВАТЬ НОВЫЙ МИР");
        confirmButton.setText("ПОДТВЕРДИТЬ МИР");
        hideDraft();
        setBusy(false, "");
    }

    private void showCharacterStage() {
        stage = STAGE_CHARACTER;
        existingWorldButton.setVisibility(View.GONE);
        stageText.setText("2 из 3 — Герой");
        helpText.setText(
                "Живой мир: " + worldData.getWorld().name
                        + ". Герой будет создан уже с учётом его текущего состояния."
        );
        requestEditText.setHint(
                "Молодой жадный вор. Не злой, но ради денег готов рисковать..."
        );
        requestEditText.setText(characterRequest);
        generateButton.setText("СГЕНЕРИРОВАТЬ ГЕРОЯ");
        confirmButton.setText("ПОДТВЕРДИТЬ ГЕРОЯ");
        hideDraft();
        setBusy(false, "");
    }

    private void showSituationStage() {
        stage = STAGE_SITUATION;
        existingWorldButton.setVisibility(View.GONE);
        stageText.setText("3 из 3 — Старт");
        helpText.setText(
                "Герой сохранён: " + characterData.getCharacter().name
                        + ". Стартовая ситуация не отменит уже произошедшие события мира."
        );
        requestEditText.setHint(
                "Например: начать в дороге, без немедленной драки, но с тревожной загадкой..."
        );
        requestEditText.setText(situationRequest);
        generateButton.setText("СГЕНЕРИРОВАТЬ СИТУАЦИЮ");
        confirmButton.setText("НАЧАТЬ ИГРУ");
        hideDraft();
        setBusy(false, "");
    }

    private void showWorldDraft(WorldDraft draft) {
        worldDraft = draft;
        previewText.setText(draft.toDisplayText());
        showDraft();
    }

    private void showCharacterDraft(CharacterDraft draft) {
        characterDraft = draft;
        previewText.setText(draft.toDisplayText());
        showDraft();
    }

    private void showSituationDraft(StartingSituationDraft draft) {
        situationDraft = draft;
        previewText.setText(draft.toDisplayText());
        showDraft();
    }

    private void showDraft() {
        setBusy(false, "Черновик готов. Измените пожелание и перегенерируйте или подтвердите.");
        previewText.setVisibility(View.VISIBLE);
        decisionPanel.setVisibility(View.VISIBLE);
    }

    private void hideDraft() {
        previewText.setVisibility(View.GONE);
        decisionPanel.setVisibility(View.GONE);
    }

    private void showGenerationError(
            Throwable throwable,
            String rawText
    ) {
        setBusy(false, "Не удалось разобрать ответ. Можно сразу повторить генерацию.");

        String message = throwable == null
                ? "Неизвестная ошибка"
                : throwable.getMessage();

        if (rawText != null && !rawText.trim().isEmpty()) {
            previewText.setText(
                    "Ошибка: " + message
                            + "\n\nRAW ОТ МОДЕЛИ:\n"
                            + rawText.trim()
            );
            previewText.setVisibility(View.VISIBLE);
        }

        Toast.makeText(
                this,
                message,
                Toast.LENGTH_LONG
        ).show();
    }

    private void setBusy(
            boolean busy,
            String status
    ) {
        this.busy = busy;
        existingWorldButton.setEnabled(!busy);
        generateButton.setEnabled(!busy);
        confirmButton.setEnabled(!busy);
        regenerateButton.setEnabled(!busy);
        backButton.setEnabled(!busy);
        requestEditText.setEnabled(!busy);
        statusText.setText(status);
    }

    private String limit(String value, int maxChars) {
        String safeValue = value == null ? "" : value.trim();

        if (safeValue.length() <= maxChars) {
            return safeValue;
        }

        return safeValue.substring(0, maxChars).trim();
    }

    private void openCampaign(long campaignId) {
        modelManager.releaseAll();

        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_OPEN_CAMPAIGN_ID, campaignId);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (modelManager != null) {
            modelManager.releaseAll();
        }

        super.onDestroy();
    }
}
