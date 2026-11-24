package com.plezyplextozidoo;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Regression for 2026.11.9 position-based seek rearm (Adam):
 * skip activates after settle inside a marker; visit cancel only; no permanent seek-disarm.
 */
public class SeekRearmSkipTest {

    @Test
    public void forwardSeekIntoMarker_detectedThenSettles() {
        // Chapter-like jump into credits window, then normal 1× progress.
        assertTrue(Play.isLikelyUserSeekOrTrickPlay(2_000_000, 3_050_000));
        assertTrue(Play.isSeekSettledDelta(3_050_000, 3_051_000));
        List<PlexApi.CreditSegment> segments = segments(seg(3_000_000, 3_200_000, false));
        assertEquals(0, Play.findEligibleCreditSegmentIndex(3_051_000, segments, null));
    }

    @Test
    public void backwardSeekIntoMarker_detectedThenSettles() {
        assertTrue(Play.isLikelyUserSeekOrTrickPlay(3_500_000, 3_050_000));
        assertTrue(Play.isSeekSettledDelta(3_050_000, 3_051_200));
        List<PlexApi.CreditSegment> segments = segments(seg(3_000_000, 3_200_000, false));
        assertEquals(0, Play.findEligibleCreditSegmentIndex(3_051_200, segments, null));
    }

    @Test
    public void chapterJumpPastMarker_noSkip() {
        assertTrue(Play.isLikelyUserSeekOrTrickPlay(2_000_000, 3_500_000));
        List<PlexApi.CreditSegment> segments = segments(seg(3_000_000, 3_200_000, false));
        assertEquals(-1, Play.findEligibleCreditSegmentIndex(3_500_000, segments, null));
        assertEquals(-1, Play.findInstantCreditSegmentIndex(3_500_000, segments, null));
    }

    @Test
    public void resumeAlreadyInsideIntro_eligible() {
        assertTrue(Play.isIntroSkipEligible(30_000, 10_000, 90_000, false, false));
        assertFalse(Play.isIntroSkipEligible(30_000, 10_000, 90_000, true, false)); // visit dismissed
        assertFalse(Play.isIntroSkipEligible(30_000, 10_000, 90_000, false, true)); // visit skipped
        assertFalse(Play.isIntroSkipEligible(5_000, 10_000, 90_000, false, false)); // before start
    }

    @Test
    public void leaveAndReturn_clearsVisitSuppression() {
        Set<Integer> dismissed = new HashSet<>();
        Set<Integer> skipped = new HashSet<>();
        dismissed.add(0);
        skipped.add(0);
        boolean[] introFlags = new boolean[]{true, true};

        // Leave credits segment 0 → clear that visit's suppressions.
        Play.clearVisitSuppressionOnLeave(false, -1, false, 0, introFlags, dismissed, skipped);
        assertTrue(dismissed.isEmpty());
        assertTrue(skipped.isEmpty());

        // Leave intro → clear intro visit flags.
        introFlags[0] = true;
        introFlags[1] = true;
        Play.clearVisitSuppressionOnLeave(false, -1, true, -1, introFlags, dismissed, skipped);
        assertFalse(introFlags[0]);
        assertFalse(introFlags[1]);

        // Re-entry: no suppression → eligible again.
        List<PlexApi.CreditSegment> segments = segments(seg(3_000_000, 3_200_000, false));
        assertEquals(0, Play.findEligibleCreditSegmentIndex(3_050_000, segments, dismissed));
        assertTrue(Play.isIntroSkipEligible(30_000, 10_000, 90_000, introFlags[0], introFlags[1]));
    }

    @Test
    public void cancelVisit_otherCreditSegmentStillEligible() {
        List<PlexApi.CreditSegment> segments = segments(
                seg(3_000_000, 3_200_000, false),
                seg(3_400_000, 3_600_000, true));
        Set<Integer> visitDismissed = new HashSet<>();
        visitDismissed.add(0);
        assertEquals(-1, Play.findEligibleCreditSegmentIndex(3_050_000, segments, visitDismissed));
        assertEquals(1, Play.findEligibleCreditSegmentIndex(3_450_000, segments, visitDismissed));
        assertEquals(1, Play.findInstantCreditSegmentIndex(3_450_000, segments, visitDismissed));
    }

    @Test
    public void appSkipSeek_notResolvedUntilNearTarget() {
        long setAt = 1_000_000L;
        assertFalse(Play.isAppSkipSeekResolved(3_200_000, setAt, 3_050_000, setAt + 500));
        assertTrue(Play.isAppSkipSeekResolved(3_200_000, setAt, 3_199_000, setAt + 500));
        assertTrue(Play.isAppSkipSeekResolved(3_200_000, setAt, 3_200_000, setAt + 500));
        // Timeout clears pending even if still mid-flight.
        assertTrue(Play.isAppSkipSeekResolved(3_200_000, setAt, 3_050_000,
                setAt + Play.APP_SKIP_PENDING_TIMEOUT_MS + 1));
        assertTrue(Play.isAppSkipSeekResolved(-1, 0, 0, 0));
    }

    @Test
    public void normalPlaybackDelta_notTrickPlay() {
        assertFalse(Play.isLikelyUserSeekOrTrickPlay(1_000_000, 1_001_000));
        assertFalse(Play.isLikelyUserSeekOrTrickPlay(1_000_000, 1_000_000)); // paused
    }

    @Test
    public void allModesShareSameEligibilityHelpers() {
        // Manual/Countdown use overlay window; Instant uses [start,end). Both respect visit suppression.
        List<PlexApi.CreditSegment> segments = segments(seg(100_000, 200_000, false));
        Set<Integer> suppressed = new HashSet<>();
        assertEquals(0, Play.findEligibleCreditSegmentIndex(150_000, segments, suppressed));
        assertEquals(0, Play.findInstantCreditSegmentIndex(150_000, segments, suppressed));
        suppressed.add(0);
        assertEquals(-1, Play.findEligibleCreditSegmentIndex(150_000, segments, suppressed));
        assertEquals(-1, Play.findInstantCreditSegmentIndex(150_000, segments, suppressed));
    }

    private static PlexApi.CreditSegment seg(long start, long end, boolean isFinal) {
        return new PlexApi.CreditSegment(start, end, isFinal);
    }

    private static List<PlexApi.CreditSegment> segments(PlexApi.CreditSegment... segs) {
        List<PlexApi.CreditSegment> list = new ArrayList<>();
        for (PlexApi.CreditSegment s : segs) list.add(s);
        return list;
    }
}
