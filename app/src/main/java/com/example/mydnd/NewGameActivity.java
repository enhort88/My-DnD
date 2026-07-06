package com.example.mydnd;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.appcompat.app.AlertDialog;

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

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class NewGameActivity extends ComponentActivity {

    private static final String MASTER_MODEL_FILE =
            "gemma-4-E2B_q4_0-it.gguf";

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

        setBusy(true, "Загружаю живые миры...");

        DbExecutor.execute(() -> {
            List<LivingWorldData> worlds = worldRepository.getAllLivingWorlds();

            runOnUiThread(() -> {
                setBusy(false, "");

                if (worlds.isEmpty()) {
                    Toast.makeText(
                            this,
                            "Сохранённых миров пока нет.",
                            Toast.LENGTH_SHORT
                    ).show();
                    return;
                }

                String[] labels = new String[worlds.size()];

                for (int i = 0; i < worlds.size(); i++) {
                    labels[i] = buildLivingWorldLabel(worlds.get(i));
                }

                new AlertDialog.Builder(this)
                        .setTitle("Выберите живой мир")
                        .setItems(labels, (dialog, which) -> {
                            if (which < 0 || which >= worlds.size()) {
                                return;
                            }

                            selectExistingWorld(worlds.get(which));
                        })
                        .setNegativeButton("Отмена", null)
                        .show();
            });
        });
    }

    private String buildLivingWorldLabel(LivingWorldData data) {
        StringBuilder text = new StringBuilder();
        text.append(data.getWorldData().getWorld().name);

        if (!data.getTimeline().name.isEmpty()) {
            text.append(" — ").append(data.getTimeline().name);
        }

        String state = limit(data.getTimeline().stateSummary, 140);

        if (!state.isEmpty()) {
            text.append("\n").append(state);
        }

        List<String> changes = new ArrayList<>();

        for (WorldEventEntity event : data.getRecentEvents()) {
            if (event != null && event.text != null && !event.text.trim().isEmpty()) {
                changes.add(event.text.trim());
            }

            if (changes.size() >= 2) {
                break;
            }
        }

        if (!changes.isEmpty()) {
            text.append("\nИзменения: ").append(String.join("; ", changes));
        }

        return limit(text.toString(), 420);
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
