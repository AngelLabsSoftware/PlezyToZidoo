package com.plezyplextozidoo;

import org.junit.Test;

import static org.junit.Assert.*;

public class ReverseSubstitutionTest {

    @Test
    public void reverseSubstitution_mapsSmbBackToServerPath() {
        String[][] rules = new String[][]{
                {"/media/", "smb://192.168.1.10/media/"}
        };
        String reversed = PlexApi.reverseSubstitution(
                "smb://user:pass@192.168.1.10/media/tv/show.mkv", rules);
        assertEquals("/media/tv/show.mkv", reversed);
    }

    @Test
    public void reverseSubstitution_handlesMountPath() {
        String[][] rules = new String[][]{
                {"/media/", "smb://nas/media/"}
        };
        String reversed = PlexApi.reverseSubstitution(
                "/data/system/smb/nas#media/tv/show.mkv", rules);
        assertEquals("/media/tv/show.mkv", reversed);
    }

    @Test
    public void isZidooFileChange_ignoresUriVsMount() {
        assertFalse(PlexApi.isZidooFileChange(
                "smb://nas/media/a.mkv",
                "/data/system/smb/nas#media/a.mkv"));
        assertTrue(PlexApi.isZidooFileChange(
                "smb://nas/media/a.mkv",
                "smb://nas/media/b.mkv"));
    }

    @Test
    public void extractSearchName_stripsExtension() {
        assertEquals("S01E01", PlexApi.extractSearchName("/media/tv/S01E01.mkv"));
        assertNull(PlexApi.extractSearchName(""));
    }
}
