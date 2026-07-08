package com.example.mydnd.llm;

public class NativeLlmBridge {

    static {
        System.loadLibrary("mydnd_native");
    }

    public native String nativePing();

    public native long nativeLoadModel(
            String modelPath
    );

    public native String nativeGenerate(
            long handle,
            String prompt,
            int maxTokens
    );

    public native String nativeGenerateStream(
            long handle,
            String prompt,
            String metadataPrompt,
            int maxTokens,
            float temperature,
            float topP,
            int topK,
            float repeatPenalty,
            boolean useMetadataPhase,
            NativeTokenCallback callback
    );

    public native String nativeGenerateDirectorAwareStream(
            long handle,
            String prompt,
            String directorMode,
            int maxTokens,
            float temperature,
            float topP,
            int topK,
            float repeatPenalty,
            boolean runWorldEventPhase,
            String worldEventBatchText,
            NativeTokenCallback tokenCallback,
            NativeToolCallback toolCallback
    );

    public native void nativeRelease(
            long handle
    );

    public native void nativeCancel(
            long handle
    );
}
