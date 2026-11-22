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
}
