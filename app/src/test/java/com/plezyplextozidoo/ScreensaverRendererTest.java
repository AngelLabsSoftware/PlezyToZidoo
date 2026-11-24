package com.plezyplextozidoo;

import org.junit.Test;

import static org.junit.Assert.*;

public class ScreensaverRendererTest {

    @Test
    public void kenBurnsEndScale_isAboutFivePercent() {
        assertEquals(1.05f, ScreensaverRenderer.KEN_BURNS_END_SCALE, 0.001f);
        assertTrue(ScreensaverRenderer.KEN_BURNS_END_SCALE >= 1.04f);
        assertTrue(ScreensaverRenderer.KEN_BURNS_END_SCALE <= 1.06f);
    }

    @Test
    public void logoWidthFractions_matchAdamSpec() {
        assertEquals(0.25f, ScreensaverRenderer.LOGO_WIDTH_FRAC_SMALL, 0.001f);
        assertEquals(0.50f, ScreensaverRenderer.LOGO_WIDTH_FRAC_MEDIUM, 0.001f);
        assertEquals(0.80f, ScreensaverRenderer.LOGO_WIDTH_FRAC_LARGE, 0.001f);
        assertEquals(0.25f, ScreensaverRenderer.logoWidthFraction("small"), 0.001f);
        assertEquals(0.50f, ScreensaverRenderer.logoWidthFraction("medium"), 0.001f);
        assertEquals(0.80f, ScreensaverRenderer.logoWidthFraction("large"), 0.001f);
    }

    @Test
    public void normalizeLogoSize_defaultsToMedium_andMapsOriginalToLarge() {
        assertEquals(ScreensaverRenderer.LOGO_SIZE_MEDIUM,
                ScreensaverRenderer.normalizeLogoSize(null));
        assertEquals(ScreensaverRenderer.LOGO_SIZE_MEDIUM,
                ScreensaverRenderer.normalizeLogoSize(""));
        assertEquals(ScreensaverRenderer.LOGO_SIZE_MEDIUM,
                ScreensaverRenderer.normalizeLogoSize("medium"));
        assertEquals(ScreensaverRenderer.LOGO_SIZE_SMALL,
                ScreensaverRenderer.normalizeLogoSize("small"));
        assertEquals(ScreensaverRenderer.LOGO_SIZE_LARGE,
                ScreensaverRenderer.normalizeLogoSize("large"));
        assertEquals(ScreensaverRenderer.LOGO_SIZE_LARGE,
                ScreensaverRenderer.normalizeLogoSize("original"));
    }

    @Test
    public void largeIsCentered_smallMediumLeft() {
        assertTrue(ScreensaverRenderer.isLogoCentered("large"));
        assertTrue(ScreensaverRenderer.isLogoCentered("original"));
        assertFalse(ScreensaverRenderer.isLogoCentered("small"));
        assertFalse(ScreensaverRenderer.isLogoCentered("medium"));
    }

    @Test
    public void fittedLogoSize_neverUpscalesPastNative() {
        int[] size = ScreensaverRenderer.fittedLogoSize(40, 20, 960, 200);
        assertEquals(40, size[0]);
        assertEquals(20, size[1]);
    }

    @Test
    public void fittedLogoSize_downscalesToMaxPreservingAspect() {
        int[] size = ScreensaverRenderer.fittedLogoSize(400, 100, 180, 30);
        assertEquals(120, size[0]);
        assertEquals(30, size[1]);
    }

    @Test
    public void fittedLogoSize_handlesInvalid() {
        int[] size = ScreensaverRenderer.fittedLogoSize(0, 10, 100, 50);
        assertEquals(0, size[0]);
        assertEquals(0, size[1]);
    }

    @Test
    public void resolveLogoDisplaySize_fitsIntoMaxBox() {
        int[] size = ScreensaverRenderer.resolveLogoDisplaySize(400, 100, 180, 30,
                ScreensaverRenderer.LOGO_SIZE_SMALL);
        assertEquals(120, size[0]);
        assertEquals(30, size[1]);
    }

    @Test
    public void candidateEligible_requiresLogoUnlessTextFallback() {
        PlexApi.ScreensaverItem withLogo = new PlexApi.ScreensaverItem(
                "1", "Film", "/art/1", "/logo/1", "1999", "movie");
        PlexApi.ScreensaverItem noLogo = new PlexApi.ScreensaverItem(
                "2", "Series", "/art/2", "", "2005", "show");
        PlexApi.ScreensaverItem noArt = new PlexApi.ScreensaverItem(
                "3", "Bare", "", "/logo/3", "2001", "movie");
        PlexApi.ScreensaverItem blankTitle = new PlexApi.ScreensaverItem(
                "4", "  ", "/art/4", "", "2001", "movie");

        assertTrue(ScreensaverRenderer.isScreensaverCandidateEligible(withLogo, false));
        assertFalse(ScreensaverRenderer.isScreensaverCandidateEligible(noLogo, false));
        assertTrue(ScreensaverRenderer.isScreensaverCandidateEligible(noLogo, true));
        assertFalse(ScreensaverRenderer.isScreensaverCandidateEligible(noArt, true));
        assertFalse(ScreensaverRenderer.isScreensaverCandidateEligible(blankTitle, true));
        assertFalse(ScreensaverRenderer.isScreensaverCandidateEligible(null, true));
    }
}
