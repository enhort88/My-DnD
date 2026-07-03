package com.example.mydnd.llm;


public interface LlmEngine {

    void generate(String prompt, GenerationProfile profile, LlmCallback callback);

    default void generate(String prompt, LlmCallback callback) {
        generate(prompt, GenerationProfile.normal(), callback);
    }

    void cancel();

    boolean isReady();
}
