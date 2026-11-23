package com.plezyplextozidoo;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.junit.Test;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Verifies that a v2026.11.2-shaped {@code PlezyPlexToZidooSettings.txt} export
 * (with "Include passwords") restores Plex URL/token, SMB user/pass, path rules,
 * and skip prefs into the destinations used by import. Fake credentials only.
 */
public class SettingsImportCompatibilityTest {

    /** Minimal fake export matching keys written by v2026.11.2 export-with-passwords. */
    private static final String FAKE_V2026_11_2_EXPORT = "{\n"
            + "  \"plex_server_url\": \"http://192.0.2.10:32400\",\n"
            + "  \"plex_token\": \"fake-plex-token-for-unit-test\",\n"
            + "  \"smbUsername\": \"fake-smb-user\",\n"
            + "  \"smbPassword\": \"fake-smb-pass\",\n"
            + "  \"path_to_replace\": \"/volume1/media\",\n"
            + "  \"replaced_with\": \"smb://nas/media\",\n"
            + "  \"smbUsername_02\": \"fake-smb-user-2\",\n"
            + "  \"smbPassword_02\": \"fake-smb-pass-2\",\n"
            + "  \"path_to_replace_02\": \"/mnt/movies\",\n"
            + "  \"replaced_with_02\": \"smb://nas/movies\",\n"
            + "  \"skip_intros\": true,\n"
            + "  \"skip_credits\": false,\n"
            + "  \"skip_behavior\": \"overlay\",\n"
            + "  \"debug\": false,\n"
            + "  \"useZidooPlayer\": true,\n"
            + "  \"showTitle\": true\n"
            + "}";

    @Test
    public void v2026_11_2_export_restores_plex_url_token_smb_paths_and_skip() {
        Type mapType = new TypeToken<Map<String, Object>>(){}.getType();
        Map<String, Object> export = new Gson().fromJson(FAKE_V2026_11_2_EXPORT, mapType);
        assertNotNull(export);

        Map<String, String> secure = new HashMap<>();
        Map<String, Object> prefs = new LinkedHashMap<>();
        SettingsActivity.applyImportMap(export, secure, prefs);

        // Plex URL → default prefs; token → SecureStorage key plex_access_token
        assertEquals("http://192.0.2.10:32400", prefs.get("plex_server_url"));
        assertEquals("fake-plex-token-for-unit-test", secure.get("plex_access_token"));
        assertFalse("plex_token must not land in default prefs map", prefs.containsKey("plex_token"));

        // SMB user/pass + path rules (slot 1 + 02)
        assertEquals("fake-smb-user", prefs.get("smbUsername"));
        assertEquals("fake-smb-pass", secure.get("smb_password"));
        assertEquals("/volume1/media", prefs.get("path_to_replace"));
        assertEquals("smb://nas/media", prefs.get("replaced_with"));

        assertEquals("fake-smb-user-2", prefs.get("smbUsername_02"));
        assertEquals("fake-smb-pass-2", secure.get("smb_password_02"));
        assertEquals("/mnt/movies", prefs.get("path_to_replace_02"));
        assertEquals("smb://nas/movies", prefs.get("replaced_with_02"));

        // Skip prefs
        assertEquals(Boolean.TRUE, prefs.get("skip_intros"));
        assertEquals(Boolean.FALSE, prefs.get("skip_credits"));
        assertEquals("overlay", prefs.get("skip_behavior"));

        assertEquals(Boolean.TRUE, prefs.get("useZidooPlayer"));
        assertEquals(Boolean.TRUE, prefs.get("showTitle"));
        assertEquals(Boolean.FALSE, prefs.get("debug"));
    }

    @Test
    public void classifyImportEntry_routesCredentialsToSecureKeys() {
        SettingsActivity.ImportAction token =
                SettingsActivity.classifyImportEntry("plex_token", "fake-token");
        assertEquals(SettingsActivity.ImportAction.Kind.SECURE_PLEX_TOKEN, token.kind);
        assertEquals("plex_access_token", token.storageKey);

        SettingsActivity.ImportAction smb =
                SettingsActivity.classifyImportEntry("smbPassword_03", "fake-pass");
        assertEquals(SettingsActivity.ImportAction.Kind.SECURE_SMB_PASSWORD, smb.kind);
        assertEquals("smb_password_03", smb.storageKey);
    }
}
