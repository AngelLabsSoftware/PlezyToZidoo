package com.plezyplextozidoo;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared Plex artwork screensaver renderer used by {@link PlexArtDreamService}
 * and {@link ScreensaverPreviewActivity}. Memory + preload only — no disk art cache.
 */
public final class ScreensaverRenderer {

    private static final String TAG = "ScreensaverRenderer";
    private static final long DEFAULT_INTERVAL_MS = 20_000L;
    private static final long MIN_INTERVAL_MS = 5_000L;
    private static final long CROSSFADE_MS = 900L;

    private final Context appContext;
    private final View root;
    private final ImageView artView;
    private final ImageView artNextView;
    private final ImageView logoView;
    private final TextView dateView;
    private final TextView statusView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<PlexApi.ScreensaverItem> playlist = new ArrayList<>();

    private String serverUrl = "";
    private String token = "";
    private int index = 0;
    private boolean showingPrimary = true;
    private boolean allowLowerRes;
    private boolean showLogo;
    private boolean showDate;
    private long intervalMs = DEFAULT_INTERVAL_MS;

    private final Runnable advanceRunnable = this::advance;

    public ScreensaverRenderer(Context context, View root) {
        this.appContext = context.getApplicationContext();
        this.root = root;
        this.artView = root.findViewById(R.id.screensaver_art);
        this.artNextView = root.findViewById(R.id.screensaver_art_next);
        this.logoView = root.findViewById(R.id.screensaver_logo);
        this.dateView = root.findViewById(R.id.screensaver_date);
        this.statusView = root.findViewById(R.id.screensaver_status);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        loadPrefs();
        status("Loading Plex artwork…");
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(appContext);
        serverUrl = prefs.getString("plex_server_url", "");
        token = SecureStorage.getInstance(appContext).getString("plex_access_token", "");
        if (serverUrl == null) serverUrl = "";
        if (token == null) token = "";

        if (serverUrl.isEmpty() || token.isEmpty()) {
            status("Set Plex server URL and token in Settings to use the screensaver.");
            return;
        }

        PlexApi.listScreensaverCandidates(serverUrl, token, new PlexApi.ScreensaverCallback() {
            @Override
            public void onReady(List<PlexApi.ScreensaverItem> items) {
                if (!running.get()) return;
                if (items == null || items.isEmpty()) {
                    status("No Plex movies/shows with artwork found.");
                    return;
                }
                playlist.clear();
                playlist.addAll(items);
                index = 0;
                statusView.setVisibility(View.GONE);
                showCurrentThenSchedule();
            }

            @Override
            public void onError(String error) {
                if (!running.get()) return;
                status("Plex error: " + error);
            }
        });
    }

    public void stop() {
        running.set(false);
        handler.removeCallbacksAndMessages(null);
        try {
            Glide.with(appContext).clear(artView);
            Glide.with(appContext).clear(artNextView);
            Glide.with(appContext).clear(logoView);
        } catch (Exception e) {
            Log.w(TAG, "Glide clear: " + e.getMessage());
        }
    }

    private void loadPrefs() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(appContext);
        allowLowerRes = prefs.getBoolean("screensaver_allow_lower_res", false);
        showLogo = prefs.getBoolean("screensaver_show_logo", true);
        showDate = prefs.getBoolean("screensaver_show_date", true);
        int seconds = 20;
        try {
            seconds = Integer.parseInt(prefs.getString("screensaver_interval_sec", "20"));
        } catch (Exception ignored) {
        }
        intervalMs = Math.max(MIN_INTERVAL_MS, seconds * 1000L);
    }

    private void showCurrentThenSchedule() {
        if (!running.get() || playlist.isEmpty()) return;
        tryShowAt(index, 0);
    }

    private void tryShowAt(int startIndex, int attempts) {
        if (!running.get() || playlist.isEmpty()) return;
        if (attempts >= playlist.size()) {
            status(allowLowerRes
                    ? "No displayable Plex artwork."
                    : "No genuine 4K artwork found. Enable “Allow lower-res art” in Settings, or add 4K fanart in Plex.");
            return;
        }
        int i = Math.floorMod(startIndex, playlist.size());
        PlexApi.ScreensaverItem item = playlist.get(i);
        String artUrl = PlexApi.buildImageUrl(serverUrl, item.artPath, token);
        ScreensaverArtProbe.measure(artUrl, new ScreensaverArtProbe.Callback() {
            @Override
            public void onMeasured(int width, int height) {
                handler.post(() -> {
                    if (!running.get()) return;
                    boolean ok = allowLowerRes || PlexApi.meetsGenuine4k(width, height);
                    if (!ok) {
                        Log.d(TAG, "Skip non-4K art " + width + "x" + height + " for " + item.title);
                        tryShowAt(i + 1, attempts + 1);
                        return;
                    }
                    index = i;
                    displayItem(item, artUrl);
                    preloadNext();
                    handler.removeCallbacks(advanceRunnable);
                    handler.postDelayed(advanceRunnable, intervalMs);
                });
            }

            @Override
            public void onError(String error) {
                handler.post(() -> {
                    if (!running.get()) return;
                    tryShowAt(i + 1, attempts + 1);
                });
            }
        });
    }

    private void advance() {
        if (!running.get() || playlist.isEmpty()) return;
        tryShowAt(index + 1, 0);
    }

    private void displayItem(PlexApi.ScreensaverItem item, String artUrl) {
        statusView.setVisibility(View.GONE);
        ImageView incoming = showingPrimary ? artNextView : artView;
        ImageView outgoing = showingPrimary ? artView : artNextView;

        incoming.setVisibility(View.VISIBLE);
        incoming.setAlpha(0f);

        int w = root.getWidth() > 0 ? root.getWidth() : 1920;
        int h = root.getHeight() > 0 ? root.getHeight() : 1080;

        Glide.with(appContext)
                .load(artUrl)
                .apply(new RequestOptions()
                        .override(w, h)
                        .centerCrop()
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .skipMemoryCache(false))
                .into(incoming);

        // Crossfade
        AlphaAnimation in = new AlphaAnimation(0f, 1f);
        in.setDuration(CROSSFADE_MS);
        in.setFillAfter(true);
        AlphaAnimation out = new AlphaAnimation(1f, 0f);
        out.setDuration(CROSSFADE_MS);
        out.setFillAfter(true);
        out.setAnimationListener(new Animation.AnimationListener() {
            @Override public void onAnimationStart(Animation animation) { }
            @Override public void onAnimationRepeat(Animation animation) { }
            @Override
            public void onAnimationEnd(Animation animation) {
                outgoing.setAlpha(0f);
                outgoing.setVisibility(View.GONE);
                Glide.with(appContext).clear(outgoing);
            }
        });
        incoming.setAlpha(1f);
        incoming.startAnimation(in);
        if (outgoing.getVisibility() == View.VISIBLE) {
            outgoing.startAnimation(out);
        }
        showingPrimary = !showingPrimary;

        // Transparent logo only — never invent text substitutes.
        if (showLogo && item.hasTransparentLogo()) {
            String logoUrl = PlexApi.buildImageUrl(serverUrl, item.logoPath, token);
            logoView.setVisibility(View.VISIBLE);
            logoView.setAlpha(0f);
            Glide.with(appContext)
                    .load(logoUrl)
                    .apply(new RequestOptions()
                            .fitCenter()
                            .diskCacheStrategy(DiskCacheStrategy.NONE)
                            .skipMemoryCache(false))
                    .into(logoView);
            logoView.animate().alpha(1f).setDuration(CROSSFADE_MS).start();
        } else {
            logoView.animate().cancel();
            logoView.setVisibility(View.GONE);
            Glide.with(appContext).clear(logoView);
        }

        if (showDate) {
            String label = PlexApi.formatReleaseLabel(item.originallyAvailableAt);
            if (!label.isEmpty()) {
                dateView.setText(label);
                dateView.setVisibility(View.VISIBLE);
            } else {
                dateView.setVisibility(View.GONE);
            }
        } else {
            dateView.setVisibility(View.GONE);
        }
    }

    private void preloadNext() {
        if (playlist.isEmpty()) return;
        int next = Math.floorMod(index + 1, playlist.size());
        // Probe a few ahead for a 4K candidate to warm Glide memory cache.
        preloadFrom(next, 0);
    }

    private void preloadFrom(int start, int attempts) {
        if (!running.get() || attempts >= Math.min(5, playlist.size())) return;
        int i = Math.floorMod(start, playlist.size());
        PlexApi.ScreensaverItem item = playlist.get(i);
        String artUrl = PlexApi.buildImageUrl(serverUrl, item.artPath, token);
        ScreensaverArtProbe.measure(artUrl, new ScreensaverArtProbe.Callback() {
            @Override
            public void onMeasured(int width, int height) {
                if (!running.get()) return;
                if (!allowLowerRes && !PlexApi.meetsGenuine4k(width, height)) {
                    preloadFrom(i + 1, attempts + 1);
                    return;
                }
                int w = root.getWidth() > 0 ? root.getWidth() : 1920;
                int h = root.getHeight() > 0 ? root.getHeight() : 1080;
                handler.post(() -> {
                    if (!running.get()) return;
                    Glide.with(appContext)
                            .load(artUrl)
                            .apply(new RequestOptions()
                                    .override(w, h)
                                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                                    .skipMemoryCache(false))
                            .preload(w, h);
                    if (showLogo && item.hasTransparentLogo()) {
                        String logoUrl = PlexApi.buildImageUrl(serverUrl, item.logoPath, token);
                        Glide.with(appContext)
                                .load(logoUrl)
                                .apply(new RequestOptions().diskCacheStrategy(DiskCacheStrategy.NONE))
                                .preload();
                    }
                });
            }

            @Override
            public void onError(String error) {
                preloadFrom(i + 1, attempts + 1);
            }
        });
    }

    private void status(@Nullable String message) {
        if (message == null || message.isEmpty()) {
            statusView.setVisibility(View.GONE);
            return;
        }
        statusView.setText(message);
        statusView.setVisibility(View.VISIBLE);
    }
}
