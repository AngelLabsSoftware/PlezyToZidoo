package com.plezyplextozidoo;

import org.junit.Test;

import static org.junit.Assert.*;

public class TokenMaskingTest {

    @Test
    public void maskTokens_redactsAsStars() {
        String input = "data: http://h/library/parts/1/2/f.mkv?X-Plex-Token=secretVALUE&foo=1";
        String masked = Play.maskTokens(input);
        assertTrue(masked.contains("X-Plex-Token=***"));
        assertFalse(masked.contains("secretVALUE"));
        assertFalse(masked.contains("<token>"));
    }

    @Test
    public void resolveFallback_prefersOriginalUriWithRealToken() {
        String original = "http://192.168.1.50:32400/library/parts/1/2/file.mkv?X-Plex-Token=realTok";
        String resolved = Play.resolveFallbackPlaybackUri(original, "settingsTok");
        assertEquals(original, resolved);
    }

    @Test
    public void resolveFallback_stripsLiteralPlaceholderAndInjectsToken() {
        String broken = "http://h/library/parts/1/2/f.mkv?X-Plex-Token=<token>";
        String resolved = Play.resolveFallbackPlaybackUri(broken, "abc123");
        assertFalse(resolved.contains("<token>"));
        assertTrue(resolved.contains("X-Plex-Token=abc123"));
    }

    @Test
    public void resolveFallback_injectsWhenUriHasNoToken() {
        String uri = "http://h/library/parts/9/0/file.mkv";
        String resolved = Play.resolveFallbackPlaybackUri(uri, "tok");
        assertEquals("http://h/library/parts/9/0/file.mkv?X-Plex-Token=tok", resolved);
    }

    @Test
    public void skipOverlayWindow_hidesBeforeEndMargin() {
        assertTrue(Play.isWithinSkipOverlayWindow(1000, 1000, 10000));
        assertTrue(Play.isWithinSkipOverlayWindow(8499, 1000, 10000));
        assertFalse(Play.isWithinSkipOverlayWindow(8500, 1000, 10000)); // end - 1500
        assertFalse(Play.isWithinSkipOverlayWindow(999, 1000, 10000));
    }

    @Test
    public void zidooPlaybackActive_playingAndPaused() {
        assertTrue(Play.isZidooPlaybackActive(1, null, "/a.mkv", true));
        assertTrue(Play.isZidooPlaybackActive(2, null, "/a.mkv", true));
        assertFalse(Play.isZidooPlaybackActive(0, null, "/a.mkv", true));
        assertTrue(Play.isZidooPlaybackActive(null, "playing", "/a.mkv", true));
        assertTrue(Play.isZidooPlaybackActive(null, "paused", "/a.mkv", true));
        assertFalse(Play.isZidooPlaybackActive(null, "stop", "/a.mkv", true));
        assertTrue(Play.isZidooPlaybackActive(null, null, "/a.mkv", true));
        assertFalse(Play.isZidooPlaybackActive(null, null, "", true));
    }
}
