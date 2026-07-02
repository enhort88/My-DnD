package com.example.mydnd.llm;

import android.os.Handler;
import android.os.Looper;

public class StubLlmEngine implements LlmEngine {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean cancelled = false;

    @Override
    public void generate(String prompt, LlmCallback callback) {
        cancelled = false;

        String answer =
                "<think>\n" +
                        "Игрок входит в опасное место. Нужно создать напряжение, но не решать за него.\n" +
                        "</think>\n\n" +
                        "Дверь поддаётся с тяжёлым скрипом. Изнутри тянет холодом, " +
                        "а на полу видны мокрые следы, ведущие в темноту.\n\n" +
                        "Что ты будешь делать?";

        handler.postDelayed(() -> {
            if (cancelled) {
                return;
            }

            callback.onToken(answer);
            callback.onComplete(answer);
        }, 500);
    }

    @Override
    public void cancel() {
        cancelled = true;
    }

    @Override
    public boolean isReady() {
        return true;
    }
}