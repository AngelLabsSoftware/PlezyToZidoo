package com.plezyplextozidoo;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

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

    private CountDownTimer countDownTimer;
    private boolean cancelled = false;

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
        Button btnPlayNow = findViewById(R.id.btnPlayNow);
        Button btnCancel = findViewById(R.id.btnCancel);

        seriesNameView.setText(seriesName != null ? seriesName : "");
        String episodeInfoText = String.format("S%02dE%02d - %s",
                seasonNumber, episodeNumber, episodeName != null ? episodeName : "");
        episodeInfoView.setText(episodeInfoText);

        // Prefer explicit URLs; otherwise build from Plex metadata paths
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
                            .override(640, 360)
                            .transform(new RoundedCorners(16))
                            .diskCacheStrategy(DiskCacheStrategy.ALL))
                    .into(episodeThumbnail);
        }

        countdownView.setText("Playing in 10");
        countDownTimer = new CountDownTimer(10000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                countdownView.setText("Playing in " + (millisUntilFinished / 1000));
            }

            @Override
            public void onFinish() {
                if (!cancelled) {
                    launchNextEpisode();
                }
            }
        }.start();

        btnPlayNow.setOnClickListener(v -> {
            cancelled = true;
            if (countDownTimer != null) {
                countDownTimer.cancel();
            }
            launchNextEpisode();
        });

        btnCancel.setOnClickListener(v -> {
            cancelled = true;
            if (countDownTimer != null) {
                countDownTimer.cancel();
            }
            setResult(RESULT_CANCELED);
            finish();
        });

        btnPlayNow.requestFocus();
        Log.d("UpNext", "Screen displayed: " + seriesName + " S" + seasonNumber + "E" + episodeNumber);
    }

    private void launchNextEpisode() {
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
    protected void onDestroy() {
        super.onDestroy();
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
    }
}
