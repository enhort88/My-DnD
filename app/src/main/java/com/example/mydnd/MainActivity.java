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


public class MainActivity extends Activity {

    private TextView chatTextView;
    private EditText inputEditText;
    private ScrollView chatScrollView;
    private Button sendButton;

    private LlmEngine llmEngine;

    private final StringBuilder chatHistory = new StringBuilder();

    private CheckBox useThinkingCheckBox;
    private CheckBox showThinkingCheckBox;

    private final ThinkBlockFilter thinkBlockFilter = new ThinkBlockFilter();

    private static final String SYSTEM_PROMPT =
            "Ты — мастер настольной RPG в духе DnD. " +
                    "Веди сцену, описывай мир, играй NPC и реагируй на действия игрока. " +
                    "Не решай за персонажа игрока. " +
                    "Если нужна проверка, попроси бросок кубика. " +
                    "Не бросай кубики сам. " +
                    "Отвечай на русском языке. " +
                    "Пиши атмосферно, но кратко.";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

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

        startFirstScene();

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
        chatHistory.append("Мастер: ");
        chatHistory.append("Ты стоишь у входа в старую придорожную таверну. ");
        chatHistory.append("За мутными окнами дрожит жёлтый свет. ");
        chatHistory.append("Дождь стучит по крыше, а где-то рядом воет пёс. ");
        chatHistory.append("Дверь приоткрыта, но внутри подозрительно тихо.");
        chatHistory.append("\n\n");

//        chatHistory.append("Система: UI готов. Можно писать действие.\n\n");

        sendButton.setEnabled(true);
        sendButton.setText("Отправить");

        updateChat();
    }

    private void sendPlayerMessage() {
        String playerText = inputEditText.getText().toString().trim();

        if (playerText.isEmpty()) {
            return;
        }

        inputEditText.setText("");
        hideKeyboard();

        chatHistory.append("Игрок: ");
        chatHistory.append(playerText);
        chatHistory.append("\n\n");

        updateChat();

        sendButton.setEnabled(true);
        sendButton.setText("Жду...");

        chatHistory.append("Система: запрос ушёл в LLM, жду ответ...\n\n");
        updateChat();

        String prompt = buildPrompt(playerText);

        llmEngine.generate(prompt, new LlmCallback() {
            @Override
            public void onToken(String token) {
                runOnUiThread(() -> {
                    chatHistory.append(token);
                    chatHistory.append("\n");
                    updateChat();
                });
            }

            @Override
            public void onComplete(String fullText) {
                runOnUiThread(() -> {
                    String visibleText = fullText;

                    if (!showThinkingCheckBox.isChecked()) {
                        visibleText = thinkBlockFilter.removeThinkBlocks(fullText);
                    }

                    chatHistory.append("Мастер: ");
                    chatHistory.append(visibleText);
                    chatHistory.append("\n\n");

                    updateChat();

                    sendButton.setEnabled(true);
                    sendButton.setText("Отправить");
                });
            }

            @Override
            public void onError(Throwable throwable) {
                runOnUiThread(() -> {
                    chatHistory.append("Ошибка LLM: ");
                    chatHistory.append(throwable.getMessage());
                    chatHistory.append("\n\n");

                    updateChat();

                    sendButton.setEnabled(true);
                    sendButton.setText("Отправить");
                });
            }
        });
    }

    private String buildPrompt(String playerText) {
        String thinkingMode;

        if (useThinkingCheckBox.isChecked()) {
            thinkingMode = "/think";
        } else {
            thinkingMode = "/no_think";
        }

        String recentHistory = getRecentGameHistoryForPrompt(500);

        return thinkingMode +
                "\nТы мастер DnD. Русский. Мрачное фэнтези. Кратко." +
                "\nНе решай за героя. Если риск — попроси проверку, кубик не бросай." +
                "\nСцена: ночная таверна, дождь, внутри подозрительно тихо." +
                "\nИстория:\n" + recentHistory +
                "\nИгрок: " + playerText +
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
        chatTextView.setText(chatHistory.toString());

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
        String history = chatHistory.toString();

        StringBuilder filtered = new StringBuilder();

        String[] lines = history.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("Система:")) {
                continue;
            }

            if (trimmed.startsWith("[Система]")) {
                continue;
            }

            if (trimmed.startsWith("Ошибка LLM:")) {
                continue;
            }

            filtered.append(line).append("\n");
        }

        String result = filtered.toString();

        if (result.length() <= maxChars) {
            return result;
        }

        return result.substring(result.length() - maxChars);
    }
}