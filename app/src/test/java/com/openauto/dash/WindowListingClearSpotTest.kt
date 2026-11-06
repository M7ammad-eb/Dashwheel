package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WindowListingClearSpotTest {

    private val screen = ScreenRect(0, 0, 1280, 720)
    // The page indicator at the bottom centre, 120 px wide.
    private val indicator = ScreenRect(580, 560, 700, 680)

    @Test
    fun staysPutWithNoWindowOverIt() {
        assertEquals(640, WindowListing.clearCentreX(indicator, screen, listOf(ScreenRect(0, 0, 500, 700))))
    }

    @Test
    fun movesBesideAMapsWindowOnTheLeftHalf() {
        // omek07's photo: Maps docked over the left half, past the centre.
        assertEquals(985, WindowListing.clearCentreX(indicator, screen, listOf(ScreenRect(0, 60, 690, 700))))
    }

    @Test
    fun takesTheWidestGapBetweenTwoWindows() {
        val windows = listOf(ScreenRect(1000, 0, 1280, 720), ScreenRect(0, 0, 600, 720))
        assertEquals(800,WindowListing.clearCentreX(indicator, screen, windows))
    }

    @Test
    fun aWindowAboveItsHeightDoesNotNarrowTheGap() {
        val windows = listOf(ScreenRect(0, 100, 1280, 300), ScreenRect(0, 400, 700, 720))
        assertEquals(990, WindowListing.clearCentreX(indicator, screen, windows))
    }

    @Test
    fun nowhereToGoLeavesItWhereItIs() {
        assertNull(WindowListing.clearCentreX(indicator, screen, listOf(ScreenRect(0, 0, 1220, 720))))
    }
}
