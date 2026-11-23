package com.plezyplextozidoo;

import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Probes remote image dimensions without fully decoding pixels.
 * Used to enforce genuine-4K filtering (never treat upscales as 4K).
 */
final class ScreensaverArtProbe {

    private static final String TAG = "ScreensaverArtProbe";
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .followRedirects(true)
            .build();

    interface Callback {
        void onMeasured(int width, int height);
        void onError(String error);
    }

    private ScreensaverArtProbe() {
    }

    static void measure(String imageUrl, Callback callback) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            callback.onError("empty url");
            return;
        }
        EXECUTOR.execute(() -> {
            Request request = new Request.Builder().url(imageUrl).get().build();
            try (Response response = CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    callback.onError("HTTP " + response.code());
                    return;
                }
                try (InputStream in = response.body().byteStream()) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    BitmapFactory.decodeStream(in, null, opts);
                    if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                        callback.onError("undecodable bounds");
                        return;
                    }
                    callback.onMeasured(opts.outWidth, opts.outHeight);
                }
            } catch (IOException e) {
                Log.w(TAG, "measure failed: " + e.getMessage());
                callback.onError(e.getMessage());
            }
        });
    }
}
