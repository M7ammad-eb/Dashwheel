package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Test

/** The rev scale the skins and bars draw on: the car's red line decides where it turns red and how far it goes. */
class RevScaleTest {

    @Test
    fun aDieselKeepsTheUsualTopAndTurnsRedAtItsRedLine() {
        val scale = revScale(4_500)
        assertEquals(SKIN_RPM_MAX, scale.top, 0f)
        assertEquals(4_500f / 7_000f, scale.redFrom, 1e-5f)
    }

    @Test
    fun aPetrolRedLineNearTheTopGetsRoom() {
        val scale = revScale(7_500)
        assertEquals(9_000f, scale.top, 0f)
        assertEquals(7_500f / 9_000f, scale.redFrom, 1e-5f)
        // Half a thousand above the top of the usual scale still fits it.
        assertEquals(SKIN_RPM_MAX, revScale(6_300).top, 0f)
    }

    @Test
    fun noRedLineOrAZeroMeansADiesels() {
        assertEquals(revScale(null), revScale(0))
        assertEquals(revScale(null), revScale(DEFAULT_REDLINE_RPM))
    }

    @Test
    fun theFractionIsClampedAndNullSafe() {
        val scale = revScale(null)
        assertEquals(0f, scale.fraction(null), 0f)
        assertEquals(1f, scale.fraction(9_000), 0f)
        assertEquals(0.5f, scale.fraction(3_500), 1e-6f)
    }
}
