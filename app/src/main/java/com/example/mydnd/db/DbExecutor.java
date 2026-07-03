package com.example.mydnd.db;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DbExecutor {

    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor();

    public static void execute(Runnable runnable) {
        EXECUTOR.execute(runnable);
    }
}