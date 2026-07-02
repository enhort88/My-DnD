package com.example.mydnd.llm;

public interface LlmCallback {

    void onToken(String token);

    void onComplete(String fullText);

    void onError(Throwable throwable);
}
