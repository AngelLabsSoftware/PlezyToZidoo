package com.plezyplextozidoo;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;

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

    /**
     * Ken Burns end scale: ~5% zoom over each slide (~2× the prior 2.5% rate at the
     * same interval; still linear/smooth, not snappy).
     */
    static final float KEN_BURNS_END_SCALE = 1.05f;

    /** Pref value: reduced logo (~25% of prior layout size). Default. */
    static final String LOGO_SIZE_SMALL = "small";
    /** Pref value: clearLogo at native file/pixel size. */
    static final String LOGO_SIZE_ORIGINAL = "original";

    /**
     * Small logo: height was 120dp; target ~25% → 30dp. Max width was 720dp → 180dp.
     * Also capped as a fraction of the screen; never upscaled past native pixels.
     */
    static final float LOGO_HEIGHT_DP = 30f;
    static final float LOGO_MAX_WIDTH_DP = 180f;
    static final float LOGO_MAX_SCREEN_WIDTH_FRAC = 0.18f;
    static final float LOGO_MAX_SCREEN_HEIGHT_FRAC = 0.08f;

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
    private final LinearInterpolator kenBurnsInterpolator = new LinearInterpolator();

    private String serverUrl = "";
    private String token = "";
    private int index = 0;
    private boolean showingPrimary = true;
    private boolean allowLowerRes;
    private boolean showLogo;
    private boolean showDate;
    /** {@link #LOGO_SIZE_SMALL} or {@link #LOGO_SIZE_ORIGINAL}. */
    private String logoSize = LOGO_SIZE_SMALL;
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
        cancelArtMotion(artView);
        cancelArtMotion(artNextView);
        logoView.animate().cancel();
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
        logoSize = normalizeLogoSize(prefs.getString("screensaver_logo_size", LOGO_SIZE_SMALL));
        int seconds = 20;
        try {
            seconds = Integer.parseInt(prefs.getString("screensaver_interval_sec", "20"));
        } catch (Exception ignored) {
        }
        intervalMs = Math.max(MIN_INTERVAL_MS, seconds * 1000L);
    }

    static String normalizeLogoSize(@Nullable String value) {
        if (LOGO_SIZE_ORIGINAL.equals(value)) {
            return LOGO_SIZE_ORIGINAL;
        }
        return LOGO_SIZE_SMALL;
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

        // Reset only the incoming layer so the outgoing Ken Burns continues through the fade.
        prepareIncomingArt(incoming);

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

        startKenBurns(incoming);

        // Property alpha (not Animation) so scale and fade share one compositor path — no jumps.
        incoming.animate()
                .alpha(1f)
                .setDuration(CROSSFADE_MS)
                .setInterpolator(kenBurnsInterpolator)
                .start();

        if (outgoing.getVisibility() == View.VISIBLE) {
            // Animate alpha only — do not cancel the outgoing scale animator.
            outgoing.animate()
                    .alpha(0f)
                    .setDuration(CROSSFADE_MS)
                    .setInterpolator(kenBurnsInterpolator)
                    .withEndAction(() -> {
                        if (!running.get()) return;
                        cancelArtMotion(outgoing);
                        outgoing.setVisibility(View.GONE);
                        try {
                            Glide.with(appContext).clear(outgoing);
                        } catch (Exception e) {
                            Log.w(TAG, "Glide clear outgoing: " + e.getMessage());
                        }
                    })
                    .start();
        }

        showingPrimary = !showingPrimary;

        // Transparent logo only — never invent text substitutes.
        if (showLogo && item.hasTransparentLogo()) {
            String logoUrl = PlexApi.buildImageUrl(serverUrl, item.logoPath, token);
            logoView.setVisibility(View.VISIBLE);
            logoView.setAlpha(0f);
            final String sizeMode = logoSize;
            int[] max = logoMaxPx(root, sizeMode);
            Glide.with(appContext)
                    .load(logoUrl)
                    .apply(new RequestOptions()
                            .fitCenter()
                            .diskCacheStrategy(DiskCacheStrategy.NONE)
                            .skipMemoryCache(false))
                    .listener(new RequestListener<Drawable>() {
                        @Override
                        public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                    Target<Drawable> target, boolean isFirstResource) {
                            return false;
                        }

                        @Override
                        public boolean onResourceReady(Drawable resource, Object model,
                                                       Target<Drawable> target, DataSource dataSource,
                                                       boolean isFirstResource) {
                            handler.post(() -> applyLogoSize(logoView, resource, max[0], max[1],
                                    sizeMode));
                            return false;
                        }
                    })
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

    private void prepareIncomingArt(ImageView incoming) {
        incoming.animate().cancel();
        incoming.setScaleX(1f);
        incoming.setScaleY(1f);
        incoming.setAlpha(0f);
        incoming.setVisibility(View.VISIBLE);
        centerPivot(incoming);
    }

    private void startKenBurns(ImageView view) {
        centerPivot(view);
        // Continue past the advance tick so the outgoing layer keeps zooming through crossfade.
        long duration = Math.max(MIN_INTERVAL_MS, intervalMs + CROSSFADE_MS);
        view.animate()
                .scaleX(KEN_BURNS_END_SCALE)
                .scaleY(KEN_BURNS_END_SCALE)
                .setDuration(duration)
                .setInterpolator(kenBurnsInterpolator)
                .start();
    }

    private static void centerPivot(ImageView view) {
        int w = view.getWidth();
        int h = view.getHeight();
        if (w > 0 && h > 0) {
            view.setPivotX(w / 2f);
            view.setPivotY(h / 2f);
        } else {
            view.post(() -> {
                view.setPivotX(view.getWidth() / 2f);
                view.setPivotY(view.getHeight() / 2f);
            });
        }
    }

    private void cancelArtMotion(ImageView view) {
        view.animate().cancel();
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setAlpha(0f);
    }

    /**
     * Fits logo into max box, preserves aspect, never upscales past native pixels.
     * Returns {@code [widthPx, heightPx]}.
     */
    static int[] fittedLogoSize(int intrinsicW, int intrinsicH, int maxW, int maxH) {
        if (intrinsicW <= 0 || intrinsicH <= 0 || maxW <= 0 || maxH <= 0) {
            return new int[]{0, 0};
        }
        float scale = Math.min(1f, Math.min((float) maxW / intrinsicW, (float) maxH / intrinsicH));
        return new int[]{Math.max(1, Math.round(intrinsicW * scale)),
                Math.max(1, Math.round(intrinsicH * scale))};
    }

    /**
     * Display size for a logo: native pixels when {@link #LOGO_SIZE_ORIGINAL}, otherwise
     * fitted into the max box. Never upscales past intrinsic size.
     */
    static int[] resolveLogoDisplaySize(int intrinsicW, int intrinsicH, int maxW, int maxH,
                                        @Nullable String sizeMode) {
        if (intrinsicW <= 0 || intrinsicH <= 0) {
            return new int[]{0, 0};
        }
        if (LOGO_SIZE_ORIGINAL.equals(normalizeLogoSize(sizeMode))) {
            // Native file/pixel size; only shrink if larger than the available screen box.
            if (maxW <= 0 || maxH <= 0) {
                return new int[]{intrinsicW, intrinsicH};
            }
            if (intrinsicW <= maxW && intrinsicH <= maxH) {
                return new int[]{intrinsicW, intrinsicH};
            }
            return fittedLogoSize(intrinsicW, intrinsicH, maxW, maxH);
        }
        return fittedLogoSize(intrinsicW, intrinsicH, maxW, maxH);
    }

    /**
     * Max logo box in px. Small: density + screen-fraction caps (~25% of prior 120×720dp).
     * Original: full screen bounds (native pixels applied in {@link #resolveLogoDisplaySize}).
     */
    static int[] logoMaxPx(View root, @Nullable String sizeMode) {
        DisplayMetrics dm = root.getResources().getDisplayMetrics();
        int screenW = root.getWidth() > 0 ? root.getWidth() : dm.widthPixels;
        int screenH = root.getHeight() > 0 ? root.getHeight() : dm.heightPixels;
        if (LOGO_SIZE_ORIGINAL.equals(normalizeLogoSize(sizeMode))) {
            return new int[]{Math.max(1, screenW), Math.max(1, screenH)};
        }
        int fromDpW = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, LOGO_MAX_WIDTH_DP, dm));
        int fromDpH = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, LOGO_HEIGHT_DP, dm));
        int maxW = Math.min(fromDpW, Math.round(screenW * LOGO_MAX_SCREEN_WIDTH_FRAC));
        int maxH = Math.min(fromDpH, Math.round(screenH * LOGO_MAX_SCREEN_HEIGHT_FRAC));
        return new int[]{Math.max(1, maxW), Math.max(1, maxH)};
    }

    /** @deprecated Prefer {@link #logoMaxPx(View, String)}; defaults to small. */
    static int[] logoMaxPx(View root) {
        return logoMaxPx(root, LOGO_SIZE_SMALL);
    }

    private static void applyLogoSize(ImageView logoView, Drawable resource, int maxW, int maxH,
                                      @Nullable String sizeMode) {
        if (logoView == null || resource == null) return;
        int[] size = resolveLogoDisplaySize(resource.getIntrinsicWidth(),
                resource.getIntrinsicHeight(), maxW, maxH, sizeMode);
        if (size[0] <= 0 || size[1] <= 0) return;
        ViewGroup.LayoutParams lp = logoView.getLayoutParams();
        if (lp == null) return;
        lp.width = size[0];
        lp.height = size[1];
        logoView.setLayoutParams(lp);
        logoView.setScaleType(ImageView.ScaleType.FIT_START);
        logoView.setAdjustViewBounds(true);
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
                        int[] max = logoMaxPx(root, logoSize);
                        RequestOptions logoOpts = new RequestOptions()
                                .diskCacheStrategy(DiskCacheStrategy.NONE);
                        if (LOGO_SIZE_SMALL.equals(logoSize)) {
                            logoOpts = logoOpts.override(max[0], max[1]);
                            Glide.with(appContext).load(logoUrl).apply(logoOpts)
                                    .preload(max[0], max[1]);
                        } else {
                            // Original: preload at native size (no forced downscale override).
                            Glide.with(appContext).load(logoUrl).apply(logoOpts).preload();
                        }
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
