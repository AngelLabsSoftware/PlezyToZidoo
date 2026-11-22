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
}
