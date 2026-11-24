package com.plezyplextozidoo;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Regression coverage for Adam's credits segment-skip policy:
 * skip current segment only; Up Next only at true media end.
 */
public class CreditsSegmentSkipTest {

    @Test
    public void parseMarkers_retainsSingleCreditsSegment() {
        String json = metaWithMarkers(
                "{\"type\":\"credits\",\"startTimeOffset\":3300000,\"endTimeOffset\":3600000}");
        PlexApi.MarkerResult m = PlexApi.parseMarkersFromBody(json);
        assertEquals(3300000, m.creditStartMs);
        assertEquals(3600000, m.creditEndMs);
        assertEquals(1, m.creditSegments.size());
        assertFalse(m.creditSegments.get(0).isFinal);
    }

    @Test
    public void parseMarkers_retainsAllCreditsWhenFinalPresent() {
        String json = metaWithMarkers(
                "{\"type\":\"credits\",\"startTimeOffset\":3000000,\"endTimeOffset\":3200000},"
                        + "{\"type\":\"credits\",\"startTimeOffset\":3400000,\"endTimeOffset\":3600000,"
                        + "\"final\":true}");
        PlexApi.MarkerResult m = PlexApi.parseMarkersFromBody(json);
        assertEquals(2, m.creditSegments.size());
        assertEquals(3000000, m.creditSegments.get(0).startMs);
        assertFalse(m.creditSegments.get(0).isFinal);
        assertEquals(3400000, m.creditSegments.get(1).startMs);
        assertTrue(m.creditSegments.get(1).isFinal);
        // Earliest still exposed for legacy fields; earlier not discarded.
        assertEquals(3000000, m.creditStartMs);
        assertEquals(3200000, m.creditEndMs);
    }

    @Test
    public void parseMarkers_finalAsStringOrInt() {
        String json = metaWithMarkers(
                "{\"type\":\"outro\",\"startTimeOffset\":100,\"endTimeOffset\":200,\"final\":\"1\"},"
                        + "{\"type\":\"credits\",\"startTimeOffset\":300,\"endTimeOffset\":400,"
                        + "\"final\":1}");
        PlexApi.MarkerResult m = PlexApi.parseMarkersFromBody(json);
        assertEquals(2, m.creditSegments.size());
        assertTrue(m.creditSegments.get(0).isFinal);
        assertTrue(m.creditSegments.get(1).isFinal);
    }

    @Test
    public void parseMarkers_skipsInvalidEnds() {
        String json = metaWithMarkers(
                "{\"type\":\"credits\",\"startTimeOffset\":1000,\"endTimeOffset\":1000},"
                        + "{\"type\":\"credits\",\"startTimeOffset\":2000,\"endTimeOffset\":1500},"
                        + "{\"type\":\"credits\",\"startTimeOffset\":-1,\"endTimeOffset\":5000},"
                        + "{\"type\":\"credits\",\"startTimeOffset\":5000,\"endTimeOffset\":6000}");
        PlexApi.MarkerResult m = PlexApi.parseMarkersFromBody(json);
        assertEquals(1, m.creditSegments.size());
        assertEquals(5000, m.creditSegments.get(0).startMs);
        assertEquals(6000, m.creditSegments.get(0).endMs);
    }

    @Test
    public void parseMetadata_includesCreditSegments() throws Exception {
        String json = "{"
                + "\"MediaContainer\":{\"Metadata\":[{"
                + "\"ratingKey\":\"10\",\"title\":\"Ep\",\"type\":\"episode\",\"duration\":3600000,"
                + "\"Marker\":["
                + "{\"type\":\"intro\",\"startTimeOffset\":0,\"endTimeOffset\":60000},"
                + "{\"type\":\"credits\",\"startTimeOffset\":3000000,\"endTimeOffset\":3200000},"
                + "{\"type\":\"credits\",\"startTimeOffset\":3500000,\"endTimeOffset\":3600000,\"final\":true}"
                + "]"
                + "}]}}";
        PlexApi.MetadataResult result = PlexApi.parseMetadataResponse(json, "10");
        assertEquals(2, result.creditSegments.size());
        assertEquals(60000, result.introEndMs);
        assertTrue(result.creditSegments.get(1).isFinal);
    }

    @Test
    public void isCreditsSeekAtMediaEnd_segmentBeforeDurationContinues() {
        assertFalse(Play.isCreditsSeekAtMediaEnd(3_200_000, 3_600_000));
        assertTrue(Play.isCreditsSeekAtMediaEnd(3_600_000, 3_600_000));
        assertTrue(Play.isCreditsSeekAtMediaEnd(3_599_000, 3_600_000)); // within 2s
        assertFalse(Play.isCreditsSeekAtMediaEnd(3_597_000, 3_600_000)); // >2s remaining
        assertFalse(Play.isCreditsSeekAtMediaEnd(3_600_000, 0)); // ambiguous duration
        assertFalse(Play.isCreditsSeekAtMediaEnd(-1, 3_600_000));
    }

    @Test
    public void findEligible_selectsCurrentSegmentOnly() {
        List<PlexApi.CreditSegment> segments = segments(
                seg(3_000_000, 3_200_000, false),
                seg(3_400_000, 3_600_000, true));
        Set<Integer> inactive = new HashSet<>();
        assertEquals(0, Play.findEligibleCreditSegmentIndex(3_050_000, segments, inactive));
        assertEquals(1, Play.findEligibleCreditSegmentIndex(3_450_000, segments, inactive));
        assertEquals(-1, Play.findEligibleCreditSegmentIndex(3_250_000, segments, inactive));
    }

    @Test
    public void dismissCurrent_laterSegmentStillEligible() {
        List<PlexApi.CreditSegment> segments = segments(
                seg(3_000_000, 3_200_000, false),
                seg(3_400_000, 3_600_000, true));
        Set<Integer> inactive = new HashSet<>();
        inactive.add(0); // Back dismissed first segment
        assertEquals(-1, Play.findEligibleCreditSegmentIndex(3_050_000, segments, inactive));
        assertEquals(1, Play.findEligibleCreditSegmentIndex(3_450_000, segments, inactive));
        assertEquals(1, Play.findInstantCreditSegmentIndex(3_450_000, segments, inactive));
    }

    @Test
    public void instantFindsSegmentAtStart_notPastEnd() {
        List<PlexApi.CreditSegment> segments = segments(seg(3_000_000, 3_200_000, false));
        Set<Integer> inactive = new HashSet<>();
        assertEquals(0, Play.findInstantCreditSegmentIndex(3_000_000, segments, inactive));
        assertEquals(-1, Play.findInstantCreditSegmentIndex(3_200_000, segments, inactive));
        assertEquals(-1, Play.findInstantCreditSegmentIndex(2_900_000, segments, inactive));
    }

    @Test
    public void backwardSeekDisarmsCurrent_notLater() {
        List<PlexApi.CreditSegment> segments = segments(
                seg(3_000_000, 3_200_000, false),
                seg(3_400_000, 3_600_000, true));
        Set<Integer> inactive = new HashSet<>();
        Play.disarmCreditSegmentsOnBackwardSeek(3_100_000, 2_500_000, segments, inactive);
        assertTrue(inactive.contains(0));
        assertFalse(inactive.contains(1));
        assertEquals(1, Play.findEligibleCreditSegmentIndex(3_450_000, segments, inactive));
    }

    @Test
    public void formatCreditSegmentsForLog_safe() {
        String log = PlexApi.formatCreditSegmentsForLog(segments(seg(1, 2, true)));
        assertTrue(log.contains("final=true"));
        assertTrue(log.contains("start=1"));
        assertEquals("[]", PlexApi.formatCreditSegmentsForLog(null));
    }

    @Test
    public void isValidCreditSegment() {
        assertTrue(Play.isValidCreditSegment(0, 100));
        assertFalse(Play.isValidCreditSegment(100, 100));
        assertFalse(Play.isValidCreditSegment(-1, 100));
    }

    private static PlexApi.CreditSegment seg(long start, long end, boolean isFinal) {
        return new PlexApi.CreditSegment(start, end, isFinal);
    }

    private static List<PlexApi.CreditSegment> segments(PlexApi.CreditSegment... segs) {
        List<PlexApi.CreditSegment> list = new ArrayList<>();
        for (PlexApi.CreditSegment s : segs) list.add(s);
        return list;
    }

    private static String metaWithMarkers(String markerJson) {
        return "{\"MediaContainer\":{\"Metadata\":[{"
                + "\"ratingKey\":\"1\",\"title\":\"T\",\"type\":\"movie\",\"duration\":3600000,"
                + "\"Marker\":[" + markerJson + "]"
                + "}]}}";
    }
}
