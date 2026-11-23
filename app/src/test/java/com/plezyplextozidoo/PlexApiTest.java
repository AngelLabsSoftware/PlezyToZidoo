package com.plezyplextozidoo;

import org.junit.Test;

import static org.junit.Assert.*;

public class PlexApiTest {

    @Test
    public void isWatched_requiresNinetyPercent() {
        assertFalse(PlexApi.isWatched(0, 100000));
        assertFalse(PlexApi.isWatched(89999, 100000));
        assertTrue(PlexApi.isWatched(90001, 100000));
        assertFalse(PlexApi.isWatched(100000, 0));
    }

    @Test
    public void parseMarkers_introAndCredits() {
        String json = "{"
                + "\"MediaContainer\":{\"Metadata\":[{"
                + "\"ratingKey\":\"10\","
                + "\"title\":\"Ep\","
                + "\"type\":\"episode\","
                + "\"duration\":3600000,"
                + "\"viewOffset\":12000,"
                + "\"grandparentTitle\":\"Show\","
                + "\"grandparentRatingKey\":\"1\","
                + "\"parentRatingKey\":\"2\","
                + "\"parentIndex\":1,"
                + "\"index\":3,"
                + "\"Media\":[{\"Part\":[{\"id\":99,\"file\":\"/media/show/S01E03.mkv\"}]}],"
                + "\"Marker\":["
                + "{\"type\":\"intro\",\"startTimeOffset\":5000,\"endTimeOffset\":90000},"
                + "{\"type\":\"credits\",\"startTimeOffset\":3300000,\"endTimeOffset\":3600000}"
                + "]"
                + "}]}}";

        try {
            PlexApi.MetadataResult result = PlexApi.parseMetadataResponse(json, "10");
            assertEquals("10", result.ratingKey);
            assertEquals("/media/show/S01E03.mkv", result.filePath);
            assertEquals(12000, result.viewOffsetMs);
            assertEquals(3600000, result.durationMs);
            assertTrue(result.isEpisode);
            assertEquals(5000, result.introStartMs);
            assertEquals(90000, result.introEndMs);
            assertEquals(3300000, result.creditStartMs);
            assertEquals(3600000, result.creditEndMs);
            assertEquals("1", result.seriesRatingKey);
            assertEquals("2", result.seasonRatingKey);
            assertEquals(3, result.episodeNumber);
        } catch (Exception e) {
            fail(e.getMessage());
        }
    }

    @Test
    public void findRatingKeyForPartId_matchesNestedPart() {
        String json = "{\"MediaContainer\":{\"Metadata\":["
                + "{\"ratingKey\":\"55\",\"Media\":[{\"Part\":[{\"id\":123,\"file\":\"/a.mkv\"}]}]},"
                + "{\"ratingKey\":\"66\",\"Media\":[{\"Part\":[{\"id\":999,\"file\":\"/b.mkv\"}]}]}"
                + "]}}";
        assertEquals("55", PlexApi.findRatingKeyForPartId(json, "123"));
        assertEquals("66", PlexApi.findRatingKeyForPartId(json, "999"));
        assertNull(PlexApi.findRatingKeyForPartId(json, "000"));
    }

    @Test
    public void findRatingKeyByFilePath_matches() {
        String json = "{\"MediaContainer\":{\"Metadata\":["
                + "{\"ratingKey\":\"7\",\"Media\":[{\"Part\":[{\"file\":\"/media/movie.mkv\"}]}]}"
                + "]}}";
        assertEquals("7", PlexApi.findRatingKeyByFilePath(json, "/media/movie.mkv"));
        assertNull(PlexApi.findRatingKeyByFilePath(json, "/other.mkv"));
    }

    @Test
    public void baseUrl_stripsTrailingSlash() {
        assertEquals("http://host:32400", PlexApi.baseUrl("http://host:32400/"));
        assertEquals("http://host:32400", PlexApi.baseUrl(" http://host:32400 "));
        assertEquals("", PlexApi.baseUrl(null));
    }

    @Test
    public void ticksHelpers_areIdentityForPlexMs() {
        assertEquals(1500, PlexApi.msToTicks(1500));
        assertEquals(1500, PlexApi.ticksToMs(1500));
    }

    @Test
    public void meetsGenuine4k_requiresFullUhd() {
        assertFalse(PlexApi.meetsGenuine4k(1920, 1080));
        assertFalse(PlexApi.meetsGenuine4k(3840, 1080));
        assertFalse(PlexApi.meetsGenuine4k(1920, 2160));
        assertTrue(PlexApi.meetsGenuine4k(3840, 2160));
        assertTrue(PlexApi.meetsGenuine4k(4096, 2160));
    }

    @Test
    public void parseScreensaverItems_moviesShowsWithArtAndClearLogo() {
        String json = "{\"MediaContainer\":{\"Metadata\":["
                + "{\"ratingKey\":\"1\",\"type\":\"movie\",\"title\":\"Film\","
                + "\"art\":\"/library/metadata/1/art/1\",\"originallyAvailableAt\":\"1999-03-31\","
                + "\"Image\":[{\"type\":\"clearLogo\",\"url\":\"/library/metadata/1/clearLogo\"}]},"
                + "{\"ratingKey\":\"2\",\"type\":\"show\",\"title\":\"Series\","
                + "\"art\":\"/library/metadata/2/art/2\",\"year\":2005},"
                + "{\"ratingKey\":\"3\",\"type\":\"episode\",\"title\":\"Ep\",\"art\":\"/library/metadata/3/art/3\"},"
                + "{\"ratingKey\":\"4\",\"type\":\"movie\",\"title\":\"NoArt\"}"
                + "]}}";
        java.util.List<PlexApi.ScreensaverItem> items = PlexApi.parseScreensaverItems(json);
        assertEquals(2, items.size());
        assertEquals("1", items.get(0).ratingKey);
        assertTrue(items.get(0).hasTransparentLogo());
        assertEquals("1999-03-31", items.get(0).originallyAvailableAt);
        assertEquals("2", items.get(1).ratingKey);
        assertFalse(items.get(1).hasTransparentLogo());
        assertEquals("2005", items.get(1).originallyAvailableAt);
    }

    @Test
    public void formatReleaseLabel_prefersYear() {
        assertEquals("1999", PlexApi.formatReleaseLabel("1999-03-31"));
        assertEquals("2005", PlexApi.formatReleaseLabel("2005"));
        assertEquals("", PlexApi.formatReleaseLabel(""));
        assertEquals("", PlexApi.formatReleaseLabel(null));
    }
}
