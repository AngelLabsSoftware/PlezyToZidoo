package com.plezyplextozidoo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
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

    public void setSmbPasswordPreference()
    {
        String[] pref_index = {"", "_02", "_03", "_04", "_05", "_06", "_07", "_08", "_09", "_10"};
        for (String s: pref_index)
        {
            EditTextPreference smbPasswordPreference = settingsFragment.findPreference("smbPassword" + s);
            if (smbPasswordPreference != null)
            {
                smbPasswordPreference.setOnBindEditTextListener(editText -> editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
            }
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

    public void setPreferenceOnImport(Map.Entry<String, ?> entry)
    {
        if(entry.getKey().equals("plex_token") || entry.getKey().equals("plex_access_token"))
        {
            String value = (entry.getValue() != null) ? entry.getValue().toString().trim() : "";
            SecureStorage.getInstance(getApplicationContext()).edit().putString("plex_access_token", value).apply();
            EditTextPreference tokenPref = settingsFragment.findPreference("plex_token");
            if(tokenPref != null)
            {
                tokenPref.setSummary(value.isEmpty() ? "Not set (optional fallback)" : "********");
            }
            return;
        }

        Preference pref = settingsFragment.findPreference(entry.getKey());
        if(pref instanceof EditTextPreference)
        {
            if(entry.getValue().toString().isEmpty())
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
            if(entry.getValue().toString().isEmpty())
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
