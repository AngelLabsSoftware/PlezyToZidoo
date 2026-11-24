package com.plezyplextozidoo;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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

    /** Pref: max 25% viewport width, left-aligned. */
    static final String LOGO_SIZE_SMALL = "small";
    /** Pref: max 50% viewport width, left-aligned. Default. */
    static final String LOGO_SIZE_MEDIUM = "medium";
    /** Pref: max 80% viewport width, horizontally centred (≥10% side padding). */
    static final String LOGO_SIZE_LARGE = "large";
    /** Legacy pref value from 11.7 — treated as Large. */
    static final String LOGO_SIZE_ORIGINAL = "original";

    static final float LOGO_WIDTH_FRAC_SMALL = 0.25f;
    static final float LOGO_WIDTH_FRAC_MEDIUM = 0.50f;
    static final float LOGO_WIDTH_FRAC_LARGE = 0.80f;
    /** Cap tall logos; never upscale past native. */
    static final float LOGO_MAX_HEIGHT_FRAC = 0.22f;
    static final float LOGO_SIDE_PAD_FRAC_LARGE = 0.10f;
    static final float OVERLAY_EDGE_PAD_DP = 48f;

    private final Context appContext;
    private final View root;
    private final ImageView artView;
    private final ImageView artNextView;
    private final ImageView logoView;
    private final TextView titleView;
    private final TextView dateView;
    private final TextView statusView;
    private final ProgressBar spinnerView;
    private final View overlayView;
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
    /** When false (default), skip titles without a usable clearLogo. */
    private boolean allowTextTitle;
    private boolean showDate;
    /** {@link #LOGO_SIZE_SMALL}, {@link #LOGO_SIZE_MEDIUM}, or {@link #LOGO_SIZE_LARGE}. */
    private String logoSize = LOGO_SIZE_MEDIUM;
    private long intervalMs = DEFAULT_INTERVAL_MS;

    private final Runnable advanceRunnable = this::advance;
    private final Runnable retryPlaylistRunnable = this::retryPlaylistLater;

    public ScreensaverRenderer(Context context, View root) {
        this.appContext = context.getApplicationContext();
        this.root = root;
        this.artView = root.findViewById(R.id.screensaver_art);
        this.artNextView = root.findViewById(R.id.screensaver_art_next);
        this.logoView = root.findViewById(R.id.screensaver_logo);
        this.titleView = root.findViewById(R.id.screensaver_title);
        this.dateView = root.findViewById(R.id.screensaver_date);
        this.statusView = root.findViewById(R.id.screensaver_status);
        this.spinnerView = root.findViewById(R.id.screensaver_spinner);
        this.overlayView = root.findViewById(R.id.screensaver_overlay);
    }

    private void retryPlaylistLater() {
        if (!running.get() || playlist.isEmpty()) return;
        hideStatusKeepSpinner(false);
        showLoadingSpinner();
        tryShowAt(0, 0);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        loadPrefs();
        showLoadingSpinner();
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
                // Keep spinner until first bg+logo (or text) is ready — hideStatus only after commit.
                showLoadingSpinner();
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
        if (titleView != null) {
            titleView.animate().cancel();
            titleView.setVisibility(View.GONE);
        }
        hideLoadingSpinner();
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
        allowTextTitle = prefs.getBoolean("screensaver_allow_text_title", false);
        showDate = prefs.getBoolean("screensaver_show_date", true);
        logoSize = normalizeLogoSize(prefs.getString("screensaver_logo_size", LOGO_SIZE_MEDIUM));
        int seconds = 20;
        try {
            seconds = Integer.parseInt(prefs.getString("screensaver_interval_sec", "20"));
        } catch (Exception ignored) {
        }
        intervalMs = Math.max(MIN_INTERVAL_MS, seconds * 1000L);
    }

    static String normalizeLogoSize(@Nullable String value) {
        if (LOGO_SIZE_SMALL.equals(value)) {
            return LOGO_SIZE_SMALL;
        }
        if (LOGO_SIZE_LARGE.equals(value) || LOGO_SIZE_ORIGINAL.equals(value)) {
            // Legacy "original" maps to Large (widest / centred).
            return LOGO_SIZE_LARGE;
        }
        return LOGO_SIZE_MEDIUM;
    }

    static float logoWidthFraction(@Nullable String sizeMode) {
        String mode = normalizeLogoSize(sizeMode);
        if (LOGO_SIZE_SMALL.equals(mode)) return LOGO_WIDTH_FRAC_SMALL;
        if (LOGO_SIZE_LARGE.equals(mode)) return LOGO_WIDTH_FRAC_LARGE;
        return LOGO_WIDTH_FRAC_MEDIUM;
    }

    static boolean isLogoCentered(@Nullable String sizeMode) {
        return LOGO_SIZE_LARGE.equals(normalizeLogoSize(sizeMode));
    }

    /**
     * Candidate gate: qualifying background plus usable clearLogo, unless text-title
     * fallback is enabled. Does not silently relax 4K / logo requirements.
     */
    static boolean isScreensaverCandidateEligible(PlexApi.ScreensaverItem item,
                                                  boolean allowTextTitleFallback) {
        if (item == null || item.artPath == null || item.artPath.isEmpty()) {
            return false;
        }
        if (item.hasTransparentLogo()) {
            return true;
        }
        return allowTextTitleFallback
                && item.title != null
                && !item.title.trim().isEmpty();
    }

    private void showCurrentThenSchedule() {
        if (!running.get() || playlist.isEmpty()) return;
        tryShowAt(index, 0);
    }

    private void tryShowAt(int startIndex, int attempts) {
        if (!running.get() || playlist.isEmpty()) return;
        if (attempts >= playlist.size()) {
            String msg;
            if (!allowLowerRes && !allowTextTitle) {
                msg = "No genuine 4K artwork with a usable logo. Enable “Allow lower-res art” or “Allow text title fallback”, or add 4K fanart + clearLogo in Plex.";
            } else if (!allowLowerRes) {
                msg = "No genuine 4K artwork found. Enable “Allow lower-res art” in Settings, or add 4K fanart in Plex.";
            } else if (!allowTextTitle) {
                msg = "No displayable Plex artwork with a usable logo. Enable “Allow text title fallback” or add clearLogo art in Plex.";
            } else {
                msg = "No displayable Plex artwork.";
            }
            status(msg);
            // Graceful stop + retry later without relaxing requirements (no infinite tight loop).
            handler.removeCallbacks(retryPlaylistRunnable);
            handler.postDelayed(retryPlaylistRunnable, Math.max(intervalMs * 2, 60_000L));
            return;
        }
        int i = Math.floorMod(startIndex, playlist.size());
        PlexApi.ScreensaverItem item = playlist.get(i);
        if (!isScreensaverCandidateEligible(item, allowTextTitle)) {
            Log.d(TAG, "Skip candidate without logo/title fallback: " + item.title);
            tryShowAt(i + 1, attempts + 1);
            return;
        }
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
                    // Preload+decode art (and logo when present) before committing the transition.
                    prepareAndDisplay(item, artUrl, i, attempts);
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

    /**
     * Decodes art (and logo if required) into memory before crossfading. Skips the
     * candidate if a required logo is missing or fails to decode.
     */
    private void prepareAndDisplay(PlexApi.ScreensaverItem item, String artUrl,
                                   int itemIndex, int attempts) {
        if (!running.get()) return;
        final boolean wantsLogo = showLogo && item.hasTransparentLogo();
        final boolean logoRequired = !allowTextTitle;
        int w = root.getWidth() > 0 ? root.getWidth() : 1920;
        int h = root.getHeight() > 0 ? root.getHeight() : 1080;

        Glide.with(appContext)
                .load(artUrl)
                .apply(new RequestOptions()
                        .override(w, h)
                        .centerCrop()
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .skipMemoryCache(false))
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                Target<Drawable> target, boolean isFirstResource) {
                        handler.post(() -> {
                            if (!running.get()) return;
                            Log.d(TAG, "Skip art decode failure for " + item.title);
                            tryShowAt(itemIndex + 1, attempts + 1);
                        });
                        return true;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model,
                                                   Target<Drawable> target, DataSource dataSource,
                                                   boolean isFirstResource) {
                        handler.post(() -> {
                            if (!running.get()) return;
                            if (wantsLogo) {
                                decodeLogoThenDisplay(item, artUrl, itemIndex, attempts, logoRequired);
                            } else if (logoRequired && !item.hasTransparentLogo()) {
                                // Should have been filtered earlier; skip rather than relax.
                                tryShowAt(itemIndex + 1, attempts + 1);
                            } else {
                                commitDisplay(item, artUrl, null, allowTextTitle);
                                index = itemIndex;
                                preloadNext();
                                handler.removeCallbacks(advanceRunnable);
                                handler.postDelayed(advanceRunnable, intervalMs);
                            }
                        });
                        return true; // we draw via displayItem ourselves
                    }
                })
                .preload(w, h);
    }

    private void decodeLogoThenDisplay(PlexApi.ScreensaverItem item, String artUrl,
                                       int itemIndex, int attempts, boolean logoRequired) {
        String logoUrl = PlexApi.buildImageUrl(serverUrl, item.logoPath, token);
        int[] max = logoMaxPx(root, logoSize);
        RequestOptions logoOpts = new RequestOptions()
                .fitCenter()
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(false)
                .override(max[0], max[1]);
        Glide.with(appContext)
                .load(logoUrl)
                .apply(logoOpts)
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                Target<Drawable> target, boolean isFirstResource) {
                        handler.post(() -> {
                            if (!running.get()) return;
                            if (logoRequired) {
                                Log.d(TAG, "Skip logo decode failure for " + item.title);
                                tryShowAt(itemIndex + 1, attempts + 1);
                            } else {
                                commitDisplay(item, artUrl, null, true);
                                index = itemIndex;
                                preloadNext();
                                handler.removeCallbacks(advanceRunnable);
                                handler.postDelayed(advanceRunnable, intervalMs);
                            }
                        });
                        return true;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model,
                                                   Target<Drawable> target, DataSource dataSource,
                                                   boolean isFirstResource) {
                        handler.post(() -> {
                            if (!running.get()) return;
                            commitDisplay(item, artUrl, resource, false);
                            index = itemIndex;
                            preloadNext();
                            handler.removeCallbacks(advanceRunnable);
                            handler.postDelayed(advanceRunnable, intervalMs);
                        });
                        return true;
                    }
                })
                .preload();
    }

    private void commitDisplay(PlexApi.ScreensaverItem item, String artUrl,
                               @Nullable Drawable decodedLogo, boolean useTextTitle) {
        displayItem(item, artUrl, decodedLogo, useTextTitle);
    }

    private void advance() {
        if (!running.get() || playlist.isEmpty()) return;
        tryShowAt(index + 1, 0);
    }

    private void displayItem(PlexApi.ScreensaverItem item, String artUrl,
                             @Nullable Drawable decodedLogo, boolean useTextTitle) {
        statusView.setVisibility(View.GONE);
        ImageView incoming = showingPrimary ? artNextView : artView;
        ImageView outgoing = showingPrimary ? artView : artNextView;

        // Reset only the incoming layer so the outgoing Ken Burns continues through the fade.
        prepareIncomingArt(incoming);

        int w = root.getWidth() > 0 ? root.getWidth() : 1920;
        int h = root.getHeight() > 0 ? root.getHeight() : 1080;

        // Art was pre-decoded into Glide memory; into() should hit cache.
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

        if (titleView != null) {
            titleView.animate().cancel();
            titleView.setVisibility(View.GONE);
        }

        applyOverlayAlignment(logoSize);

        if (decodedLogo != null && showLogo) {
            logoView.setVisibility(View.VISIBLE);
            logoView.setAlpha(0f);
            final String sizeMode = logoSize;
            int[] max = logoMaxPx(root, sizeMode);
            applyLogoSize(logoView, decodedLogo, max[0], max[1], sizeMode);
            logoView.setImageDrawable(decodedLogo);
            logoView.animate().alpha(1f).setDuration(CROSSFADE_MS).start();
        } else {
            logoView.animate().cancel();
            logoView.setVisibility(View.GONE);
            try {
                Glide.with(appContext).clear(logoView);
            } catch (Exception e) {
                Log.w(TAG, "Glide clear logo: " + e.getMessage());
            }
            if (useTextTitle && titleView != null
                    && item.title != null && !item.title.trim().isEmpty()) {
                // Understated white text — same width/alignment rules as logos.
                titleView.setText(item.title.trim());
                applyTitleSize(titleView, logoSize);
                titleView.setAlpha(0f);
                titleView.setVisibility(View.VISIBLE);
                titleView.animate().alpha(1f).setDuration(CROSSFADE_MS).start();
            }
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

        // First successful slide: hide spinner (no loading text).
        hideLoadingSpinner();
        if (statusView != null) {
            statusView.setVisibility(View.GONE);
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
     * Display size for a logo fitted into the max box for the size mode.
     * Never upscales past intrinsic size.
     */
    static int[] resolveLogoDisplaySize(int intrinsicW, int intrinsicH, int maxW, int maxH,
                                        @Nullable String sizeMode) {
        if (intrinsicW <= 0 || intrinsicH <= 0) {
            return new int[]{0, 0};
        }
        return fittedLogoSize(intrinsicW, intrinsicH, maxW, maxH);
    }

    /**
     * Max logo box in px from viewport width fraction + height cap + Large side padding.
     */
    static int[] logoMaxPx(View root, @Nullable String sizeMode) {
        DisplayMetrics dm = root.getResources().getDisplayMetrics();
        int screenW = root.getWidth() > 0 ? root.getWidth() : dm.widthPixels;
        int screenH = root.getHeight() > 0 ? root.getHeight() : dm.heightPixels;
        String mode = normalizeLogoSize(sizeMode);
        float widthFrac = logoWidthFraction(mode);
        int maxW = Math.round(screenW * widthFrac);
        if (LOGO_SIZE_LARGE.equals(mode)) {
            // Enforce ≥10% side padding when centred.
            int padCap = Math.round(screenW * (1f - 2f * LOGO_SIDE_PAD_FRAC_LARGE));
            maxW = Math.min(maxW, padCap);
        }
        int maxH = Math.round(screenH * LOGO_MAX_HEIGHT_FRAC);
        return new int[]{Math.max(1, maxW), Math.max(1, maxH)};
    }

    /** @deprecated Prefer {@link #logoMaxPx(View, String)}; defaults to medium. */
    static int[] logoMaxPx(View root) {
        return logoMaxPx(root, LOGO_SIZE_MEDIUM);
    }

    private void applyOverlayAlignment(@Nullable String sizeMode) {
        if (overlayView == null) return;
        boolean center = isLogoCentered(sizeMode);
        if (overlayView instanceof LinearLayout) {
            LinearLayout ll = (LinearLayout) overlayView;
            ll.setGravity(center ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        }
        ViewGroup.LayoutParams lp = overlayView.getLayoutParams();
        if (lp instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
            flp.gravity = Gravity.BOTTOM | (center ? Gravity.CENTER_HORIZONTAL : Gravity.START);
            overlayView.setLayoutParams(flp);
        }
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
        boolean center = isLogoCentered(sizeMode);
        logoView.setScaleType(center ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.FIT_START);
        logoView.setAdjustViewBounds(true);
    }

    private void applyTitleSize(TextView titleView, @Nullable String sizeMode) {
        if (titleView == null) return;
        int[] max = logoMaxPx(root, sizeMode);
        titleView.setMaxWidth(max[0]);
        titleView.setGravity(isLogoCentered(sizeMode) ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        float sp = LOGO_SIZE_SMALL.equals(normalizeLogoSize(sizeMode)) ? 20f
                : (LOGO_SIZE_LARGE.equals(normalizeLogoSize(sizeMode)) ? 28f : 24f);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
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
                    if (!isScreensaverCandidateEligible(item, allowTextTitle)) {
                        preloadFrom(i + 1, attempts + 1);
                        return;
                    }
                    if (showLogo && item.hasTransparentLogo()) {
                        String logoUrl = PlexApi.buildImageUrl(serverUrl, item.logoPath, token);
                        int[] max = logoMaxPx(root, logoSize);
                        RequestOptions logoOpts = new RequestOptions()
                                .diskCacheStrategy(DiskCacheStrategy.NONE)
                                .override(max[0], max[1]);
                        Glide.with(appContext).load(logoUrl).apply(logoOpts)
                                .preload(max[0], max[1]);
                    }
                });
            }

            @Override
            public void onError(String error) {
                preloadFrom(i + 1, attempts + 1);
            }
        });
    }

    private void showLoadingSpinner() {
        if (spinnerView != null) {
            spinnerView.setVisibility(View.VISIBLE);
        }
        if (statusView != null) {
            statusView.setVisibility(View.GONE);
        }
    }

    private void hideLoadingSpinner() {
        if (spinnerView != null) {
            spinnerView.setVisibility(View.GONE);
        }
    }

    private void hideStatusKeepSpinner(boolean keepSpinner) {
        if (statusView != null) {
            statusView.setVisibility(View.GONE);
        }
        if (!keepSpinner) {
            hideLoadingSpinner();
        }
    }

    /** Real errors only — never a “loading…” label (spinner covers that). */
    private void status(@Nullable String message) {
        hideLoadingSpinner();
        if (message == null || message.isEmpty()) {
            if (statusView != null) statusView.setVisibility(View.GONE);
            return;
        }
        if (statusView == null) return;
        statusView.setText(message);
        statusView.setVisibility(View.VISIBLE);
    }
}
