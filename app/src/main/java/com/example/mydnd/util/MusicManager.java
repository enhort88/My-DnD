package com.example.mydnd.util;

import android.content.Context;
import android.media.MediaPlayer;

import androidx.annotation.RawRes;

public final class MusicManager {

    private static MediaPlayer mediaPlayer;
    private static int requestedTrack;
    private static int loadedTrack;

    private MusicManager() {
    }

    public static void play(
            Context context,
            @RawRes int trackResId
    ) {
        requestedTrack = trackResId;

        if (!AppSettings.isMusicEnabled(context)) {
            releasePlayer();
            return;
        }

        if (mediaPlayer != null
                && loadedTrack == trackResId) {

            applyVolume(context);

            if (!mediaPlayer.isPlaying()) {
                mediaPlayer.start();
            }

            return;
        }

        releasePlayer();

        mediaPlayer = MediaPlayer.create(
                context.getApplicationContext(),
                trackResId
        );

        if (mediaPlayer == null) {
            loadedTrack = 0;
            return;
        }

        loadedTrack = trackResId;
        mediaPlayer.setLooping(true);
        applyVolume(context);
        mediaPlayer.start();
    }

    public static void refresh(Context context) {
        if (!AppSettings.isMusicEnabled(context)) {
            releasePlayer();
            return;
        }

        if (mediaPlayer != null) {
            applyVolume(context);
            return;
        }

        if (requestedTrack != 0) {
            play(context, requestedTrack);
        }
    }

    public static void stop() {
        releasePlayer();
        requestedTrack = 0;
    }

    private static void applyVolume(Context context) {
        if (mediaPlayer == null) {
            return;
        }

        float volume = AppSettings.getMusicVolume(context);
        mediaPlayer.setVolume(volume, volume);
    }

    private static void releasePlayer() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (IllegalStateException ignored) {
            }

            mediaPlayer.release();
            mediaPlayer = null;
        }

        loadedTrack = 0;
    }
}
