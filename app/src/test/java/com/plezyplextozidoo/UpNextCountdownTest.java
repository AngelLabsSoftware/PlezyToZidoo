package com.plezyplextozidoo;

import org.junit.Test;

import static org.junit.Assert.*;

public class UpNextCountdownTest {

    @Test
    public void normalize_defaultsToSeven() {
        assertEquals(7, UpNextActivity.normalizeCountdownSec(null));
        assertEquals(7, UpNextActivity.normalizeCountdownSec(""));
        assertEquals(7, UpNextActivity.normalizeCountdownSec("4"));
        assertEquals(7, UpNextActivity.normalizeCountdownSec("abc"));
    }

    @Test
    public void normalize_allowsConfiguredValues() {
        assertEquals(3, UpNextActivity.normalizeCountdownSec("3"));
        assertEquals(5, UpNextActivity.normalizeCountdownSec("5"));
        assertEquals(7, UpNextActivity.normalizeCountdownSec("7"));
        assertEquals(10, UpNextActivity.normalizeCountdownSec("10"));
    }

    @Test
    public void intervalNormalize_mapsLegacyToTwenty() {
        assertEquals("20", SettingsActivity.normalizeInterval("10"));
        assertEquals("20", SettingsActivity.normalizeInterval("15"));
        assertEquals("45", SettingsActivity.normalizeInterval("45"));
        assertEquals("20", SettingsActivity.normalizeInterval("99"));
    }
}
