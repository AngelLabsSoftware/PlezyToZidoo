package com.plezyplextozidoo;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import androidx.preference.PreferenceManager;

/**
 * Secondary screen for path substitution rules 2–10 and per-rule SMB credentials.
 * Same preference keys as the former PreferenceFragment screen.
 */
public class SubstitutionRulesActivity extends AppCompatActivity {

    /** Rule numbers 2–10 map to suffixes _02 … _10. */
    private static final String[] RULE_SUFFIXES = {
            "_02", "_03", "_04", "_05", "_06", "_07", "_08", "_09", "_10"
    };

    private SharedPreferences prefs;
    private ScrollView rulesScroll;
    private boolean suppressWatchers;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_substitution_rules);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        AppCompatButton back = findViewById(R.id.btnRulesBack);
        back.setBackgroundTintList(null);
        back.setBackgroundResource(R.drawable.btn_tv_focus);
        back.setOnClickListener(v -> finish());

        rulesScroll = findViewById(R.id.rulesScroll);
        LinearLayout container = findViewById(R.id.rulesContainer);
        LayoutInflater inflater = LayoutInflater.from(this);

        for (int i = 0; i < RULE_SUFFIXES.length; i++) {
            String suffix = RULE_SUFFIXES[i];
            int ruleNumber = i + 2;
            View card = inflater.inflate(R.layout.dash_rule_card, container, false);
            card.setBackgroundResource(i % 2 == 0
                    ? R.drawable.dash_panel_bg
                    : R.drawable.dash_panel_alt_bg);

            TextView title = card.findViewById(R.id.ruleTitle);
            title.setText(getString(R.string.rule_card_title, ruleNumber));

            EditText pathReplace = card.findViewById(R.id.rulePathReplace);
            EditText pathWith = card.findViewById(R.id.rulePathWith);
            EditText smbUser = card.findViewById(R.id.ruleSmbUser);
            EditText smbPassword = card.findViewById(R.id.ruleSmbPassword);

            suppressWatchers = true;
            pathReplace.setText(prefs.getString("path_to_replace" + suffix, ""));
            pathWith.setText(prefs.getString("replaced_with" + suffix, ""));
            smbUser.setText(prefs.getString("smbUsername" + suffix, ""));
            String existingPw = SecureStorage.getSmbPassword(this, suffix);
            smbPassword.setText(existingPw != null ? existingPw : "");
            suppressWatchers = false;

            bindStringPref(pathReplace, "path_to_replace" + suffix);
            bindStringPref(pathWith, "replaced_with" + suffix);
            bindStringPref(smbUser, "smbUsername" + suffix);
            bindSmbPassword(smbPassword, suffix);

            container.addView(card);
        }

        ensureFocusScroll();
        back.requestFocus();
    }

    private void bindStringPref(EditText field, String key) {
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (suppressWatchers) return;
                prefs.edit().putString(key, s != null ? s.toString() : "").apply();
            }
        });
    }

    private void bindSmbPassword(EditText field, String suffix) {
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (suppressWatchers) return;
                SecureStorage.putSmbPassword(SubstitutionRulesActivity.this, suffix,
                        s != null ? s.toString() : "");
            }
        });
    }

    private void ensureFocusScroll() {
        View root = findViewById(android.R.id.content);
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            if (newFocus != null && rulesScroll != null) {
                rulesScroll.post(() -> scrollToView(newFocus));
            }
        });
    }

    private void scrollToView(View focus) {
        if (rulesScroll == null || focus == null) return;
        int[] loc = new int[2];
        int[] scrollLoc = new int[2];
        focus.getLocationOnScreen(loc);
        rulesScroll.getLocationOnScreen(scrollLoc);
        int relativeTop = loc[1] - scrollLoc[1];
        int relativeBottom = relativeTop + focus.getHeight();
        int visible = rulesScroll.getHeight();
        if (relativeTop < 0) {
            rulesScroll.smoothScrollBy(0, relativeTop - 24);
        } else if (relativeBottom > visible) {
            rulesScroll.smoothScrollBy(0, relativeBottom - visible + 24);
        }
    }

    /** Legacy XML onClick hooks (substitution_back / import_export layouts). */
    public void onSubstitutionBackClickMethod(View view) {
        finish();
    }

    public void onImportClickMethod(View view) {}
    public void onExportClickMethod(View view) {}
}
