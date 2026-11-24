package com.plezyplextozidoo;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * Inline TV-friendly segmented selector (single choice).
 * Selected = white bg / dark text; unfocused unselected = dark; focus stroke via drawable.
 */
public final class SegmentedControl {

    public interface Listener {
        void onSelected(@NonNull String value);
    }

    private SegmentedControl() {}

    public static void bind(@NonNull LinearLayout container,
                            @NonNull String[] labels,
                            @NonNull String[] values,
                            @Nullable String currentValue,
                            @Nullable Listener listener) {
        if (labels.length != values.length || labels.length == 0) {
            throw new IllegalArgumentException("labels/values length mismatch");
        }
        Context ctx = container.getContext();
        container.removeAllViews();
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setFocusable(false);

        int padH = dp(ctx, 8);
        int padV = dp(ctx, 8);
        int gap = dp(ctx, 6);

        String selected = currentValue != null ? currentValue : values[0];
        boolean matched = false;
        for (String v : values) {
            if (v.equals(selected)) {
                matched = true;
                break;
            }
        }
        if (!matched) selected = values[0];

        final TextView[] chips = new TextView[values.length];
        for (int i = 0; i < values.length; i++) {
            final String value = values[i];
            TextView chip = new TextView(ctx);
            chip.setText(labels[i]);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(padH, padV, padH, padV);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setBackgroundResource(R.drawable.dash_segment_item);
            chip.setTextColor(ContextCompat.getColorStateList(ctx, R.color.dash_segment_text));
            chip.setFocusable(true);
            chip.setClickable(true);
            chip.setSelected(value.equals(selected));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.setMarginStart(gap);
            chip.setLayoutParams(lp);

            chip.setOnClickListener(v -> {
                for (int j = 0; j < chips.length; j++) {
                    chips[j].setSelected(values[j].equals(value));
                }
                if (listener != null) listener.onSelected(value);
            });
            chip.setOnFocusChangeListener((v, hasFocus) -> {
                // Keep selected styling; focus stroke is in the drawable state list.
                v.refreshDrawableState();
            });

            chips[i] = chip;
            container.addView(chip);
        }
    }

    private static int dp(Context ctx, int dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics()));
    }
}
