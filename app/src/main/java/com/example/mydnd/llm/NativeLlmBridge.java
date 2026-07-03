package com.example.mydnd.llm;

public class NativeLlmBridge {

    static {
        System.loadLibrary("mydnd_native");
    }

    public native String nativePing();

    public native long nativeLoadModel(String modelPath);

    public native String nativeGenerate(long handle, String prompt, int maxTokens);

    public native String nativeGenerateStream(
            long handle,
            String prompt,
            int maxTokens,
            float temperature,
            float topP,
            int topK,
            float repeatPenalty,
            NativeTokenCallback callback
    );

    public native void nativeRelease(long handle);

    public native void nativeCancel(long handle);
}