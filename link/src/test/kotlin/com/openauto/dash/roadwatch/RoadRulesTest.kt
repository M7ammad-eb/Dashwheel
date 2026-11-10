package com.openauto.dash.roadwatch

import com.openauto.dash.link.LinkCodec
import com.openauto.dash.link.RoadScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoadRulesTest {
    private val closingFast = RoadScene(lines = 2, offset = 0f, leadM = 40f, closingMps = 20f, ttcS = 2f)

    @Test
    fun brakeWhenTheCarAheadComesUpFast() {
        val r = RoadRules()
        assertNull("one frame isn't enough", r.update(closingFast, 90f, false, 0))
        assertEquals(RoadWarning.COLLISION, r.update(closingFast, 90f, false, 100))
        // It stays up long enough to be seen, then goes.
        assertEquals(RoadWarning.COLLISION, r.update(RoadScene(lines = 2, offset = 0f), 90f, false, 1_500))
        assertNull(r.update(RoadScene(lines = 2, offset = 0f), 90f, false, 2_200))
    }

    @Test
    fun aFarCarSlowlyNearerIsNoAlarm() {
        val r = RoadRules()
        val slow = RoadScene(leadM = 60f, closingMps = 5f, ttcS = 12f)
        repeat(10) { assertNull(r.update(slow, 90f, false, it * 100L)) }
    }

    @Test
    fun inTownOnlyWhenItIsReallyClose() {
        val r = RoadRules()
        val town = RoadScene(leadM = 8f, closingMps = 4f, ttcS = 2f)
        repeat(3) { assertNull(r.update(town, 20f, false, it * 100L)) }
        val close = RoadScene(leadM = 5f, closingMps = 4f, ttcS = 1.2f)
        r.update(close, 20f, false, 300)
        assertEquals(RoadWarning.COLLISION, r.update(close, 20f, false, 400))
    }

    @Test
    fun closingFasterThanTheCarDrivesIsAJumpyBox() {
        val r = RoadRules()
        val phantom = RoadScene(leadM = 20f, closingMps = 15f, ttcS = 1.3f)
        repeat(5) { assertNull(r.update(phantom, 20f, false, it * 100L)) }
    }

    @Test
    fun aWheelOnTheLineIsToldOnce() {
        val r = RoadRules()
        var t = 0L
        fun at(offset: Float, indicating: Boolean = false) = r.update(RoadScene(offset = offset, lines = 2), 100f, indicating, t).also { t += 100 }
        repeat(5) { assertNull(at(0f)) }
        // Drifting right onto the line: told once it has been over it a few scenes running.
        listOf(0.05f, 0.10f, 0.15f, 0.20f, 0.24f, 0.26f).forEach { assertNull(at(it)) }
        assertEquals(RoadWarning.LANE_RIGHT, at(0.27f))
        assertEquals(RoadWarning.LANE_RIGHT, at(0.28f))
        // Back in the lane: it goes; along the line again later without coming back first: nothing new.
        assertNull(at(0.14f))
        listOf(0.18f, 0.22f, 0.26f, 0.27f, 0.28f).forEach { assertNull(at(it)) }
        // Back in the middle, then onto the left line: told.
        repeat(3) { at(0f) }
        listOf(-0.08f, -0.14f, -0.19f, -0.25f, -0.26f).forEach { assertNull(at(it)) }
        assertEquals(RoadWarning.LANE_LEFT, at(-0.27f))
    }

    @Test
    fun oneSceneOverTheLineIsAMisreadingNotADrift() {
        val r = RoadRules()
        var t = 0L
        fun at(offset: Float) = r.update(RoadScene(offset = offset, lines = 2), 100f, false, t).also { t += 100 }
        repeat(5) { assertNull(at(0f)) }
        // The far edge of the road taken for the lane's line, for a frame.
        assertNull(at(0.42f))
        assertNull(at(0f))
        assertNull(at(0.41f))
        repeat(5) { assertNull(at(-0.01f)) }
    }

    @Test
    fun anIndicatorMeansTheLaneChangeIsWanted() {
        val r = RoadRules()
        var t = 0L
        fun at(offset: Float, indicating: Boolean) = r.update(RoadScene(offset = offset, lines = 2), 100f, indicating, t).also { t += 100 }
        repeat(3) { at(0f, false) }
        listOf(0.06f, 0.12f, 0.18f, 0.24f, 0.3f).forEach { assertNull(at(it, true)) }
        // Indicator off as the car crosses: still the lane change.
        assertNull(at(0.34f, false))
    }

    @Test
    fun noLaneWarningsInTownOrWhenTurnedOff() {
        val slow = RoadRules()
        val off = RoadRules(lanes = false)
        var t = 0L
        listOf(0f, 0.05f, 0.1f, 0.15f, 0.2f, 0.25f, 0.3f).forEach {
            assertNull(slow.update(RoadScene(offset = it, lines = 2), 50f, false, t))
            assertNull(off.update(RoadScene(offset = it, lines = 2), 100f, false, t))
            t += 100
        }
    }

    @Test
    fun followingTooCloseForAWhile() {
        val r = RoadRules()
        // 15 m behind at 90 km/h: 0.6 s.
        val close = RoadScene(leadM = 15f, closingMps = 0f)
        assertNull(r.update(close, 90f, false, 0))
        assertNull(r.update(close, 90f, false, 2_900))
        assertEquals(RoadWarning.TOO_CLOSE, r.update(close, 90f, false, 3_000))
        // Not again at once, even if it carries on.
        assertNull(r.update(close, 90f, false, 8_000))
    }

    @Test
    fun aSafeGapIsFine() {
        val r = RoadRules()
        val fine = RoadScene(leadM = 50f, closingMps = 0f)
        repeat(50) { assertNull(r.update(fine, 90f, false, it * 100L)) }
    }

    @Test
    fun theCarAheadDrivesOffAtTheLights() {
        val r = RoadRules().driven()
        assertNull(r.update(RoadScene(leadM = 6f), 0f, false, 0))
        assertNull(r.update(RoadScene(leadM = 6f), 0f, false, 3_500))
        // Pulling away: a little further each frame.
        assertNull(r.update(RoadScene(leadM = 8f), 0f, false, 3_600))
        assertNull(r.update(RoadScene(leadM = 10f), 0f, false, 3_700))
        assertNull(r.update(RoadScene(leadM = 11f), 0f, false, 3_800))
        assertEquals(RoadWarning.MOVED_OFF, r.update(RoadScene(leadM = 12f), 0f, false, 4_100))
        // The driver sets off: gone, and not said again before the next stop.
        assertNull(r.update(RoadScene(leadM = 12f), 10f, false, 4_500))
    }

    @Test
    fun aFramesFlickerToTheCarBeyondIsNotTheCarAheadMovingOff() {
        val r = RoadRules().driven()
        for (t in 0..3_500 step 100) assertNull(r.update(RoadScene(leadM = 4.4f), 0f, false, t.toLong()))
        assertNull(r.update(RoadScene(leadM = 9.7f), 0f, false, 3_600))
        for (t in 3_700..5_000 step 100) assertNull(r.update(RoadScene(leadM = 4.4f), 0f, false, t.toLong()))
        // Nor is the queue a box that slid from 7 to 6 m for a fifth of a second before the parked cars at 12 m.
        val p = RoadRules().driven()
        for (t in 0..200 step 33) assertNull(p.update(RoadScene(leadM = 7f - t / 200f), 0f, false, t.toLong()))
        for (t in 233..5_000 step 33) assertNull(p.update(RoadScene(leadM = 12f), 0f, false, t.toLong()))
    }

    @Test
    fun noWordWhenTheCarAheadOnlyEdgesForward() {
        val r = RoadRules().driven()
        r.update(RoadScene(leadM = 6f), 0f, false, 0)
        assertNull(r.update(RoadScene(leadM = 8f), 0f, false, 4_000))
    }

    @Test
    fun noWordBeforeTheCarHasDriven() {
        // Parked at the kerb as the drive starts, a van 12 m ahead whose box slides away as the phone is placed.
        val r = RoadRules()
        for (t in 0..3_500 step 100) assertNull(r.update(RoadScene(leadM = 12f), 0f, false, t.toLong()))
        for (t in 3_600..5_000 step 100) assertNull(r.update(RoadScene(leadM = 12f + (t - 3_500) / 250f), 0f, false, t.toLong()))
    }

    /** Road watch has seen the car drive: its next stop can be a queue. */
    private fun RoadRules.driven() = apply { update(RoadScene(), 50f, false, -10_000) }

    @Test
    fun withoutAnySpeedNothingIsJudged() {
        val r = RoadRules()
        repeat(3) { assertNull(r.update(closingFast, null, false, it * 100L)) }
    }

    @Test
    fun aFrameWithoutASpeedKeepsTheWarningUpAndWhatWasCounted() {
        val r = RoadRules()
        r.update(closingFast, 90f, false, 0)
        assertEquals(RoadWarning.COLLISION, r.update(closingFast, 90f, false, 100))
        // The car's speed missed for a frame: the warning stays its time, and goes when it is over.
        assertEquals(RoadWarning.COLLISION, r.update(RoadScene(), null, false, 200))
        assertEquals(RoadWarning.COLLISION, r.update(RoadScene(), 90f, false, 1_500))
        assertNull(r.update(RoadScene(), null, false, 2_200))
        // Nor does it forget that the car has driven: the next stop is still a queue.
        val q = RoadRules().driven()
        assertNull(q.update(RoadScene(leadM = 6f), null, false, 0))
        assertNull(q.update(RoadScene(leadM = 6f), 0f, false, 100))
        assertNull(q.update(RoadScene(leadM = 6f), 0f, false, 3_500))
        assertNull(q.update(RoadScene(leadM = 8f), 0f, false, 3_600))
        assertNull(q.update(RoadScene(leadM = 10f), 0f, false, 3_700))
        assertNull(q.update(RoadScene(leadM = 11f), 0f, false, 3_800))
        assertEquals(RoadWarning.MOVED_OFF, q.update(RoadScene(leadM = 12f), 0f, false, 4_100))
    }

    @Test
    fun clearingForgetsEverythingHalfCounted() {
        // Told once about following too close: cleared, the next drive's first time is told too, not a minute later.
        val r = RoadRules()
        val close = RoadScene(leadM = 15f, closingMps = 0f)
        r.update(close, 90f, false, 0)
        assertEquals(RoadWarning.TOO_CLOSE, r.update(close, 90f, false, 3_000))
        r.clear()
        assertNull(r.update(close, 90f, false, 4_000))
        assertEquals(RoadWarning.TOO_CLOSE, r.update(close, 90f, false, 7_000))
        // An indicator on as Road watch stopped doesn't excuse a drift once it starts again.
        val l = RoadRules()
        l.update(RoadScene(offset = 0f, lines = 2), 100f, true, 0)
        l.clear()
        var t = 100L
        fun at(offset: Float) = l.update(RoadScene(offset = offset, lines = 2), 100f, false, t).also { t += 100 }
        repeat(3) { assertNull(at(0f)) }
        listOf(0.05f, 0.10f, 0.15f, 0.20f, 0.24f, 0.26f).forEach { assertNull(at(it)) }
        assertEquals(RoadWarning.LANE_RIGHT, at(0.27f))
    }

    @Test
    fun thePhonesSpeedStandsInForTheCars() {
        val r = RoadRules()
        val scene = closingFast.copy(speedKmh = 90f)
        r.update(scene, null, false, 0)
        assertEquals(RoadWarning.COLLISION, r.update(scene, null, false, 100))
    }

    @Test
    fun theSceneRoundTripsThroughTheCodec() {
        val sent = RoadScene(offset = -0.12f, lines = 2, leadM = 23.5f, closingMps = 3.2f, ttcS = 7.3f, leadKind = "car", speedKmh = 88f)
        assertEquals(sent, LinkCodec.decode(LinkCodec.encode(sent)))
    }
}
