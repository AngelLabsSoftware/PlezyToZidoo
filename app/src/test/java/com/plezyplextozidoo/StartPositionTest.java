package com.plezyplextozidoo;

import org.junit.Test;

import static org.junit.Assert.*;

public class StartPositionTest {

    @Test
    public void intentPositionWinsIncludingZero() {
        assertEquals(0, Play.resolveStartPositionMs(true, 0, 60000));
        assertEquals(12345, Play.resolveStartPositionMs(true, 12345, 60000));
    }

    @Test
    public void serverOffsetUsedWhenIntentAbsent() {
        assertEquals(60000, Play.resolveStartPositionMs(false, 0, 60000));
        assertEquals(0, Play.resolveStartPositionMs(false, 0, 0));
    }

    @Test
    public void resultPositionRules() {
        assertEquals(0, Play.resolveResultPositionMs("1", "2", 5000, 10000, 1000));
        assertEquals(0, Play.resolveResultPositionMs("1", "1", 9500, 10000, 1000));
        assertEquals(5000, Play.resolveResultPositionMs("1", "1", 5000, 10000, 1000));
        assertEquals(1000, Play.resolveResultPositionMs("1", "1", 0, 10000, 1000));
    }
}
