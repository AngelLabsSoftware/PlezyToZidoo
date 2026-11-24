package com.plezyplextozidoo;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.SwitchCompat;
import androidx.preference.PreferenceManager;

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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * TV-oriented settings dashboard. Preference keys match the previous PreferenceFragment
 * screen so upgrades keep saved values. Import/export unchanged in format.
 */
public class SettingsActivity extends AppCompatActivity {

    private final String backupFile = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS) + "/PlezyPlexToZidooSettings.txt";
    private static final int PERMISSION_REQUEST_IMPORT = 1;
    private static final int PERMISSION_REQUEST_EXPORT = 2;
    private final String[] permissions = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    private SharedPreferences prefs;
    private SharedPreferences securePrefs;
    private ScrollView settingsScroll;

    private SwitchCompat swUseZidoo;
    private SwitchCompat swShowTitle;
    private SwitchCompat swSkipIntros;
    private SwitchCompat swSkipCredits;
    private SwitchCompat swRecentOnly;
    private SwitchCompat swAllowLowerRes;
    private SwitchCompat swShowLogo;
    private SwitchCompat swTextFallback;
    private SwitchCompat swShowYear;
    private SwitchCompat swDebug;
    private SwitchCompat swExportPasswords;

    private EditText inputServerUrl;
    private EditText inputPlexToken;
    private EditText inputPathReplace;
    private EditText inputPathWith;
    private TextView overlayStatus;

    private boolean suppressWatchers;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SecureStorage.removeLegacyPlaintextPassword(getApplicationContext());
        SecureStorage.migrateSmbPasswords(getApplicationContext());

        String deviceId = SecureStorage.getDeviceId(getApplicationContext());
        PlexApi.setClientIdentity(Build.MODEL, deviceId, BuildConfig.VERSION_NAME);

        setContentView(R.layout.activity_settings_dashboard);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        securePrefs = SecureStorage.getInstance(this);

        settingsScroll = findViewById(R.id.settingsScroll);
        bindToggle(R.id.rowUseZidoo, R.string.useZidooPlayer_title);
        bindToggle(R.id.rowShowTitle, R.string.showTitle_title);
        bindToggle(R.id.rowSkipIntros, R.string.skip_intros_title);
        bindToggle(R.id.rowSkipCredits, R.string.skip_credits_title);
        bindToggle(R.id.rowRecentOnly, R.string.screensaver_recent_title);
        bindToggle(R.id.rowAllowLowerRes, R.string.screensaver_allow_lower_res_title);
        bindToggle(R.id.rowShowLogo, R.string.screensaver_show_logo_title);
        bindToggle(R.id.rowTextFallback, R.string.screensaver_allow_text_title_title);
        bindToggle(R.id.rowShowYear, R.string.screensaver_show_date_title);
        bindToggle(R.id.rowDebug, R.string.debug_title);
        bindToggle(R.id.rowExportPasswords, R.string.export_include_passwords_title);

        swUseZidoo = switchOf(R.id.rowUseZidoo);
        swShowTitle = switchOf(R.id.rowShowTitle);
        swSkipIntros = switchOf(R.id.rowSkipIntros);
        swSkipCredits = switchOf(R.id.rowSkipCredits);
        swRecentOnly = switchOf(R.id.rowRecentOnly);
        swAllowLowerRes = switchOf(R.id.rowAllowLowerRes);
        swShowLogo = switchOf(R.id.rowShowLogo);
        swTextFallback = switchOf(R.id.rowTextFallback);
        swShowYear = switchOf(R.id.rowShowYear);
        swDebug = switchOf(R.id.rowDebug);
        swExportPasswords = switchOf(R.id.rowExportPasswords);

        inputServerUrl = findViewById(R.id.inputServerUrl);
        inputPlexToken = findViewById(R.id.inputPlexToken);
        inputPathReplace = findViewById(R.id.inputPathReplace);
        inputPathWith = findViewById(R.id.inputPathWith);
        overlayStatus = findViewById(R.id.overlayStatus);

        AppCompatButton btnPreview = findViewById(R.id.btnPreviewScreensaver);
        AppCompatButton btnTest = findViewById(R.id.btnTestConnection);
        AppCompatButton btnManage = findViewById(R.id.btnManageRules);
        AppCompatButton btnImport = findViewById(R.id.btnImport);
        AppCompatButton btnExport = findViewById(R.id.btnExport);
        clearTint(btnPreview, btnTest, btnManage, btnImport, btnExport);

        loadFromPrefs();
        wireSwitches();
        wireSegments();
        wireTextFields();
        wireButtons(btnPreview, btnTest, btnManage, btnImport, btnExport);
        wireOverlayStatus();
        ensureFocusScroll();
    }

    @Override
    protected void onStart() {
        super.onStart();
        refreshOverlayStatus();
    }

    private void bindToggle(int includeId, int labelRes) {
        View row = findViewById(includeId);
        TextView label = row.findViewById(R.id.toggleLabel);
        label.setText(labelRes);
    }

    private SwitchCompat switchOf(int includeId) {
        return findViewById(includeId).findViewById(R.id.toggleSwitch);
    }

    private void clearTint(AppCompatButton... buttons) {
        for (AppCompatButton b : buttons) {
            // Public API (minSdk 28) — avoids RestrictedApi on setSupportBackgroundTintList.
            b.setBackgroundTintList(null);
            b.setBackgroundResource(R.drawable.btn_tv_focus);
        }
    }

    private void loadFromPrefs() {
        suppressWatchers = true;
        swUseZidoo.setChecked(prefs.getBoolean("useZidooPlayer", true));
        swShowTitle.setChecked(prefs.getBoolean("showTitle", true));
        swSkipIntros.setChecked(prefs.getBoolean("skip_intros", true));
        swSkipCredits.setChecked(prefs.getBoolean("skip_credits", true));
        swRecentOnly.setChecked(prefs.getBoolean("screensaver_recent_only", false));
        swAllowLowerRes.setChecked(prefs.getBoolean("screensaver_allow_lower_res", false));
        swShowLogo.setChecked(prefs.getBoolean("screensaver_show_logo", true));
        swTextFallback.setChecked(prefs.getBoolean("screensaver_allow_text_title", false));
        swShowYear.setChecked(prefs.getBoolean("screensaver_show_date", true));
        swDebug.setChecked(prefs.getBoolean("debug", false));
        swExportPasswords.setChecked(prefs.getBoolean("export_include_passwords", false));

        inputServerUrl.setText(prefs.getString("plex_server_url", ""));
        String token = securePrefs.getString("plex_access_token", "");
        inputPlexToken.setText(token != null ? token : "");
        inputPathReplace.setText(prefs.getString("path_to_replace", ""));
        inputPathWith.setText(prefs.getString("replaced_with", ""));
        updatePlayerDependentEnabled();
        suppressWatchers = false;
    }

    private void updatePlayerDependentEnabled() {
        boolean on = swUseZidoo.isChecked();
        swShowTitle.setEnabled(on);
        swSkipIntros.setEnabled(on);
        swSkipCredits.setEnabled(on);
        findViewById(R.id.segSkipBehavior).setEnabled(on);
        overlayStatus.setEnabled(on);
        overlayStatus.setAlpha(on ? 1f : 0.45f);
    }

    private void wireSwitches() {
        swUseZidoo.setOnCheckedChangeListener((b, v) -> {
            if (suppressWatchers) return;
            prefs.edit().putBoolean("useZidooPlayer", v).apply();
            updatePlayerDependentEnabled();
        });
        bindBool(swShowTitle, "showTitle");
        bindBool(swSkipIntros, "skip_intros");
        bindBool(swSkipCredits, "skip_credits");
        bindBool(swRecentOnly, "screensaver_recent_only");
        bindBool(swAllowLowerRes, "screensaver_allow_lower_res");
        bindBool(swShowLogo, "screensaver_show_logo");
        bindBool(swTextFallback, "screensaver_allow_text_title");
        bindBool(swShowYear, "screensaver_show_date");
        bindBool(swDebug, "debug");
        bindBool(swExportPasswords, "export_include_passwords");
    }

    private void bindBool(SwitchCompat sw, String key) {
        sw.setOnCheckedChangeListener((b, v) -> {
            if (suppressWatchers) return;
            prefs.edit().putBoolean(key, v).apply();
        });
    }

    private void wireSegments() {
        SegmentedControl.bind(findViewById(R.id.segSkipBehavior),
                getStringArray(R.array.skip_behavior_segment_labels),
                getStringArray(R.array.skip_behavior_values),
                prefs.getString("skip_behavior", "manual"),
                value -> prefs.edit().putString("skip_behavior", value).apply());

        String upNext = prefs.getString(UpNextActivity.PREF_UP_NEXT_COUNTDOWN_SEC,
                String.valueOf(UpNextActivity.DEFAULT_UP_NEXT_COUNTDOWN_SEC));
        SegmentedControl.bind(findViewById(R.id.segUpNextCountdown),
                getStringArray(R.array.up_next_countdown_labels),
                getStringArray(R.array.up_next_countdown_values),
                String.valueOf(UpNextActivity.normalizeCountdownSec(upNext)),
                value -> prefs.edit().putString(UpNextActivity.PREF_UP_NEXT_COUNTDOWN_SEC, value).apply());

        SegmentedControl.bind(findViewById(R.id.segLibrary),
                getStringArray(R.array.screensaver_library_labels),
                getStringArray(R.array.screensaver_library_values),
                prefs.getString("screensaver_library", "both"),
                value -> prefs.edit().putString("screensaver_library", value).apply());

        SegmentedControl.bind(findViewById(R.id.segLogoSize),
                getStringArray(R.array.screensaver_logo_size_entries),
                getStringArray(R.array.screensaver_logo_size_values),
                ScreensaverRenderer.normalizeLogoSize(
                        prefs.getString("screensaver_logo_size", ScreensaverRenderer.LOGO_SIZE_MEDIUM)),
                value -> prefs.edit().putString("screensaver_logo_size", value).apply());

        String interval = normalizeInterval(prefs.getString("screensaver_interval_sec", "20"));
        SegmentedControl.bind(findViewById(R.id.segSlideDuration),
                getStringArray(R.array.screensaver_interval_labels),
                getStringArray(R.array.screensaver_interval_values),
                interval,
                value -> prefs.edit().putString("screensaver_interval_sec", value).apply());

        SegmentedControl.bind(findViewById(R.id.segCrossfade),
                getStringArray(R.array.screensaver_crossfade_labels),
                getStringArray(R.array.screensaver_crossfade_values),
                prefs.getString("screensaver_crossfade_sec", "1"),
                value -> prefs.edit().putString("screensaver_crossfade_sec", value).apply());

        SegmentedControl.bind(findViewById(R.id.segAnimSpeed),
                getStringArray(R.array.screensaver_anim_speed_labels),
                getStringArray(R.array.screensaver_anim_speed_values),
                prefs.getString("screensaver_anim_speed", "1"),
                value -> prefs.edit().putString("screensaver_anim_speed", value).apply());
    }

    /** Map legacy 10/15 → nearest supported segment value without rewriting storage until user picks. */
    static String normalizeInterval(String raw) {
        if ("10".equals(raw) || "15".equals(raw)) return "20";
        if ("20".equals(raw) || "30".equals(raw) || "45".equals(raw) || "60".equals(raw)) return raw;
        return "20";
    }

    private String[] getStringArray(int id) {
        return getResources().getStringArray(id);
    }

    private void wireTextFields() {
        inputServerUrl.addTextChangedListener(simpleWatcher(s -> {
            if (suppressWatchers) return;
            // Persist trimmed; port dialog on focus loss / test.
            String trimmed = s.trim();
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            prefs.edit().putString("plex_server_url", trimmed).apply();
        }));
        inputServerUrl.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) maybeOfferPort();
        });

        inputPlexToken.addTextChangedListener(simpleWatcher(s -> {
            if (suppressWatchers) return;
            securePrefs.edit().putString("plex_access_token", s.trim()).apply();
        }));

        inputPathReplace.addTextChangedListener(simpleWatcher(s -> {
            if (suppressWatchers) return;
            prefs.edit().putString("path_to_replace", s).apply();
        }));
        inputPathWith.addTextChangedListener(simpleWatcher(s -> {
            if (suppressWatchers) return;
            prefs.edit().putString("replaced_with", s).apply();
        }));
    }

    private void maybeOfferPort() {
        String value = inputServerUrl.getText() != null ? inputServerUrl.getText().toString().trim() : "";
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        final String suggested = PlexApi.suggestAppendDefaultPlexPort(value);
        if (suggested == null || suggested.equals(value)) return;
        final String current = value;
        new AlertDialog.Builder(this)
                .setTitle(R.string.plex_server_url_port_title)
                .setMessage(getString(R.string.plex_server_url_port_message, suggested))
                .setPositiveButton(R.string.plex_server_url_port_yes, (d, w) -> {
                    suppressWatchers = true;
                    inputServerUrl.setText(suggested);
                    suppressWatchers = false;
                    prefs.edit().putString("plex_server_url", suggested).apply();
                })
                .setNegativeButton(R.string.plex_server_url_port_no, (d, w) ->
                        prefs.edit().putString("plex_server_url", current).apply())
                .setCancelable(true)
                .show();
    }

    private interface StringConsumer {
        void accept(String s);
    }

    private TextWatcher simpleWatcher(StringConsumer consumer) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                consumer.accept(s != null ? s.toString() : "");
            }
        };
    }

    private void wireButtons(AppCompatButton btnPreview, AppCompatButton btnTest,
                             AppCompatButton btnManage, AppCompatButton btnImport,
                             AppCompatButton btnExport) {
        btnPreview.setOnClickListener(v ->
                startActivity(new Intent(this, ScreensaverPreviewActivity.class)));
        btnManage.setOnClickListener(v ->
                startActivity(new Intent(this, SubstitutionRulesActivity.class)));
        btnImport.setOnClickListener(v -> {
            if (!checkAndRequestPermissions(PERMISSION_REQUEST_IMPORT)) importSettings();
        });
        btnExport.setOnClickListener(v -> {
            if (!checkAndRequestPermissions(PERMISSION_REQUEST_EXPORT)) exportSettings();
        });
        btnTest.setOnClickListener(v -> testConnection());
    }

    private void wireOverlayStatus() {
        overlayStatus.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this,
                        "Open Settings → Apps → Special access → Display over other apps",
                        Toast.LENGTH_LONG).show();
            }
        });
        refreshOverlayStatus();
    }

    private void refreshOverlayStatus() {
        boolean allowed = SkipOverlay.canDrawOverlays(getApplicationContext());
        overlayStatus.setText(allowed
                ? R.string.overlay_permission_granted
                : R.string.overlay_permission_denied);
    }

    private void testConnection() {
        String serverUrl = prefs.getString("plex_server_url", "");
        String token = securePrefs.getString("plex_access_token", "");
        if (serverUrl == null || serverUrl.isEmpty()) {
            Toast.makeText(this, "Please configure a Server URL first", Toast.LENGTH_LONG).show();
            return;
        }
        if (token == null || token.isEmpty()) {
            Toast.makeText(this, "Please configure a Plex token first", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Testing…", Toast.LENGTH_SHORT).show();
        PlexApi.testConnection(serverUrl, token, new PlexApi.SimpleCallback() {
            @Override
            public void onSuccess(String message) {
                Toast.makeText(SettingsActivity.this, message, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onError(String error) {
                Toast.makeText(SettingsActivity.this, "Connection failed: " + error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void ensureFocusScroll() {
        View root = findViewById(android.R.id.content);
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            if (newFocus != null && settingsScroll != null) {
                settingsScroll.post(() -> scrollToView(newFocus));
            }
        });
    }

    private void scrollToView(View focus) {
        if (settingsScroll == null || focus == null) return;
        int[] loc = new int[2];
        int[] scrollLoc = new int[2];
        focus.getLocationOnScreen(loc);
        settingsScroll.getLocationOnScreen(scrollLoc);
        int relativeTop = loc[1] - scrollLoc[1];
        int relativeBottom = relativeTop + focus.getHeight();
        int visible = settingsScroll.getHeight();
        if (relativeTop < 0) {
            settingsScroll.smoothScrollBy(0, relativeTop - 24);
        } else if (relativeBottom > visible) {
            settingsScroll.smoothScrollBy(0, relativeBottom - visible + 24);
        }
    }

    // --- Import / export (format compatible with prior PreferenceFragment builds) ---

    public void importSettings() {
        FileInputStream input = null;
        BufferedReader reader = null;
        try {
            input = new FileInputStream(backupFile);
            reader = new BufferedReader(new InputStreamReader(input));
            Gson gson = new Gson();
            Type mapType = new TypeToken<Map<String, ?>>() {}.getType();
            Map<String, ?> map = gson.fromJson(reader, mapType);

            Map<String, String> secureOut = new HashMap<>();
            Map<String, Object> prefsOut = new HashMap<>();
            applyImportMap(map, secureOut, prefsOut);

            SharedPreferences.Editor secureEd = securePrefs.edit();
            for (Map.Entry<String, String> e : secureOut.entrySet()) {
                secureEd.putString(e.getKey(), e.getValue());
            }
            secureEd.apply();

            SharedPreferences.Editor prefEd = prefs.edit();
            for (Map.Entry<String, Object> e : prefsOut.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Boolean) {
                    prefEd.putBoolean(e.getKey(), (Boolean) v);
                } else if (v == null) {
                    prefEd.remove(e.getKey());
                } else {
                    prefEd.putString(e.getKey(), v.toString());
                }
            }
            prefEd.apply();

            // SMB passwords keyed by plaintext suffix form in export
            for (Map.Entry<String, ?> entry : map.entrySet()) {
                ImportAction action = classifyImportEntry(entry.getKey(), entry.getValue());
                if (action.kind == ImportAction.Kind.SECURE_SMB_PASSWORD) {
                    String key = entry.getKey();
                    String suffix = key.substring("smbPassword".length());
                    SecureStorage.putSmbPassword(getApplicationContext(), suffix,
                            action.stringValue != null ? action.stringValue : "");
                }
            }

            loadFromPrefs();
            wireSegments();
            Toast.makeText(this, "Settings imported successfully from " + backupFile, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to import settings from " + backupFile, Toast.LENGTH_LONG).show();
        } finally {
            try {
                if (reader != null) reader.close();
                if (input != null) input.close();
            } catch (IOException ex) {
                Toast.makeText(this, "Failed to close import file", Toast.LENGTH_LONG).show();
            }
        }
    }

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
        if (rawValue instanceof Boolean) {
            return new ImportAction(ImportAction.Kind.PREF_BOOLEAN, key, null, (Boolean) rawValue);
        }
        if ("true".equalsIgnoreCase(asString) || "false".equalsIgnoreCase(asString)) {
            return new ImportAction(ImportAction.Kind.PREF_BOOLEAN, key, null,
                    Boolean.parseBoolean(asString));
        }
        return new ImportAction(ImportAction.Kind.PREF_STRING, key,
                asString.isEmpty() ? null : asString, false);
    }

    static void applyImportMap(Map<String, ?> exportMap,
                               Map<String, String> secureOut,
                               Map<String, Object> prefsOut) {
        if (exportMap == null) return;
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

    static boolean isPasswordKey(String key) {
        return key != null && ("plex_token".equals(key) || "plex_access_token".equals(key)
                || key.startsWith("smbPassword"));
    }

    static String buildExportJson(Map<String, ?> prefsMap, boolean includePasswords) {
        LinkedHashMap<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : prefsMap.entrySet()) {
            String key = entry.getKey();
            if ("plex_access_token".equals(key)) continue;
            if (!includePasswords && isPasswordKey(key)) continue;
            filtered.put(key, entry.getValue());
        }
        return new GsonBuilder().setPrettyPrinting().create().toJson(filtered);
    }

    public void exportSettings() {
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(backupFile);
            Map<String, Object> prefsMap = prefs.getAll().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .collect(Collectors.toMap(Map.Entry::getKey,
                            e -> (Object) e.getValue(),
                            (a, b) -> a, LinkedHashMap::new));

            boolean includePasswords = prefs.getBoolean("export_include_passwords", false);
            if (includePasswords) {
                String plexToken = securePrefs.getString("plex_access_token", "");
                if (plexToken != null && !plexToken.isEmpty()) {
                    prefsMap.put("plex_token", plexToken);
                }
                for (String suffix : SecureStorage.SMB_PASSWORD_SUFFIXES) {
                    String smb = SecureStorage.getSmbPassword(getApplicationContext(), suffix);
                    if (smb != null && !smb.isEmpty()) {
                        prefsMap.put(SecureStorage.smbPlaintextKey(suffix), smb);
                    }
                }
            }

            String json = buildExportJson(prefsMap, includePasswords);
            output.write(json.getBytes(StandardCharsets.UTF_8));
            String msg = includePasswords
                    ? "Settings and passwords exported to " + backupFile
                    : "Settings exported to " + backupFile + " (passwords excluded)";
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show();
        } finally {
            try {
                if (output != null) {
                    output.flush();
                    output.close();
                }
            } catch (IOException ex) {
                Toast.makeText(this, "Failed to close export file", Toast.LENGTH_LONG).show();
            }
        }
    }

    public Boolean checkAndRequestPermissions(int requestCode) {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(permissions, requestCode);
            return true;
        }
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_IMPORT || requestCode == PERMISSION_REQUEST_EXPORT) {
            if (grantResults.length >= 2
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                if (requestCode == PERMISSION_REQUEST_IMPORT) importSettings();
                else exportSettings();
            } else {
                Toast.makeText(this, "Please give storage access in order to import/export settings",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    /** Kept for XML onClick from legacy import_export layout if inflated elsewhere. */
    public void onImportClickMethod(View view) {
        if (!checkAndRequestPermissions(PERMISSION_REQUEST_IMPORT)) importSettings();
    }

    public void onExportClickMethod(View view) {
        if (!checkAndRequestPermissions(PERMISSION_REQUEST_EXPORT)) exportSettings();
    }

    public void onSubstitutionBackClickMethod(View view) {
        // No-op on dashboard; SubstitutionRulesActivity handles Back.
        finish();
    }
}
