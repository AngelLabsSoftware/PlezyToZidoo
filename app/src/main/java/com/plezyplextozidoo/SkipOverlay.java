package com.plezyplextozidoo;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;

/**
 * SYSTEM_ALERT_WINDOW overlay pill for Skip Intro / Skip Credits above the Zidoo player bar.
 */
public class SkipOverlay {

    public interface Listener {
        void onSkipConfirmed(String segment);
        void onSkipDismissed(String segment);
        void onCountdownCancelled(String segment);
    }

    private static final String TAG = "SkipOverlay";
    /** Unfocused: pale grey fill, white border, dark text (see button text color). */
    private static final int BG_COLOR = 0xFFE0E0E0;
    private static final int BORDER_COLOR = 0xFFFFFFFF;
    /** Focused: black fill, white border, white text. */
    private static final int FOCUS_BORDER_COLOR = 0xFFFFFFFF;
    private static final int FOCUS_BG_COLOR = 0xFF000000;
    private static final int TEXT_COLOR = 0xFF222222;
    private static final int FOCUS_TEXT_COLOR = 0xFFFFFFFF;
    /** Small corner radius — not a pill. */
    private static final float CORNER_RADIUS_DP = 6f;

    private final Context appContext;
    private final WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Listener listener;

    private FrameLayout root;
    private Button button;
    private WindowManager.LayoutParams layoutParams;
    private boolean attached = false;

    private String activeSegment = null; // "intro" | "credits"
    private boolean countdownMode = false;
    private int countdownRemaining = 0;
    private Runnable countdownTick;

    public SkipOverlay(Context context) {
        this.appContext = context.getApplicationContext();
        this.windowManager = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public static boolean canDrawOverlays(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return Settings.canDrawOverlays(context);
    }

    public boolean isShowing() {
        return attached;
    }

    public String getActiveSegment() {
        return activeSegment;
    }

    public boolean isCountdownMode() {
        return countdownMode;
    }

    /** Manual skip button for the given segment. */
    public void showManual(String segment, String label) {
        mainHandler.post(() -> {
            cancelCountdownLocked();
            countdownMode = false;
            activeSegment = segment;
            ensureViews();
            button.setText(label != null ? label : "Skip");
            attachIfNeeded();
            button.requestFocus();
        });
    }

    /**
     * Countdown overlay: "Skipping Intro in 3…2…1… — Press Back to Cancel"
     * then invokes onSkipConfirmed.
     */
    public void showCountdown(String segment, String kindLabel, int seconds) {
        mainHandler.post(() -> {
            cancelCountdownLocked();
            countdownMode = true;
            activeSegment = segment;
            countdownRemaining = Math.max(1, seconds);
            ensureViews();
            updateCountdownLabel(kindLabel);
            attachIfNeeded();
            button.requestFocus();

            countdownTick = new Runnable() {
                @Override
                public void run() {
                    if (!attached || !countdownMode) {
                        return;
                    }
                    countdownRemaining--;
                    if (countdownRemaining <= 0) {
                        String seg = activeSegment;
                        hideInternal();
                        if (listener != null && seg != null) {
                            listener.onSkipConfirmed(seg);
                        }
                        return;
                    }
                    updateCountdownLabel(kindLabel);
                    mainHandler.postDelayed(this, 1000);
                }
            };
            mainHandler.postDelayed(countdownTick, 1000);
        });
    }

    public void hide() {
        mainHandler.post(this::hideInternal);
    }

    public void destroy() {
        mainHandler.post(() -> {
            cancelCountdownLocked();
            hideInternal();
            listener = null;
        });
    }

    private void updateCountdownLabel(String kindLabel) {
        String kind = kindLabel != null ? kindLabel : "Intro";
        button.setText("Skipping " + kind + " in " + countdownRemaining
                + "… — Press Back to Cancel");
    }

    private void ensureViews() {
        if (root != null) {
            return;
        }

        float density = appContext.getResources().getDisplayMetrics().density;
        int padH = (int) (20 * density);
        int padV = (int) (12 * density);
        int margin = (int) (24 * density);
        int bottomLift = (int) (72 * density); // sit above Zidoo transport bar

        button = new Button(appContext);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(TEXT_COLOR);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setPadding(padH, padV, padH, padV);
        button.setMinWidth((int) (160 * density));
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setBackground(buildFocusBackground(density));
        button.setOnFocusChangeListener((v, hasFocus) ->
                button.setTextColor(hasFocus ? FOCUS_TEXT_COLOR : TEXT_COLOR));
        button.setOnClickListener(v -> {
            String seg = activeSegment;
            boolean wasCountdown = countdownMode;
            hideInternal();
            if (listener == null || seg == null) {
                return;
            }
            if (wasCountdown) {
                listener.onSkipConfirmed(seg);
            } else {
                listener.onSkipConfirmed(seg);
            }
        });
        button.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_UP) {
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                String seg = activeSegment;
                boolean wasCountdown = countdownMode;
                hideInternal();
                if (listener != null && seg != null) {
                    if (wasCountdown) {
                        listener.onCountdownCancelled(seg);
                    } else {
                        listener.onSkipDismissed(seg);
                    }
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                button.performClick();
                return true;
            }
            return false;
        });

        root = new FrameLayout(appContext);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        root.addView(button, lp);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.BOTTOM | Gravity.END;
        layoutParams.x = margin;
        layoutParams.y = bottomLift;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
    }

    private StateListDrawable buildFocusBackground(float density) {
        float radius = CORNER_RADIUS_DP * density;
        int stroke = Math.max(2, Math.round(2 * density));

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(BG_COLOR);
        normal.setCornerRadius(radius);
        normal.setStroke(stroke, BORDER_COLOR);

        GradientDrawable focused = new GradientDrawable();
        focused.setColor(FOCUS_BG_COLOR);
        focused.setCornerRadius(radius);
        focused.setStroke(stroke, FOCUS_BORDER_COLOR);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{android.R.attr.state_selected}, focused);
        states.addState(new int[]{android.R.attr.state_pressed}, focused);
        states.addState(new int[]{}, normal);
        return states;
    }

    private void attachIfNeeded() {
        if (!canDrawOverlays(appContext)) {
            Log.w(TAG, "Overlay permission missing; not attaching");
            return;
        }
        if (attached) {
            return;
        }
        try {
            windowManager.addView(root, layoutParams);
            attached = true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to add overlay: " + e.getMessage());
            attached = false;
        }
    }

    private void hideInternal() {
        cancelCountdownLocked();
        countdownMode = false;
        activeSegment = null;
        if (!attached || root == null) {
            attached = false;
            return;
        }
        try {
            windowManager.removeView(root);
        } catch (Exception e) {
            Log.w(TAG, "Failed to remove overlay: " + e.getMessage());
        }
        attached = false;
    }

    private void cancelCountdownLocked() {
        if (countdownTick != null) {
            mainHandler.removeCallbacks(countdownTick);
            countdownTick = null;
        }
        countdownRemaining = 0;
    }
}
