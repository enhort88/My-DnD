package com.example.mydnd.llm;


public interface LlmEngine {

    void generate(String prompt, LlmCallback callback);

    void cancel();

    boolean isReady();
}
