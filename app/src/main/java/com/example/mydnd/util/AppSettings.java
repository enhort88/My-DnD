package com.example.mydnd.util;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppSettings {

    public static final String RESPONSE_FAST = "FAST";
    public static final String RESPONSE_NORMAL = "NORMAL";
    public static final String RESPONSE_ATMOSPHERIC = "ATMOSPHERIC";

    private static final String PREFS_NAME = "pocket_dnd_settings";
    private static final String KEY_MUSIC_ENABLED = "music_enabled";
    private static final String KEY_MUSIC_VOLUME = "music_volume";
    private static final String KEY_RESPONSE_MODE = "response_mode";

    private AppSettings() {
    }

    public static boolean isMusicEnabled(Context context) {
        return preferences(context).getBoolean(
                KEY_MUSIC_ENABLED,
                true
        );
    }

    public static void setMusicEnabled(
            Context context,
            boolean enabled
    ) {
        preferences(context)
                .edit()
                .putBoolean(KEY_MUSIC_ENABLED, enabled)
                .apply();
    }

    public static float getMusicVolume(Context context) {
        float value = preferences(context).getFloat(
                KEY_MUSIC_VOLUME,
                0.25f
        );

        return clampVolume(value);
    }

    public static void setMusicVolume(
            Context context,
            float volume
    ) {
        preferences(context)
                .edit()
                .putFloat(KEY_MUSIC_VOLUME, clampVolume(volume))
                .apply();
    }

    public static String getResponseMode(Context context) {
        return normalizeResponseMode(
                preferences(context).getString(
                        KEY_RESPONSE_MODE,
                        RESPONSE_NORMAL
                )
        );
    }

    public static void setResponseMode(
            Context context,
            String mode
    ) {
        preferences(context)
                .edit()
                .putString(
                        KEY_RESPONSE_MODE,
                        normalizeResponseMode(mode)
                )
                .apply();
    }

    public static String normalizeResponseMode(String mode) {
        if (RESPONSE_FAST.equals(mode)) {
            return RESPONSE_FAST;
        }

        if (RESPONSE_ATMOSPHERIC.equals(mode)) {
            return RESPONSE_ATMOSPHERIC;
        }

        return RESPONSE_NORMAL;
    }

    private static float clampVolume(float value) {
        if (value < 0f) {
            return 0f;
        }

        if (value > 1f) {
            return 1f;
        }

        return value;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(
                        PREFS_NAME,
                        Context.MODE_PRIVATE
                );
    }
}
