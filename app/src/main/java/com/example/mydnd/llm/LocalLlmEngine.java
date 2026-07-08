package com.example.mydnd.llm;

import android.util.Log;

import java.io.File;

public class LocalLlmEngine implements LlmEngine {

    private static final String TAG =
            "MyDND_LLM";

    private final NativeLlmBridge nativeBridge =
            new NativeLlmBridge();

    private final String modelPath;

    private final boolean useMetadataPhase;

    private long handle = 0L;

    private boolean cancelled = false;


    public LocalLlmEngine(
            String modelPath,
            boolean useMetadataPhase
    ) {
        this.modelPath = modelPath;
        this.useMetadataPhase = useMetadataPhase;
    }


    public void load() {
        if (handle != 0L) {
            return;
        }

        File modelFile =
                new File(modelPath);

        if (!modelFile.exists()) {
            throw new IllegalStateException(
                    "Файл модели не найден: "
                            + modelPath
            );
        }

        if (!modelFile.canRead()) {
            throw new IllegalStateException(
                    "Нет доступа на чтение модели: "
                            + modelPath
            );
        }

        handle =
                nativeBridge.nativeLoadModel(
                        modelPath
                );

        if (handle == 0L) {
            throw new IllegalStateException(
                    "Не удалось загрузить модель native-частью: "
                            + modelPath
            );
        }
    }


    @Override
    public void generate(
            String prompt,
            GenerationProfile profile,
            LlmCallback callback
    ) {
        generate(
                prompt,
                "",
                profile,
                callback
        );
    }


    public void generate(
            String prompt,
            String metadataPrompt,
            GenerationProfile profile,
            LlmCallback callback
    ) {
        cancelled = false;

        new Thread(
                () -> {
                    try {
                        Log.d(
                                TAG,
                                "generate(): started"
                        );

                        load();

                        Log.d(
                                TAG,
                                "generate(): model loaded"
                                        + ", metadataPhase="
                                        + useMetadataPhase
                        );

                        if (cancelled) {
                            Log.d(
                                    TAG,
                                    "generate(): cancelled after load"
                            );

                            callback.onComplete("");
                            return;
                        }

                        NativeTokenCallback nativeCallback =
                                new NativeTokenCallback() {

                                    @Override
                                    public void onToken(
                                            String token
                                    ) {
                                        callback.onToken(token);
                                    }
                                };

                        String safeMetadataPrompt =
                                metadataPrompt == null
                                        ? ""
                                        : metadataPrompt;

                        String answer =
                                nativeBridge.nativeGenerateStream(
                                        handle,
                                        prompt,
                                        safeMetadataPrompt,
                                        profile.getMaxTokens(),
                                        profile.getTemperature(),
                                        profile.getTopP(),
                                        profile.getTopK(),
                                        profile.getRepeatPenalty(),
                                        useMetadataPhase,
                                        nativeCallback
                                );

                        Log.d(
                                TAG,
                                "generate(): nativeGenerate finished"
                        );

                        callback.onComplete(answer);

                    } catch (Throwable throwable) {
                        Log.e(
                                TAG,
                                "generate(): error",
                                throwable
                        );

                        callback.onError(throwable);
                    }
                },
                "LocalLlmEngineThread"
        ).start();
    }


    public void generateDirectorAware(
            String prompt,
            String directorMode,
            GenerationProfile profile,
            boolean runWorldEventPhase,
            String worldEventBatchText,
            NativeToolCallback toolCallback,
            LlmCallback callback
    ) {
        cancelled = false;

        new Thread(
                () -> {
                    try {
                        Log.d(
                                TAG,
                                "generateDirectorAware(): started"
                        );

                        load();

                        Log.d(
                                TAG,
                                "generateDirectorAware(): model loaded"
                        );

                        if (cancelled) {
                            Log.d(
                                    TAG,
                                    "generateDirectorAware(): cancelled after load"
                            );

                            callback.onComplete("");
                            return;
                        }

                        NativeTokenCallback nativeTokenCallback =
                                new NativeTokenCallback() {

                                    @Override
                                    public void onToken(
                                            String token
                                    ) {
                                        callback.onToken(token);
                                    }
                                };

                        String answer =
                                nativeBridge.nativeGenerateDirectorAwareStream(
                                        handle,
                                        prompt,
                                        directorMode,
                                        profile.getMaxTokens(),
                                        profile.getTemperature(),
                                        profile.getTopP(),
                                        profile.getTopK(),
                                        profile.getRepeatPenalty(),
                                        runWorldEventPhase,
                                        worldEventBatchText,
                                        nativeTokenCallback,
                                        toolCallback
                                );

                        Log.d(
                                TAG,
                                "generateDirectorAware(): native generation finished"
                        );

                        if (answer != null
                                && answer.startsWith("Ошибка:")) {
                            throw new IllegalStateException(answer);
                        }

                        callback.onComplete(answer);

                    } catch (Throwable throwable) {
                        Log.e(
                                TAG,
                                "generateDirectorAware(): error",
                                throwable
                        );

                        callback.onError(throwable);
                    }
                },
                "LocalLlmDirectorAwareThread"
        ).start();
    }


    @Override
    public void cancel() {
        cancelled = true;

        if (handle != 0L) {
            nativeBridge.nativeCancel(handle);
        }
    }


    @Override
    public boolean isReady() {
        return handle != 0L;
    }


    public void release() {
        if (handle == 0L) {
            return;
        }

        nativeBridge.nativeRelease(handle);
        handle = 0L;
    }
}