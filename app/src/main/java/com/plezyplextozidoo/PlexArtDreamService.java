package com.plezyplextozidoo;

import android.os.Build;
import android.service.dreams.DreamService;
import android.view.View;

/**
 * DayDream / screensaver entry point selectable from Android Settings → Screensaver
 * (including Zidoo Advanced / Android Settings). Uses {@link ScreensaverRenderer}.
 */
public class PlexArtDreamService extends DreamService {

    private ScreensaverRenderer renderer;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setScreenBright(true);
        }

        SecureStorage.removeLegacyPlaintextPassword(getApplicationContext());
        SecureStorage.migrateSmbPasswords(getApplicationContext());
        String deviceId = SecureStorage.getDeviceId(getApplicationContext());
        PlexApi.setClientIdentity(Build.MODEL, deviceId, BuildConfig.VERSION_NAME);

        View root = View.inflate(this, R.layout.screensaver_scene, null);
        setContentView(root);
        renderer = new ScreensaverRenderer(this, root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (renderer != null) {
            renderer.start();
        }
    }

    @Override
    public void onDreamingStopped() {
        if (renderer != null) {
            renderer.stop();
        }
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (renderer != null) {
            renderer.stop();
            renderer = null;
        }
        super.onDetachedFromWindow();
    }
}
