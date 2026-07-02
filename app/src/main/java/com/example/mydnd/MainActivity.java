package com.example.mydnd;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import com.example.mydnd.llm.LlmCallback;
import com.example.mydnd.llm.LlmEngine;
import com.example.mydnd.llm.LocalLlmEngine;
import android.widget.CheckBox;
import com.example.mydnd.llm.ThinkBlockFilter;
import java.io.File;
import android.os.Handler;
import android.os.Looper;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;


public class MainActivity extends Activity {

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

    private static final long STREAM_FLUSH_DELAY_MS = 50;


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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        welcomeLayout = findViewById(R.id.welcomeLayout);
        gameLayout = findViewById(R.id.gameLayout);

        startGameButton = findViewById(R.id.startGameButton);
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
            startFirstScene();
        });

        exitButton.setOnClickListener(v -> finish());


        sendButton.setOnClickListener(v -> sendPlayerMessage());
        sendButton.setOnLongClickListener(v -> {
            llmEngine.cancel();
            sendButton.setEnabled(true);
            sendButton.setText("Отправить");

            chatHistory.append("Система: генерация отменена вручную.\n\n");
            updateChat();

            return true;
        });
    }

    private void startFirstScene() {
        chatDisplay.clear();
        promptHistory.setLength(0);

        appendMasterMessage(
                "Ты стоишь у входа в старую придорожную таверну. " +
                        "За мутными окнами дрожит жёлтый свет. " +
                        "Дождь стучит по крыше, а где-то рядом воет пёс. " +
                        "Дверь приоткрыта, но внутри подозрительно тихо."
        );

        appendSystemMessage("UI готов. Можно писать действие.");

        sendButton.setEnabled(true);
        sendButton.setText("Отправить");
    }

    private void sendPlayerMessage() {
        String playerText = inputEditText.getText().toString().trim();

        if (playerText.isEmpty()) {
            return;
        }

        inputEditText.setText("");
        hideKeyboard();

        appendPlayerMessage(playerText);

        updateChat();

        sendButton.setEnabled(true);
        sendButton.setText("Жду...");

        appendSystemMessage("Запрос ушёл в небеса, жду ответ...");
        updateChat();

        String prompt = buildPrompt(playerText);

        masterStreamingStarted = false;
        generationInProgress = true;

        llmEngine.generate(prompt, new LlmCallback() {
            @Override
            public void onToken(String token) {
                runOnUiThread(() -> {
                    if (token == null || token.isEmpty()) {
                        return;
                    }

                    if (token.startsWith("[Система]")) {
                        appendSystemMessage(token.replace("[Система]", "").trim());
                        return;
                    }

                    if (!masterStreamingStarted) {
                        masterStreamingStarted = true;
                    }

                    appendMasterStreamingToken(token);
                });
            }

            @Override
            public void onComplete(String fullText) {
                runOnUiThread(() -> {
                    flushStreamingTokens();

                    String visibleText = fullText;

                    if (!showThinkingCheckBox.isChecked()) {
                        visibleText = thinkBlockFilter.removeThinkBlocks(fullText);
                    }

                    if (masterStreamingStarted) {
                        appendColoredText("\n\n", COLOR_MASTER);
                    } else {
                        appendMasterMessage(visibleText);
                    }

                    promptHistory.append("Мастер: ");
                    promptHistory.append(visibleText);
                    promptHistory.append("\n\n");

                    generationInProgress = false;
                    masterStreamingStarted = false;

                    updateChat();

                    sendButton.setEnabled(true);
                    sendButton.setText("Отправить");
                });
            }

            @Override
            public void onError(Throwable throwable) {
                runOnUiThread(() -> {
                    generationInProgress = false;
                    masterStreamingStarted = false;

                    appendSystemMessage("Ошибка LLM: " + throwable.getMessage());

                    sendButton.setEnabled(true);
                    sendButton.setText("Отправить");
                });
            }
        });
    }

    private String buildPrompt(String playerText) {
        String thinkingMode = useThinkingCheckBox.isChecked() ? "/think" : "/no_think";

        String recentHistory = getRecentGameHistoryForPrompt(250);

        return thinkingMode +
                "\nТы мастер DnD. Русский язык. Мрачное фэнтези." +
                "\nОписывай атмосферно, но без воды." +
                "\nНе решай за героя. При риске проси проверку, кубик не бросай." +
                "\nДай цельный ответ: сцена, последствия, детали, вопрос игроку." +
                "\nНе обрывай фразу. Заверши ответ логично." +
                "\n\nСцена: ночная таверна, дождь, внутри подозрительно тихо." +
                "\n\nКраткая история:\n" + recentHistory +
                "\n\nИгрок: " + playerText +
                "\nМастер:";
    }
//private String buildPrompt(String playerText) {
//    String thinkingMode;
//
//    if (useThinkingCheckBox.isChecked()) {
//        thinkingMode = "/think";
//    } else {
//        thinkingMode = "/no_think";
//    }
//
//    return thinkingMode +
//            "\nТы — мастер DnD. Отвечай на русском. Очень кратко." +
//            "\nИгрок: " + playerText +
//            "\nМастер:";
//}
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
            flushStreamingTokens();
            streamFlushScheduled = false;
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
        welcomeLayout.setVisibility(View.GONE);
        gameLayout.setVisibility(View.VISIBLE);
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

        promptHistory.append("Мастер: ");
        promptHistory.append(text);
        promptHistory.append("\n\n");
    }

    private void appendPlayerMessage(String text) {
        appendColoredText("Ты: " + text + "\n\n", COLOR_PLAYER);

        promptHistory.append("Игрок: ");
        promptHistory.append(text);
        promptHistory.append("\n\n");
    }

    private void appendSystemMessage(String text) {
//        appendColoredText("Система: " + text + "\n\n", COLOR_SYSTEM);
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
}