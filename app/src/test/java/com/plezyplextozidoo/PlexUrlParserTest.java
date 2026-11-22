package com.plezyplextozidoo;

import org.junit.Test;

import static org.junit.Assert.*;

public class PlexUrlParserTest {

    @Test
    public void parsePlezyUri_extractsBasePartAndToken() {
        String url = "http://192.168.1.50:32400/library/parts/12345/67890/file.mkv?X-Plex-Token=abcTOKEN";
        PlexApi.ParsedIntent parsed = PlexApi.parsePlezyUri(url);
        assertNotNull(parsed);
        assertEquals("http://192.168.1.50:32400", parsed.serverUrl);
        assertEquals("12345", parsed.partId);
        assertEquals("abcTOKEN", parsed.token);
    }

    @Test
    public void parsePlezyUri_httpsWithoutToken() {
        String url = "https://plex.example.com:32400/library/parts/99/1/file.mkv";
        PlexApi.ParsedIntent parsed = PlexApi.parsePlezyUri(url);
        assertNotNull(parsed);
        assertEquals("https://plex.example.com:32400", parsed.serverUrl);
        assertEquals("99", parsed.partId);
        assertNull(parsed.token);
    }

    @Test
    public void parsePlezyUri_returnsNullForNonPartsUrl() {
        assertNull(PlexApi.parsePlezyUri("http://192.168.1.50:32400/library/metadata/1"));
        assertNull(PlexApi.parsePlezyUri("smb://host/share/file.mkv"));
        assertNull(PlexApi.parsePlezyUri(null));
        assertNull(PlexApi.parsePlezyUri(""));
    }

    @Test
    public void extractPartId_works() {
        assertEquals("42", PlexApi.extractPartId("http://h/library/parts/42/0/file.mkv"));
        assertNull(PlexApi.extractPartId("http://h/library/metadata/42"));
    }

    @Test
    public void extractBaseUrl_stripsPath() {
        assertEquals("http://host:32400", PlexApi.extractBaseUrl("http://host:32400/library/parts/1/2/f.mkv"));
        assertEquals("https://plex.tv", PlexApi.extractBaseUrl("https://plex.tv/foo"));
    }
}
