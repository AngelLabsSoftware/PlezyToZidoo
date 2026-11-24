package com.plezyplextozidoo;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import androidx.preference.PreferenceManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;

import jp.wasabeef.glide.transformations.BlurTransformation;

/**
 * Up Next countdown screen for episode auto-advance.
 *
 * Launch with intent extras:
 *   seriesName, episodeName, seasonNumber, episodeNumber,
 *   nextItemId, serverPath, seriesId, seasonId, serverUrl, accessToken,
 *   optional backdropUrl / thumbUrl
 */
public class UpNextActivity extends AppCompatActivity {

    /** Pref key — seconds until auto-play. Independent of Skip Intro/Credits (always 3s). */
    public static final String PREF_UP_NEXT_COUNTDOWN_SEC = "up_next_countdown_sec";
    public static final int DEFAULT_UP_NEXT_COUNTDOWN_SEC = 7;

    private CountDownTimer countDownTimer;
    private boolean cancelled = false;
    private boolean finished = false;

    private String nextItemId;
    private String serverPath;
    private String seriesId;
    private String seasonId;
    private String episodeName;
    private int seasonNumber;
    private int episodeNumber;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_up_next);

        Intent intent = getIntent();
        String seriesName = intent.getStringExtra("seriesName");
        episodeName = intent.getStringExtra("episodeName");
        seasonNumber = intent.getIntExtra("seasonNumber", 0);
        episodeNumber = intent.getIntExtra("episodeNumber", 0);
        nextItemId = intent.getStringExtra("nextItemId");
        serverPath = intent.getStringExtra("serverPath");
        seriesId = intent.getStringExtra("seriesId");
        seasonId = intent.getStringExtra("seasonId");
        String backdropUrl = intent.getStringExtra("backdropUrl");
        String thumbUrl = intent.getStringExtra("thumbUrl");
        String serverUrl = intent.getStringExtra("serverUrl");
        String accessToken = intent.getStringExtra("accessToken");

        ImageView backdropImageView = findViewById(R.id.backdrop);
        ImageView episodeThumbnail = findViewById(R.id.episodeThumbnail);
        TextView seriesNameView = findViewById(R.id.seriesName);
        TextView episodeInfoView = findViewById(R.id.episodeInfo);
        TextView countdownView = findViewById(R.id.countdown);
        AppCompatButton btnPlayNow = findViewById(R.id.btnPlayNow);
        AppCompatButton btnCancel = findViewById(R.id.btnCancel);

        // Material themes tint AppCompatButton via colorPrimary — clear so mono drawable shows.
        btnPlayNow.setSupportBackgroundTintList(null);
        btnCancel.setSupportBackgroundTintList(null);
        btnPlayNow.setBackgroundResource(R.drawable.btn_tv_focus);
        btnCancel.setBackgroundResource(R.drawable.btn_tv_focus);

        seriesNameView.setText(seriesName != null ? seriesName : "");
        String episodeInfoText = String.format("S%02dE%02d - %s",
                seasonNumber, episodeNumber, episodeName != null ? episodeName : "");
        episodeInfoView.setText(episodeInfoText);

        if ((backdropUrl == null || backdropUrl.isEmpty()) && serverUrl != null && seriesId != null) {
            backdropUrl = PlexApi.buildImageUrl(serverUrl,
                    "/library/metadata/" + seriesId + "/art", accessToken);
        }
        if ((thumbUrl == null || thumbUrl.isEmpty()) && serverUrl != null && nextItemId != null) {
            thumbUrl = PlexApi.buildImageUrl(serverUrl,
                    "/library/metadata/" + nextItemId + "/thumb", accessToken);
        }

        if (backdropUrl != null && !backdropUrl.isEmpty()) {
            Glide.with(this)
                    .load(backdropUrl)
                    .apply(new RequestOptions()
                            .override(1280, 720)
                            .transform(new BlurTransformation(25, 3))
                            .diskCacheStrategy(DiskCacheStrategy.ALL))
                    .into(backdropImageView);
        }

        if (thumbUrl != null && !thumbUrl.isEmpty()) {
            Glide.with(this)
                    .load(thumbUrl)
                    .apply(new RequestOptions()
                            .override(960, 540)
                            .transform(new RoundedCorners(16))
                            .diskCacheStrategy(DiskCacheStrategy.ALL))
                    .into(episodeThumbnail);
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int countdownSec = normalizeCountdownSec(
                prefs.getString(PREF_UP_NEXT_COUNTDOWN_SEC, String.valueOf(DEFAULT_UP_NEXT_COUNTDOWN_SEC)));
        long countdownMs = countdownSec * 1000L;

        countdownView.setText(getString(R.string.up_next_playing_in, countdownSec));
        countDownTimer = new CountDownTimer(countdownMs, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                if (cancelled || finished) return;
                countdownView.setText(getString(R.string.up_next_playing_in,
                        Math.max(1, (int) Math.ceil(millisUntilFinished / 1000.0))));
            }

            @Override
            public void onFinish() {
                if (!cancelled && !finished) {
                    launchNextEpisode();
                }
            }
        }.start();

        btnPlayNow.setOnClickListener(v -> {
            if (cancelled || finished) return;
            cancelled = true;
            if (countDownTimer != null) {
                countDownTimer.cancel();
            }
            launchNextEpisode();
        });

        btnCancel.setOnClickListener(v -> {
            if (finished) return;
            cancelled = true;
            if (countDownTimer != null) {
                countDownTimer.cancel();
            }
            setResult(RESULT_CANCELED);
            finish();
        });

        btnPlayNow.requestFocus();
        Log.d("UpNext", "Screen displayed: " + seriesName + " S" + seasonNumber + "E" + episodeNumber
                + " countdown=" + countdownSec + "s");
    }

    /** Allowed values: 3, 5, 7 (default), 10. Unknown → default. */
    static int normalizeCountdownSec(String raw) {
        if (raw == null) return DEFAULT_UP_NEXT_COUNTDOWN_SEC;
        try {
            int v = Integer.parseInt(raw.trim());
            if (v == 3 || v == 5 || v == 7 || v == 10) return v;
        } catch (NumberFormatException ignored) {
        }
        return DEFAULT_UP_NEXT_COUNTDOWN_SEC;
    }

    private void launchNextEpisode() {
        if (finished) return;
        finished = true;
        cancelled = true;
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        Intent resultIntent = new Intent();
        resultIntent.putExtra("nextItemId", nextItemId);
        resultIntent.putExtra("serverPath", serverPath);
        resultIntent.putExtra("seriesId", seriesId);
        resultIntent.putExtra("seasonId", seasonId);
        resultIntent.putExtra("episodeName", episodeName);
        resultIntent.putExtra("seasonNumber", seasonNumber);
        resultIntent.putExtra("episodeNumber", episodeNumber);
        setResult(RESULT_OK, resultIntent);
        finish();
    }

    @Override
    public void onBackPressed() {
        cancelled = true;
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        setResult(RESULT_CANCELED);
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelled = true;
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
    }
}
