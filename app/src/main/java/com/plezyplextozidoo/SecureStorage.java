package com.plezyplextozidoo;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton wrapper around EncryptedSharedPreferences for secure credential storage.
 * <p>
 * If encrypted prefs cannot be created, non-credential keys may use a private prefs file,
 * but <b>credentials are never silently persisted in plaintext</b> — they stay session-only
 * in memory until encryption works again. Legacy plaintext SMB values in default prefs are
 * still readable for migration / playback continuity.
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
    private static volatile boolean encryptionAvailable = true;

    private SecureStorage() {
        // Prevent instantiation
    }

    /** True when EncryptedSharedPreferences initialized successfully. */
    public static boolean isEncryptionAvailable() {
        // Force init so callers after first getInstance see the real flag.
        return encryptionAvailable;
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

    static boolean isCredentialKey(@Nullable String key) {
        if (key == null) return false;
        if ("plex_access_token".equals(key) || "plex_token".equals(key)) return true;
        if (key.startsWith("smb_password")) return true;
        return key.startsWith("smbPassword");
    }

    /**
     * Dual-read SMB password: encrypted (or session) store first, then legacy plaintext prefs.
     * When a plaintext value is found and secure is empty, copies into secure storage
     * <b>only if encryption is available</b> (never copies into a plaintext disk fallback).
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
            if (encryptionAvailable) {
                secure.edit().putString(secureKey, plain).apply();
                Log.i(TAG, "Migrated SMB password slot to encrypted storage (plaintext retained)");
            } else {
                Log.w(TAG, "Encryption unavailable — using legacy SMB plaintext for read only; "
                        + "not copying credentials to disk fallback");
            }
            return plain;
        }
        return "";
    }

    /**
     * Writes an SMB password to encrypted storage when available; otherwise keeps it
     * session-only in memory (never writes credential plaintext to the fallback file).
     * Does not remove any legacy plaintext copy (destructive cleanup deferred).
     */
    public static void putSmbPassword(Context context, String suffix, String value) {
        Context app = context.getApplicationContext();
        String v = value != null ? value : "";
        getInstance(app).edit().putString(smbSecureKey(suffix), v).apply();
        if (!encryptionAvailable) {
            Log.e(TAG, "SMB password kept session-only — encrypted SharedPreferences unavailable");
        }
    }

    /**
     * Copies any plaintext SMB passwords into encrypted storage without deleting originals.
     * No-ops credential disk writes when encryption is unavailable.
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

            SharedPreferences encrypted = EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            encryptionAvailable = true;
            return encrypted;
        } catch (GeneralSecurityException | IOException e) {
            encryptionAvailable = false;
            Log.e(TAG, "Failed to create encrypted preferences — credentials will NOT be "
                    + "persisted in plaintext; non-credential keys use a private prefs file", e);
            SharedPreferences disk = context.getSharedPreferences(FALLBACK_PREFS_FILE, Context.MODE_PRIVATE);
            return new CredentialSafePreferences(disk);
        }
    }

    /**
     * Wraps a disk SharedPreferences so credential keys never hit disk.
     * Credential gets/puts use an in-memory map for the process lifetime only.
     */
    static final class CredentialSafePreferences implements SharedPreferences {
        private final SharedPreferences disk;
        private final ConcurrentHashMap<String, String> sessionCredentials = new ConcurrentHashMap<>();

        CredentialSafePreferences(SharedPreferences disk) {
            this.disk = disk;
        }

        @Override
        public Map<String, ?> getAll() {
            Map<String, Object> all = new HashMap<>(disk.getAll());
            all.putAll(sessionCredentials);
            return all;
        }

        @Override
        public String getString(String key, @Nullable String defValue) {
            if (isCredentialKey(key)) {
                String v = sessionCredentials.get(key);
                return v != null ? v : defValue;
            }
            return disk.getString(key, defValue);
        }

        @Override
        public Set<String> getStringSet(String key, @Nullable Set<String> defValues) {
            return disk.getStringSet(key, defValues);
        }

        @Override
        public int getInt(String key, int defValue) {
            return disk.getInt(key, defValue);
        }

        @Override
        public long getLong(String key, long defValue) {
            return disk.getLong(key, defValue);
        }

        @Override
        public float getFloat(String key, float defValue) {
            return disk.getFloat(key, defValue);
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            return disk.getBoolean(key, defValue);
        }

        @Override
        public boolean contains(String key) {
            if (isCredentialKey(key)) {
                return sessionCredentials.containsKey(key);
            }
            return disk.contains(key);
        }

        @Override
        public Editor edit() {
            return new Editor() {
                private final SharedPreferences.Editor diskEditor = disk.edit();
                private final Map<String, String> pendingCredPuts = new HashMap<>();
                private final Set<String> pendingCredRemoves = new HashSet<>();
                private boolean clearSessionCreds = false;

                @Override
                public Editor putString(String key, @Nullable String value) {
                    if (isCredentialKey(key)) {
                        if (value == null) {
                            pendingCredRemoves.add(key);
                            pendingCredPuts.remove(key);
                        } else {
                            pendingCredPuts.put(key, value);
                            pendingCredRemoves.remove(key);
                        }
                        return this;
                    }
                    diskEditor.putString(key, value);
                    return this;
                }

                @Override
                public Editor putStringSet(String key, @Nullable Set<String> values) {
                    diskEditor.putStringSet(key, values);
                    return this;
                }

                @Override
                public Editor putInt(String key, int value) {
                    diskEditor.putInt(key, value);
                    return this;
                }

                @Override
                public Editor putLong(String key, long value) {
                    diskEditor.putLong(key, value);
                    return this;
                }

                @Override
                public Editor putFloat(String key, float value) {
                    diskEditor.putFloat(key, value);
                    return this;
                }

                @Override
                public Editor putBoolean(String key, boolean value) {
                    diskEditor.putBoolean(key, value);
                    return this;
                }

                @Override
                public Editor remove(String key) {
                    if (isCredentialKey(key)) {
                        pendingCredRemoves.add(key);
                        pendingCredPuts.remove(key);
                        return this;
                    }
                    diskEditor.remove(key);
                    return this;
                }

                @Override
                public Editor clear() {
                    clearSessionCreds = true;
                    pendingCredPuts.clear();
                    pendingCredRemoves.clear();
                    diskEditor.clear();
                    return this;
                }

                private void applySession() {
                    if (clearSessionCreds) {
                        sessionCredentials.clear();
                    }
                    for (String k : pendingCredRemoves) {
                        sessionCredentials.remove(k);
                    }
                    sessionCredentials.putAll(pendingCredPuts);
                    if (!pendingCredPuts.isEmpty()) {
                        Log.e(TAG, "Credential values held in memory only (encryption unavailable)");
                    }
                }

                @Override
                public boolean commit() {
                    applySession();
                    return diskEditor.commit();
                }

                @Override
                public void apply() {
                    applySession();
                    diskEditor.apply();
                }
            };
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
            disk.registerOnSharedPreferenceChangeListener(listener);
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
            disk.unregisterOnSharedPreferenceChangeListener(listener);
        }
    }
}
