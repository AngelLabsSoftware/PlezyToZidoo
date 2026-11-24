package com.plezyplextozidoo;

import org.junit.Test;

import static org.junit.Assert.*;

public class ScreensaverRendererTest {

    @Test
    public void kenBurnsEndScale_isAboutFivePercent() {
        // ~2× the prior 2.5% zoom; still subtle/cinematic.
        assertEquals(1.05f, ScreensaverRenderer.KEN_BURNS_END_SCALE, 0.001f);
        assertTrue(ScreensaverRenderer.KEN_BURNS_END_SCALE >= 1.04f);
        assertTrue(ScreensaverRenderer.KEN_BURNS_END_SCALE <= 1.06f);
    }

    @Test
    public void logoDefaults_areQuarterOfPriorSize() {
        // Prior layout: 120dp height, 720dp max width → ~25%.
        assertEquals(30f, ScreensaverRenderer.LOGO_HEIGHT_DP, 0.01f);
        assertEquals(180f, ScreensaverRenderer.LOGO_MAX_WIDTH_DP, 0.01f);
    }

    @Test
    public void normalizeLogoSize_defaultsToSmall() {
        assertEquals(ScreensaverRenderer.LOGO_SIZE_SMALL,
                ScreensaverRenderer.normalizeLogoSize(null));
        assertEquals(ScreensaverRenderer.LOGO_SIZE_SMALL,
                ScreensaverRenderer.normalizeLogoSize(""));
        assertEquals(ScreensaverRenderer.LOGO_SIZE_SMALL,
                ScreensaverRenderer.normalizeLogoSize("small"));
        assertEquals(ScreensaverRenderer.LOGO_SIZE_ORIGINAL,
                ScreensaverRenderer.normalizeLogoSize("original"));
    }

    @Test
    public void fittedLogoSize_neverUpscalesPastNative() {
        int[] size = ScreensaverRenderer.fittedLogoSize(40, 20, 180, 30);
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
    public void resolveLogoDisplaySize_smallFitsIntoMaxBox() {
        int[] size = ScreensaverRenderer.resolveLogoDisplaySize(400, 100, 180, 30,
                ScreensaverRenderer.LOGO_SIZE_SMALL);
        assertEquals(120, size[0]);
        assertEquals(30, size[1]);
    }

    @Test
    public void resolveLogoDisplaySize_originalUsesNativePixels() {
        int[] size = ScreensaverRenderer.resolveLogoDisplaySize(400, 100, 1920, 1080,
                ScreensaverRenderer.LOGO_SIZE_ORIGINAL);
        assertEquals(400, size[0]);
        assertEquals(100, size[1]);
    }

    @Test
    public void resolveLogoDisplaySize_originalShrinksOnlyIfLargerThanScreen() {
        int[] size = ScreensaverRenderer.resolveLogoDisplaySize(4000, 1000, 1920, 1080,
                ScreensaverRenderer.LOGO_SIZE_ORIGINAL);
        assertEquals(1920, size[0]);
        assertEquals(480, size[1]);
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
