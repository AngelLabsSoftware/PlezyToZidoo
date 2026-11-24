package com.plezyplextozidoo;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceFragmentCompat;

/**
 * Secondary screen for path substitution rules 2–10 and per-rule SMB credentials.
 * Primary rule remains on the main settings dashboard.
 */
public class SubstitutionRulesActivity extends AppCompatActivity {

    private RulesFragment fragment;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);
        if (savedInstanceState == null) {
            fragment = new RulesFragment();
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings, fragment)
                    .commit();
        }
    }

    public void onSubstitutionBackClickMethod(View view) {
        finish();
    }

    /** Unused on this screen but referenced by shared import_export layout. */
    public void onImportClickMethod(View view) {}
    public void onExportClickMethod(View view) {}

    public static class RulesFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.substitution_preferences, rootKey);
            Context context = requireContext();
            for (String s : SecureStorage.SMB_PASSWORD_SUFFIXES) {
                EditTextPreference smbPasswordPreference = findPreference("smbPassword" + s);
                if (smbPasswordPreference == null) continue;
                String existing = SecureStorage.getSmbPassword(context, s);
                smbPasswordPreference.setSummary(
                        (existing != null && !existing.isEmpty()) ? "********" : "Not set");
                smbPasswordPreference.setOnBindEditTextListener(editText ->
                        editText.setInputType(InputType.TYPE_CLASS_TEXT
                                | InputType.TYPE_TEXT_VARIATION_PASSWORD));
                final String suffix = s;
                smbPasswordPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                    String value = (newValue != null) ? newValue.toString() : "";
                    SecureStorage.putSmbPassword(context, suffix, value);
                    smbPasswordPreference.setSummary(value.isEmpty() ? "Not set" : "********");
                    return false;
                });
            }
        }
    }
}
