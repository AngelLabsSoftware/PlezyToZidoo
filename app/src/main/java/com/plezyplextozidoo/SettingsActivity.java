package com.plezyplextozidoo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreference;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class SettingsActivity extends AppCompatActivity
{
    private final String backupFile = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + "/PlezyPlexToZidooSettings.txt";
    private SettingsFragment settingsFragment;
    private String settingsRootKey;
    private String scrollToPreference;
    private static final int PERMISSION_REQUEST_IMPORT = 1;
    private static final int PERMISSION_REQUEST_EXPORT = 2;
    private final String[] permissions = {Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE};

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        SecureStorage.removeLegacyPlaintextPassword(getApplicationContext());
        SecureStorage.migrateSmbPasswords(getApplicationContext());

        String deviceId = SecureStorage.getDeviceId(getApplicationContext());
        PlexApi.setClientIdentity(Build.MODEL, deviceId, BuildConfig.VERSION_NAME);

        setContentView(R.layout.settings_activity);
        if (savedInstanceState == null)
        {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings, new SettingsFragment())
                    .commit();
        }
    }

    public static class SettingsFragment extends PreferenceFragmentCompat
    {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey)
        {
            SettingsActivity activity = (SettingsActivity) requireActivity();
            activity.settingsFragment = this;
            activity.settingsRootKey = rootKey;

            activity.showRootSettings(null);
        }

        @SuppressLint("RestrictedApi")
        @Override
        protected void onBindPreferences()
        {
            super.onBindPreferences();

            SettingsActivity activity = (SettingsActivity) getActivity();
            if(activity != null && activity.scrollToPreference != null)
            {
                scrollToPreference(activity.scrollToPreference);
                activity.scrollToPreference = null;
            }
        }
    }

    @Override
    protected void onStart()
    {
        super.onStart();
        if (settingsFragment != null && settingsFragment.findPreference("overlay_permission") != null)
        {
            setOverlayPermissionPreference();
        }
    }

    @Override
    public void onBackPressed()
    {
        if (settingsFragment == null)
        {
            super.onBackPressed();
            return;
        }

        Preference substitutionLink = settingsFragment.findPreference("substitution_link");
        if (substitutionLink != null)
        {
            super.onBackPressed();
        }
        else
        {
            showRootSettings("substitution_link");
        }
    }

    public void showRootSettings(@Nullable String scrollToPref)
    {
        if (settingsFragment == null)
        {
            return;
        }

        scrollToPreference = scrollToPref;
        settingsFragment.setPreferencesFromResource(R.xml.root_preferences, settingsRootKey);
        setSmbPasswordPreference();
        setPlexTokenPreference();
        setPlexServerUrlPreference();
        setTestConnectionPreference();
        setSkipBehaviorPreference();
        setOverlayPermissionPreference();
        setScreensaverPreferences();

        Preference substitutionLink = settingsFragment.findPreference("substitution_link");
        if (substitutionLink != null)
        {
            substitutionLink.setOnPreferenceClickListener(preference ->
            {
                settingsFragment.setPreferencesFromResource(R.xml.substitution_preferences, settingsRootKey);
                setSmbPasswordPreference();
                return true;
            });
        }
    }

    private void setPlexTokenPreference()
    {
        EditTextPreference tokenPref = settingsFragment.findPreference("plex_token");
        if (tokenPref == null) return;

        Context context = settingsFragment.requireContext();
        SharedPreferences securePrefs = SecureStorage.getInstance(context);

        String existing = securePrefs.getString("plex_access_token", "");
        tokenPref.setSummary((existing != null && !existing.isEmpty()) ? "********" : "Not set (optional fallback)");

        tokenPref.setOnBindEditTextListener(editText ->
                editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));

        tokenPref.setOnPreferenceChangeListener((preference, newValue) ->
        {
            String value = (newValue != null) ? newValue.toString().trim() : "";
            securePrefs.edit().putString("plex_access_token", value).apply();
            tokenPref.setSummary(value.isEmpty() ? "Not set (optional fallback)" : "********");
            return false;
        });
    }

    private void setPlexServerUrlPreference()
    {
        EditTextPreference serverUrlPref = settingsFragment.findPreference("plex_server_url");
        if (serverUrlPref == null) return;

        serverUrlPref.setOnPreferenceChangeListener((preference, newValue) ->
        {
            String value = (newValue != null) ? newValue.toString().trim() : "";
            while (value.endsWith("/"))
            {
                value = value.substring(0, value.length() - 1);
            }
            serverUrlPref.setText(value);
            serverUrlPref.setSummary(value.isEmpty() ? getString(R.string.plex_server_url_summary) : value);
            return false;
        });
    }

    private void setSkipBehaviorPreference()
    {
        ListPreference skipBehavior = settingsFragment.findPreference("skip_behavior");
        if (skipBehavior == null) return;

        skipBehavior.setSummaryProvider(preference ->
        {
            CharSequence entry = ((ListPreference) preference).getEntry();
            return entry != null ? entry : getString(R.string.skip_behavior_summary);
        });
    }

    private void setOverlayPermissionPreference()
    {
        Preference overlayPref = settingsFragment.findPreference("overlay_permission");
        if (overlayPref == null) return;

        boolean allowed = SkipOverlay.canDrawOverlays(getApplicationContext());
        overlayPref.setSummary(allowed
                ? "Granted — Skip button can appear over Zidoo"
                : getString(R.string.overlay_permission_summary));

        overlayPref.setOnPreferenceClickListener(preference ->
        {
            try
            {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
            catch (Exception e)
            {
                Toast.makeText(getApplicationContext(),
                        "Open Settings → Apps → Special access → Display over other apps",
                        Toast.LENGTH_LONG).show();
            }
            return true;
        });
    }

    private void setTestConnectionPreference()
    {
        Preference testPref = settingsFragment.findPreference("plex_test_connection");
        if (testPref == null) return;

        testPref.setOnPreferenceClickListener(preference ->
        {
            Context context = settingsFragment.requireContext();
            SharedPreferences defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context);
            SharedPreferences securePrefs = SecureStorage.getInstance(context);
            String serverUrl = defaultPrefs.getString("plex_server_url", "");
            String token = securePrefs.getString("plex_access_token", "");

            if (serverUrl == null || serverUrl.isEmpty())
            {
                Toast.makeText(context, "Please configure a fallback Server URL first", Toast.LENGTH_LONG).show();
                return true;
            }
            if (token == null || token.isEmpty())
            {
                Toast.makeText(context, "Please configure a fallback X-Plex-Token first", Toast.LENGTH_LONG).show();
                return true;
            }

            testPref.setSummary("Testing...");
            PlexApi.testConnection(serverUrl, token, new PlexApi.SimpleCallback()
            {
                @Override
                public void onSuccess(String message)
                {
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show();
                    testPref.setSummary(message);
                }

                @Override
                public void onError(String error)
                {
                    Toast.makeText(context, "Connection failed: " + error, Toast.LENGTH_LONG).show();
                    testPref.setSummary("Test connection failed");
                }
            });

            return true;
        });
    }

    private void setScreensaverPreferences()
    {
        ListPreference interval = settingsFragment.findPreference("screensaver_interval_sec");
        if (interval != null)
        {
            interval.setSummaryProvider(preference ->
            {
                CharSequence entry = ((ListPreference) preference).getEntry();
                return entry != null ? entry : getString(R.string.screensaver_interval_summary);
            });
        }

        Preference preview = settingsFragment.findPreference("screensaver_preview");
        if (preview != null)
        {
            preview.setOnPreferenceClickListener(preference ->
            {
                startActivity(new Intent(this, ScreensaverPreviewActivity.class));
                return true;
            });
        }
    }

    public void setSmbPasswordPreference()
    {
        Context context = settingsFragment.requireContext();
        for (String s : SecureStorage.SMB_PASSWORD_SUFFIXES)
        {
            EditTextPreference smbPasswordPreference = settingsFragment.findPreference("smbPassword" + s);
            if (smbPasswordPreference == null)
            {
                continue;
            }

            // Non-persistent: value lives in SecureStorage (with plaintext dual-read fallback).
            String existing = SecureStorage.getSmbPassword(context, s);
            smbPasswordPreference.setSummary(
                    (existing != null && !existing.isEmpty()) ? "********" : "Not set");

            smbPasswordPreference.setOnBindEditTextListener(editText ->
                    editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));

            final String suffix = s;
            smbPasswordPreference.setOnPreferenceChangeListener((preference, newValue) ->
            {
                String value = (newValue != null) ? newValue.toString() : "";
                SecureStorage.putSmbPassword(context, suffix, value);
                smbPasswordPreference.setSummary(value.isEmpty() ? "Not set" : "********");
                return false;
            });
        }
    }

    public void onSubstitutionBackClickMethod(View view)
    {
        showRootSettings("substitution_link");
    }

    public void onImportClickMethod(View view)
    {
        if (checkAndRequestPermissions(PERMISSION_REQUEST_IMPORT))
        {
            return;
        }

        importSettings();
    }

    public void importSettings()
    {
        FileInputStream input = null;
        BufferedReader reader = null;
        try
        {
            input = new FileInputStream(backupFile);
            reader = new BufferedReader(new InputStreamReader(input));

            Gson gson = new Gson();
            Type mapType = new TypeToken<Map<String, ?>>(){}.getType();
            Map<String, ?> map = gson.fromJson(reader, mapType);

            for (Map.Entry<String, ?> entry : map.entrySet())
            {
                try
                {
                    setPreferenceOnImport(entry);
                }
                catch (Exception e)
                {
                    // Ignore bad data
                }
            }

            settingsFragment.setPreferencesFromResource(R.xml.substitution_preferences, settingsRootKey);
            for (Map.Entry<String, ?> entry : map.entrySet())
            {
                try
                {
                    setPreferenceOnImport(entry);
                }
                catch (Exception e)
                {
                    // Ignore bad data
                }
            }

            showRootSettings("import_export");
            Toast.makeText(getApplicationContext(), "Settings imported successfully from " + backupFile, Toast.LENGTH_LONG).show();
        }
        catch (Exception e)
        {
            Toast.makeText(getApplicationContext(), "Failed to import settings from " + backupFile, Toast.LENGTH_LONG).show();
        }
        finally
        {
            try
            {
                if (reader != null)
                {
                    reader.close();
                }
                if (input != null)
                {
                    input.close();
                }
            }
            catch (IOException ex)
            {
                Toast.makeText(getApplicationContext(), "Failed to close import file", Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * Pure classification of one export-file entry for import.
     * Matches v2026.11.2 {@code PlezyPlexToZidooSettings.txt} keys (and current).
     */
    static final class ImportAction {
        enum Kind { SECURE_PLEX_TOKEN, SECURE_SMB_PASSWORD, PREF_STRING, PREF_BOOLEAN, SKIP }

        final Kind kind;
        final String storageKey;
        final String stringValue;
        final boolean booleanValue;

        ImportAction(Kind kind, String storageKey, String stringValue, boolean booleanValue) {
            this.kind = kind;
            this.storageKey = storageKey;
            this.stringValue = stringValue;
            this.booleanValue = booleanValue;
        }
    }

    /**
     * Maps a single JSON export entry to the storage destination used on import.
     * Fake credentials only in tests — never log values.
     */
    static ImportAction classifyImportEntry(String key, Object rawValue) {
        if (key == null) {
            return new ImportAction(ImportAction.Kind.SKIP, null, null, false);
        }
        String asString = (rawValue != null) ? rawValue.toString() : "";
        if ("plex_token".equals(key) || "plex_access_token".equals(key)) {
            return new ImportAction(ImportAction.Kind.SECURE_PLEX_TOKEN, "plex_access_token",
                    asString.trim(), false);
        }
        if (key.startsWith("smbPassword")) {
            String suffix = key.substring("smbPassword".length());
            return new ImportAction(ImportAction.Kind.SECURE_SMB_PASSWORD,
                    SecureStorage.smbSecureKey(suffix), asString, false);
        }
        // Boolean switches (skip_intros, etc.) — string prefs (URLs, paths, usernames, intervals).
        if (rawValue instanceof Boolean) {
            return new ImportAction(ImportAction.Kind.PREF_BOOLEAN, key, null,
                    (Boolean) rawValue);
        }
        if ("true".equalsIgnoreCase(asString) || "false".equalsIgnoreCase(asString)) {
            return new ImportAction(ImportAction.Kind.PREF_BOOLEAN, key, null,
                    Boolean.parseBoolean(asString));
        }
        return new ImportAction(ImportAction.Kind.PREF_STRING, key,
                asString.isEmpty() ? null : asString, false);
    }

    /**
     * Applies a full export map into destination maps (no Android UI). Used by unit tests
     * and keeps import key routing consistent with {@link #setPreferenceOnImport}.
     */
    static void applyImportMap(Map<String, ?> exportMap,
                               Map<String, String> secureOut,
                               Map<String, Object> prefsOut) {
        if (exportMap == null) {
            return;
        }
        for (Map.Entry<String, ?> entry : exportMap.entrySet()) {
            ImportAction action = classifyImportEntry(entry.getKey(), entry.getValue());
            switch (action.kind) {
                case SECURE_PLEX_TOKEN:
                case SECURE_SMB_PASSWORD:
                    secureOut.put(action.storageKey, action.stringValue != null ? action.stringValue : "");
                    break;
                case PREF_STRING:
                    prefsOut.put(action.storageKey, action.stringValue);
                    break;
                case PREF_BOOLEAN:
                    prefsOut.put(action.storageKey, action.booleanValue);
                    break;
                case SKIP:
                default:
                    break;
            }
        }
    }

    public void setPreferenceOnImport(Map.Entry<String, ?> entry)
    {
        ImportAction action = classifyImportEntry(entry.getKey(), entry.getValue());
        switch (action.kind) {
            case SECURE_PLEX_TOKEN: {
                SecureStorage.getInstance(getApplicationContext()).edit()
                        .putString("plex_access_token", action.stringValue).apply();
                EditTextPreference tokenPref = settingsFragment.findPreference("plex_token");
                if (tokenPref != null) {
                    String v = action.stringValue != null ? action.stringValue : "";
                    tokenPref.setSummary(v.isEmpty() ? "Not set (optional fallback)" : "********");
                }
                return;
            }
            case SECURE_SMB_PASSWORD: {
                String key = entry.getKey();
                String suffix = key.substring("smbPassword".length());
                SecureStorage.putSmbPassword(getApplicationContext(), suffix,
                        action.stringValue != null ? action.stringValue : "");
                EditTextPreference smbPref = settingsFragment.findPreference(key);
                if (smbPref != null) {
                    String v = action.stringValue != null ? action.stringValue : "";
                    smbPref.setSummary(v.isEmpty() ? "Not set" : "********");
                }
                return;
            }
            case SKIP:
                return;
            default:
                break;
        }

        Preference pref = settingsFragment.findPreference(entry.getKey());
        if(pref instanceof EditTextPreference)
        {
            if(entry.getValue() == null || entry.getValue().toString().isEmpty())
            {
                ((EditTextPreference)pref).setText(null);
            }
            else
            {
                ((EditTextPreference)pref).setText(entry.getValue().toString());
            }
        }
        else if(pref instanceof SwitchPreference)
        {
            if(entry.getValue() == null || entry.getValue().toString().isEmpty())
            {
                ((SwitchPreference)pref).setChecked(false);
            }
            else
            {
                ((SwitchPreference)pref).setChecked(Boolean.parseBoolean(entry.getValue().toString()));
            }
        }
    }

    public void onExportClickMethod(View view)
    {
        if (checkAndRequestPermissions(PERMISSION_REQUEST_EXPORT))
        {
            return;
        }

        exportSettings();
    }

    static boolean isPasswordKey(String key)
    {
        return key != null && ("plex_token".equals(key) || "plex_access_token".equals(key)
                || key.startsWith("smbPassword"));
    }

    static String buildExportJson(Map<String, ?> prefsMap, boolean includePasswords)
    {
        LinkedHashMap<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : prefsMap.entrySet())
        {
            String key = entry.getKey();
            if ("plex_access_token".equals(key))
            {
                continue;
            }
            if (!includePasswords && isPasswordKey(key))
            {
                continue;
            }
            filtered.put(key, entry.getValue());
        }

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(filtered);
    }

    public void exportSettings()
    {
        FileOutputStream output = null;
        try
        {
            output = new FileOutputStream(backupFile);

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            Map<String,Object> prefsMap = prefs.getAll().entrySet()
                                                   .stream()
                                                   .sorted(Map.Entry.comparingByKey())
                                                   .collect(Collectors.toMap(Map.Entry::getKey,
                                                                             e -> (Object) e.getValue(),
                                                                             (oldValue, newValue) -> oldValue, LinkedHashMap::new));

            boolean includePasswords = prefs.getBoolean("export_include_passwords", false);

            if(includePasswords)
            {
                String plexToken = SecureStorage.getInstance(getApplicationContext()).getString("plex_access_token", "");
                if(plexToken != null && !plexToken.isEmpty())
                {
                    prefsMap.put("plex_token", plexToken);
                }
                for (String suffix : SecureStorage.SMB_PASSWORD_SUFFIXES)
                {
                    String smb = SecureStorage.getSmbPassword(getApplicationContext(), suffix);
                    if (smb != null && !smb.isEmpty())
                    {
                        prefsMap.put(SecureStorage.smbPlaintextKey(suffix), smb);
                    }
                }
            }

            String json = buildExportJson(prefsMap, includePasswords);
            output.write(json.getBytes(StandardCharsets.UTF_8));

            String exportedMessage = includePasswords
                    ? "Settings and passwords exported to " + backupFile
                    : "Settings exported to " + backupFile + " (passwords excluded)";
            Toast.makeText(getApplicationContext(), exportedMessage, Toast.LENGTH_LONG).show();
        }
        catch (Exception e)
        {
            Toast.makeText(getApplicationContext(), e.toString(), Toast.LENGTH_LONG).show();
        }
        finally
        {
            try
            {
                if (output != null)
                {
                    output.flush();
                    output.close();
                }
            }
            catch (IOException ex)
            {
                Toast.makeText(getApplicationContext(), "Failed to close export file", Toast.LENGTH_LONG).show();
            }
        }
    }

    public Boolean checkAndRequestPermissions(int requestCode)
    {
        if(checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
        {
            requestPermissions(permissions, requestCode);
            return true;
        }

        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults)
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if(requestCode == PERMISSION_REQUEST_IMPORT || requestCode == PERMISSION_REQUEST_EXPORT)
        {
            if(grantResults.length >= 2
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && grantResults[1] == PackageManager.PERMISSION_GRANTED)
            {
                if(requestCode == PERMISSION_REQUEST_IMPORT)
                {
                    importSettings();
                }
                else
                {
                    exportSettings();
                }
            }
            else
            {
                Toast.makeText(getApplicationContext(), "Please give storage access in order to import/export settings", Toast.LENGTH_LONG).show();
            }
        }
    }
}
