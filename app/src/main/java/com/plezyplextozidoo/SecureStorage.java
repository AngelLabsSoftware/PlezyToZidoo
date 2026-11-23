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

    /** Preference key suffixes used for path-substitution SMB password slots. */
    static final String[] SMB_PASSWORD_SUFFIXES = {
            "", "_02", "_03", "_04", "_05", "_06", "_07", "_08", "_09", "_10"
    };

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

    /** Secure-storage key for an SMB password preference suffix ("" or "_02", …). */
    static String smbSecureKey(String suffix) {
        String s = suffix == null ? "" : suffix;
        return "smb_password" + s;
    }

    /** Default SharedPreferences key for the same SMB password slot. */
    static String smbPlaintextKey(String suffix) {
        String s = suffix == null ? "" : suffix;
        return "smbPassword" + s;
    }

    /**
     * Dual-read SMB password: encrypted store first, then legacy plaintext prefs.
     * When a plaintext value is found and secure is empty, copies into secure storage.
     * Does <b>not</b> delete the plaintext original (deferred until migration is verified).
     */
    public static String getSmbPassword(Context context, String suffix) {
        Context app = context.getApplicationContext();
        SharedPreferences secure = getInstance(app);
        String secureKey = smbSecureKey(suffix);
        String secureValue = secure.getString(secureKey, "");
        if (secureValue != null && !secureValue.isEmpty()) {
            return secureValue;
        }

        SharedPreferences defaultPrefs = PreferenceManager.getDefaultSharedPreferences(app);
        String plain = defaultPrefs.getString(smbPlaintextKey(suffix), "");
        if (plain != null && !plain.isEmpty()) {
            // Copy forward only — keep plaintext until migration is verified on device.
            secure.edit().putString(secureKey, plain).apply();
            Log.i(TAG, "Migrated SMB password slot to encrypted storage (plaintext retained)");
            return plain;
        }
        return "";
    }

    /**
     * Writes an SMB password to encrypted storage. Does not remove any legacy plaintext
     * copy (destructive cleanup is deferred until migration is verified).
     */
    public static void putSmbPassword(Context context, String suffix, String value) {
        Context app = context.getApplicationContext();
        String v = value != null ? value : "";
        getInstance(app).edit().putString(smbSecureKey(suffix), v).apply();
    }

    /**
     * Copies any plaintext SMB passwords into encrypted storage without deleting originals.
     * Safe to call on every Settings / Play start.
     */
    public static void migrateSmbPasswords(Context context) {
        try {
            for (String suffix : SMB_PASSWORD_SUFFIXES) {
                getSmbPassword(context, suffix);
            }
        } catch (Exception e) {
            Log.w(TAG, "SMB password migration skipped: " + e.getMessage());
        }
    }

    /**
     * Removes any plaintext plex/jellyfin token left in the default SharedPreferences
     * by older builds. Does <b>not</b> remove SMB plaintext keys (those stay until
     * migration is verified — see {@link #migrateSmbPasswords}).
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
