package com.example.mydnd.util;

import android.content.Context;
import android.media.MediaPlayer;

import androidx.annotation.RawRes;

public final class MusicManager {

    private static MediaPlayer mediaPlayer;
    private static int currentTrack;

    private MusicManager() {
    }

    public static void play(Context context, @RawRes int trackResId) {
        if (mediaPlayer != null && currentTrack == trackResId) {
            if (!mediaPlayer.isPlaying()) {
                mediaPlayer.start();
            }
            return;
        }

        stop();

        currentTrack = trackResId;

        mediaPlayer = MediaPlayer.create(
                context.getApplicationContext(),
                trackResId
        );

        if (mediaPlayer == null) {
            currentTrack = 0;
            return;
        }

        mediaPlayer.setLooping(true);
        mediaPlayer.setVolume(0.25f, 0.25f);
        mediaPlayer.start();
    }

    public static void stop() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }

        currentTrack = 0;
    }
}