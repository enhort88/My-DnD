package com.example.mydnd.llm;

import android.util.Log;

public class LlmModelManager {

    private static final String TAG =
            "MyDND_MODEL";

    private final Object lock =
            new Object();

    private final LocalLlmEngine masterEngine;

    private final LocalLlmEngine serviceEngine;

    private LocalLlmEngine activeEngine;

    private ModelRole activeRole;

    private boolean busy = false;


    public LlmModelManager(
            String masterModelPath,
            String serviceModelPath
    ) {
        masterEngine =
                new LocalLlmEngine(
                        masterModelPath,
                        true
                );

        serviceEngine =
                new LocalLlmEngine(
                        serviceModelPath,
                        false
                );
    }


    public void generate(
            ModelRole role,
            String prompt,
            GenerationProfile profile,
            LlmCallback callback
    ) {
        generate(
                role,
                prompt,
                "",
                profile,
                callback
        );
    }


    public void generate(
            ModelRole role,
            String prompt,
            String metadataPrompt,
            GenerationProfile profile,
            LlmCallback callback
    ) {
        final LocalLlmEngine engine;

        synchronized (lock) {
            if (busy) {
                callback.onError(
                        new IllegalStateException(
                                "Другая генерация уже выполняется"
                        )
                );
                return;
            }

            engine = switchToLocked(role);
            busy = true;
        }

        Log.d(
                TAG,
                "generate(): role=" + role
        );

        engine.generate(
                prompt,
                metadataPrompt,
                profile,
                new LlmCallback() {

                    @Override
                    public void onToken(
                            String token
                    ) {
                        callback.onToken(token);
                    }


                    @Override
                    public void onComplete(
                            String fullText
                    ) {
                        finishGeneration();

                        Log.d(
                                TAG,
                                "generate(): completed, role="
                                        + role
                        );

                        callback.onComplete(fullText);
                    }


                    @Override
                    public void onError(
                            Throwable throwable
                    ) {
                        finishGeneration();

                        Log.e(
                                TAG,
                                "generate(): failed, role="
                                        + role,
                                throwable
                        );

                        callback.onError(throwable);
                    }
                }
        );
    }


    public void cancelCurrent() {
        LocalLlmEngine engine;

        synchronized (lock) {
            engine = activeEngine;
        }

        if (engine == null) {
            return;
        }

        Log.d(
                TAG,
                "cancelCurrent(): role="
                        + activeRole
        );

        engine.cancel();
    }


    public boolean isBusy() {
        synchronized (lock) {
            return busy;
        }
    }


    public ModelRole getActiveRole() {
        synchronized (lock) {
            return activeRole;
        }
    }


    private LocalLlmEngine switchToLocked(
            ModelRole role
    ) {
        if (activeEngine != null
                && activeRole == role) {

            Log.d(
                    TAG,
                    "switchToLocked(): reuse "
                            + role
            );

            return activeEngine;
        }

        if (activeEngine != null) {
            Log.d(
                    TAG,
                    "switchToLocked(): release "
                            + activeRole
            );

            activeEngine.release();
        }

        if (role == ModelRole.MASTER) {
            activeEngine = masterEngine;
        } else {
            activeEngine = serviceEngine;
        }

        activeRole = role;

        Log.d(
                TAG,
                "switchToLocked(): active role="
                        + activeRole
        );

        return activeEngine;
    }


    private void finishGeneration() {
        synchronized (lock) {
            busy = false;
        }
    }
}