package com.example.mydnd.llm;

public class NativeLlmBridge {

    static {
        System.loadLibrary("mydnd_native");
    }

    public native String nativePing();

    public native long nativeLoadModel(String modelPath);

    public native String nativeGenerate(long handle, String prompt, int maxTokens);

    public native void nativeRelease(long handle);
}