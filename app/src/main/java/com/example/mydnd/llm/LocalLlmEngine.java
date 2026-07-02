package com.example.mydnd.llm;

import java.io.File;
import android.util.Log;

public class LocalLlmEngine implements LlmEngine {

    private final NativeLlmBridge bridge = new NativeLlmBridge();
    private final String modelPath;

    private long handle = 0L;
    private boolean cancelled = false;

    private static final String TAG = "MyDND_LLM";

    public LocalLlmEngine(String modelPath) {
        this.modelPath = modelPath;
    }

    public void load() {
        if (handle != 0L) {
            return;
        }

        File modelFile = new File(modelPath);

        if (!modelFile.exists()) {
            throw new IllegalStateException(
                    "Файл модели не найден: " + modelPath
            );
        }

        if (!modelFile.canRead()) {
            throw new IllegalStateException(
                    "Нет доступа на чтение модели: " + modelPath
            );
        }

        handle = bridge.nativeLoadModel(modelPath);

        if (handle == 0L) {
            throw new IllegalStateException(
                    "Не удалось загрузить модель native-частью: " + modelPath
            );
        }
    }

    @Override
    public void generate(String prompt, LlmCallback callback) {
        cancelled = false;

        new Thread(() -> {
            try {
                Log.d(TAG, "generate(): started");
                callback.onToken("[Система] Запрос принят. Загружаю модель...\n");

                load();

                Log.d(TAG, "generate(): model loaded");
                callback.onToken("[Система] Модель загружена. Генерирую ответ...\n");

                if (cancelled) {
                    Log.d(TAG, "generate(): cancelled after load");
                    return;
                }

                String answer = bridge.nativeGenerateStream(
                        handle,
                        prompt,
                        60,
                        token -> {
                            if (!cancelled) {
                                callback.onToken(token);
                            }
                        }
                );

                Log.d(TAG, "generate(): nativeGenerate finished");

                if (cancelled) {
                    Log.d(TAG, "generate(): cancelled after generation");
                    return;
                }

                callback.onComplete(answer);
            } catch (Throwable throwable) {
                Log.e(TAG, "generate(): error", throwable);
                callback.onError(throwable);
            }
        }, "LocalLlmEngineThread").start();
    }

    @Override
    public void cancel() {
        cancelled = true;
    }

    @Override
    public boolean isReady() {
        return handle != 0L;
    }

    public void release() {
        if (handle != 0L) {
            bridge.nativeRelease(handle);
            handle = 0L;
        }
    }
}