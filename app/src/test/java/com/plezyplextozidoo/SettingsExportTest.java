package com.plezyplextozidoo;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class SettingsExportTest {

    @Test
    public void isPasswordKey_detectsTokenAndSmb() {
        assertTrue(SettingsActivity.isPasswordKey("plex_token"));
        assertTrue(SettingsActivity.isPasswordKey("plex_access_token"));
        assertTrue(SettingsActivity.isPasswordKey("smbPassword"));
        assertTrue(SettingsActivity.isPasswordKey("smbPassword_02"));
        assertFalse(SettingsActivity.isPasswordKey("plex_server_url"));
        assertFalse(SettingsActivity.isPasswordKey("debug"));
    }

    @Test
    public void buildExportJson_excludesTokenByDefault() {
        Map<String, Object> prefs = new LinkedHashMap<>();
        prefs.put("plex_server_url", "http://host:32400");
        prefs.put("plex_token", "secret-token");
        prefs.put("debug", true);

        String json = SettingsActivity.buildExportJson(prefs, false);
        assertTrue(json.contains("http://host:32400"));
        assertFalse(json.contains("secret-token"));
        assertTrue(json.contains("debug"));
    }

    @Test
    public void buildExportJson_includesTokenWhenRequested() {
        Map<String, Object> prefs = new LinkedHashMap<>();
        prefs.put("plex_token", "secret-token");
        prefs.put("smbPassword", "smb-secret");

        String json = SettingsActivity.buildExportJson(prefs, true);
        assertTrue(json.contains("secret-token"));
        assertTrue(json.contains("smb-secret"));
    }

    @Test
    public void buildExportJson_alwaysDropsAccessTokenKey() {
        Map<String, Object> prefs = new LinkedHashMap<>();
        prefs.put("plex_access_token", "should-never-appear");
        String json = SettingsActivity.buildExportJson(prefs, true);
        assertFalse(json.contains("should-never-appear"));
    }

    @Test
    public void sanitizeExportString_stripsPlexTokenQuery() {
        String raw = "http://host:32400/library/parts/1/file.mkv?X-Plex-Token=secretVALUE&foo=1";
        String clean = SettingsActivity.sanitizeExportString(raw);
        assertFalse(clean.contains("secretVALUE"));
        assertFalse(clean.toLowerCase().contains("x-plex-token=secret"));
        assertTrue(clean.contains("foo=1") || clean.contains("file.mkv"));
    }

    @Test
    public void sanitizeExportString_stripsUrlUserInfo() {
        String raw = "smb://user:s3cret@192.168.1.5/share/Movies";
        String clean = SettingsActivity.sanitizeExportString(raw);
        assertFalse(clean.contains("s3cret"));
        assertFalse(clean.contains("user:"));
        assertTrue(clean.startsWith("smb://"));
        assertTrue(clean.contains("192.168.1.5/share/Movies"));
    }

    @Test
    public void buildExportJson_sanitizesEmbeddedAuthWhenPasswordsOff() {
        Map<String, Object> prefs = new LinkedHashMap<>();
        prefs.put("path_to_replace", "smb://u:p@host/media/");
        prefs.put("plex_token", "secret-token");
        prefs.put("debug", false);

        String json = SettingsActivity.buildExportJson(prefs, false);
        assertFalse(json.contains("secret-token"));
        assertFalse(json.contains("u:p@"));
        assertTrue(json.contains("smb://"));
        assertTrue(json.contains("debug"));
    }

    @Test
    public void buildExportJson_keepsEmbeddedAuthWhenPasswordsOn() {
        Map<String, Object> prefs = new LinkedHashMap<>();
        prefs.put("replaced_with", "smb://u:p@host/data/");
        String json = SettingsActivity.buildExportJson(prefs, true);
        assertTrue(json.contains("u:p@host"));
    }
}
