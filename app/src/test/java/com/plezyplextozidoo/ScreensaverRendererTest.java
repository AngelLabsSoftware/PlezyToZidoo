package com.plezyplextozidoo;

import org.junit.Test;

import static org.junit.Assert.*;

public class ScreensaverRendererTest {

    @Test
    public void kenBurnsEndScale_isSubtleTwoToThreePercent() {
        assertTrue(ScreensaverRenderer.KEN_BURNS_END_SCALE >= 1.02f);
        assertTrue(ScreensaverRenderer.KEN_BURNS_END_SCALE <= 1.03f);
    }

    @Test
    public void logoDefaults_areQuarterOfPriorSize() {
        // Prior layout: 120dp height, 720dp max width → ~25%.
        assertEquals(30f, ScreensaverRenderer.LOGO_HEIGHT_DP, 0.01f);
        assertEquals(180f, ScreensaverRenderer.LOGO_MAX_WIDTH_DP, 0.01f);
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
}
