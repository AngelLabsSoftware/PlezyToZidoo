package com.plezyplextozidoo;

import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

/**
 * In-app preview of the same {@link ScreensaverRenderer} used by the DayDream service.
 */
public class ScreensaverPreviewActivity extends AppCompatActivity {

    private ScreensaverRenderer renderer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        SecureStorage.removeLegacyPlaintextPassword(getApplicationContext());
        SecureStorage.migrateSmbPasswords(getApplicationContext());
        String deviceId = SecureStorage.getDeviceId(getApplicationContext());
        PlexApi.setClientIdentity(Build.MODEL, deviceId, BuildConfig.VERSION_NAME);

        View root = View.inflate(this, R.layout.screensaver_scene, null);
        setContentView(root);
        renderer = new ScreensaverRenderer(this, root);
        root.setOnClickListener(v -> finish());
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (renderer != null) {
            renderer.start();
        }
    }

    @Override
    protected void onStop() {
        if (renderer != null) {
            renderer.stop();
        }
        super.onStop();
    }
}
