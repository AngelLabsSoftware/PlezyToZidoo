package com.plezyplextozidoo;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.UUID;

/**
 * Singleton wrapper around EncryptedSharedPreferences for secure credential storage.
 * Falls back to regular SharedPreferences if encryption is unavailable.
 */
public class SecureStorage {

    private static final String TAG = "SecureStorage";
    private static final String PREFS_FILE = "plex_secure_prefs";
    private static final String FALLBACK_PREFS_FILE = "plex_secure_prefs_fallback";

    private static volatile SharedPreferences instance;

    private SecureStorage() {
        // Prevent instantiation
    }

    public static SharedPreferences getInstance(Context context) {
        if (instance == null) {
            synchronized (SecureStorage.class) {
                if (instance == null) {
                    instance = createPreferences(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    public static String getDeviceId(Context context) {
        SharedPreferences prefs = getInstance(context);
        String deviceId = prefs.getString("device_id", "");
        if (deviceId == null || deviceId.isEmpty()) {
            deviceId = UUID.randomUUID().toString().replace("-", "");
            prefs.edit().putString("device_id", deviceId).apply();
        }
        return deviceId;
    }

    /**
     * Removes any plaintext token left in the default SharedPreferences by older builds.
     */
    public static void removeLegacyPlaintextPassword(Context context) {
        try {
            SharedPreferences defaultPrefs =
                    PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
            boolean changed = false;
            SharedPreferences.Editor editor = defaultPrefs.edit();
            if (defaultPrefs.contains("plex_token")) {
                editor.remove("plex_token");
                changed = true;
            }
            if (defaultPrefs.contains("plex_access_token")) {
                editor.remove("plex_access_token");
                changed = true;
            }
            // Legacy Jellyfin keys from seed builds
            if (defaultPrefs.contains("jellyfin_password")) {
                editor.remove("jellyfin_password");
                changed = true;
            }
            if (changed) {
                editor.apply();
                Log.i(TAG, "Removed leftover plaintext credentials from default preferences");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not clean up legacy credential entries: " + e.getMessage());
        }
    }

    private static SharedPreferences createPreferences(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            return EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Failed to create encrypted preferences, using fallback", e);
            return context.getSharedPreferences(FALLBACK_PREFS_FILE, Context.MODE_PRIVATE);
        }
    }
}
