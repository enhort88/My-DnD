package com.example.mydnd.llm;

public interface NativeToolCallback {

    String onToolCall(
            String rawToolCall
    );
}
