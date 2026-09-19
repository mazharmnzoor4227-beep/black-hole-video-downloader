package com.blackhole.downloader;

import android.app.Application;
import android.util.Log;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BlackHoleApplication extends Application {
    private static final String TAG = "BlackHoleApp";
    private final ExecutorService initExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();

        // Warm the on-device extractor in the background so the home screen stays instant.
        initExecutor.execute(() -> {
            try {
                YoutubeDL.getInstance().init(this);
            } catch (Throwable error) {
                Log.w(TAG, "Extractor warm-up failed", error);
            }

            try {
                FFmpeg.getInstance().init(this);
            } catch (Throwable error) {
                // FFmpeg is optional at runtime. MainActivity falls back to a combined stream.
                Log.w(TAG, "FFmpeg warm-up unavailable; fallback mode will be used", error);
            }
        });
    }
}
