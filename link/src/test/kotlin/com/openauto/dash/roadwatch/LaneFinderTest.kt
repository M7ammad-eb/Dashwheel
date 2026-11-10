package com.openauto.dash.roadwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

class LaneFinderTest {
    private val band = RoadBand(horizonY = 0.5f, bonnetY = 0.92f)

    @Test
    fun findsBothLinesOfAStraightLane() {
        val got = LaneFinder.find(road(left = 0.15f, right = 0.85f), band)
        assertNear(0.15f, got.left?.bottomX)
        assertNear(0.85f, got.right?.bottomX)
        // Both lean in towards the point on the horizon they run to.
        assertNear(0.5f + (0.15f - 0.5f) * topDepth(), got.left?.topX)
        assertNear(0.5f + (0.85f - 0.5f) * topDepth(), got.right?.topX)
    }

    @Test
    fun aDashedLineIsStillALine() {
        val got = LaneFinder.find(road(left = 0.2f, right = 0.8f, dashedLeft = true), band)
        assertNear(0.2f, got.left?.bottomX)
        assertNear(0.8f, got.right?.bottomX)
        assertTrue("the dashes back it less than the solid line", got.leftSupport < got.rightSupport)
    }

    @Test
    fun atNightTheLitLinesStandOutOfADarkRoad() {
        val got = LaneFinder.find(road(left = 0.15f, right = 0.85f, roadGrey = 25, lineGrey = 95, skyGrey = 10), band)
        assertNear(0.15f, got.left?.bottomX)
        assertNear(0.85f, got.right?.bottomX)
    }

    @Test
    fun theNearestLineOnEachSideIsTheCarsLane() {
        // Three lanes: the car in the middle one, the outer lines further out.
        val got = LaneFinder.find(road(lines = listOf(-0.55f, 0.15f, 0.85f, 1.55f)), band)
        assertNear(0.15f, got.left?.bottomX)
        assertNear(0.85f, got.right?.bottomX)
    }

    @Test
    fun anUprightPhoneSeesTheSameLaneWider() {
        // Upright, the frame spans a 51° view instead of 66°: the lane fills it, and the markings are wider.
        val wider = widthShare(1f, 51f)
        val img = road(left = 0.5f - 0.35f * wider, right = 0.5f + 0.35f * wider, lineWidth = 0.03f * wider, w = 120, h = 160)
        val got = LaneFinder.find(img, band, hfovDeg = 51f)
        assertNear(0.5f - 0.35f * wider, got.left?.bottomX)
        assertNear(0.5f + 0.35f * wider, got.right?.bottomX)
    }

    @Test
    fun theWidthShareFollowsTheView() {
        assertEquals(0.1f, widthShare(0.1f, REFERENCE_HFOV), 0.0001f)
        assertEquals(0.136f, widthShare(0.1f, 51f), 0.002f)
    }

    @Test
    fun aRoadWithoutMarkingsHasNoLane() {
        val got = LaneFinder.find(road(lines = emptyList()), band)
        assertNull(got.left)
        assertNull(got.right)
    }

    @Test
    fun aShadowAcrossTheRoadIsNotALine() {
        // A tree's shadow: the road dark on one side of an edge, bright on the other.
        val img = road(lines = emptyList())
        for (y in 0 until img.h) for (x in 0 until img.w) {
            if (y > img.h / 2 && x > img.w * 0.3f + (y - img.h / 2) * 0.4f) img.px[y * img.w + x] = 150
        }
        val got = LaneFinder.find(img, band)
        assertNull(got.left)
        assertNull(got.right)
    }

    @Test
    fun aLaneOffTheMiddleIsFoundWhereTheCarIs() {
        val got = LaneFinder.find(road(left = 0.3f, right = 1.0f), band, carX = 0.6f)
        assertNear(0.3f, got.left?.bottomX)
        assertNear(1.0f, got.right?.bottomX)
    }

    @Test
    fun theTrackerBelievesALineSeenThreeFramesRunning() {
        val t = LaneTracker()
        val seen = LaneSighting(LaneLine(0.15f, 0.47f), LaneLine(0.85f, 0.53f))
        assertEquals(0, t.update(seen, 0).lines)
        assertEquals(0, t.update(seen, 100).lines)
        val third = t.update(seen, 200)
        assertEquals(2, third.lines)
        assertEquals(0f, third.offset!!, 0.01f)
    }

    @Test
    fun aDashMissingForAMomentKeepsTheLine() {
        val t = LaneTracker()
        val seen = LaneSighting(LaneLine(0.15f, 0.47f), LaneLine(0.85f, 0.53f))
        repeat(3) { t.update(seen, it * 100L) }
        val gap = LaneSighting(right = LaneLine(0.85f, 0.53f))
        assertEquals(2, t.update(gap, 600).lines)
        assertEquals(1, t.update(gap, 1_000).lines)
    }

    @Test
    fun theCarLeftOfTheLanesMiddleReadsNegative() {
        val t = LaneTracker()
        val seen = LaneSighting(LaneLine(0.3f, 0.48f), LaneLine(1.0f, 0.55f))
        var state = LaneState()
        repeat(4) { state = t.update(seen, it * 100L) }
        // The lane's middle at 0.65, the car at 0.5: 0.15 of a 0.7 lane to its left.
        assertEquals(-0.214f, state.offset!!, 0.01f)
    }

    @Test
    fun oneLineAndTheLanesWidthStillPlaceTheCar() {
        val t = LaneTracker()
        repeat(4) { t.update(LaneSighting(LaneLine(0.15f, 0.47f), LaneLine(0.85f, 0.53f)), it * 100L) }
        var state = LaneState()
        // The left line gone for good (a slip road), the right one drifting nearer.
        repeat(12) { state = t.update(LaneSighting(right = LaneLine(0.80f, 0.53f)), 500 + it * 100L) }
        assertEquals(1, state.lines)
        assertEquals(0.07f, state.offset!!, 0.02f)
    }

    @Test
    fun aJumpIsBelievedOnlyOnceItStays() {
        val t = LaneTracker()
        val seen = LaneSighting(LaneLine(0.15f, 0.47f), LaneLine(0.85f, 0.53f))
        repeat(3) { t.update(seen, it * 100L) }
        val jumped = LaneSighting(LaneLine(0.30f, 0.48f), LaneLine(0.85f, 0.53f))
        repeat(7) { assertNear(0.15f, t.update(jumped, 300 + it * 100L).left?.bottomX) }
        assertNear(0.30f, t.update(jumped, 1_000).left?.bottomX)
    }

    @Test
    fun theRoadsFarEdgeIsNotTheLanesLineInADashesGap() {
        // The lane's left line dashed at 0.15 (dashedLeft dashes the first line listed), the road's solid
        // edge far out at -0.55: the edge has more points than the dashes and must still not be taken for them.
        val img = road(lines = listOf(0.15f, -0.55f, 0.85f), dashedLeft = true, seed = 11)
        val got = LaneFinder.find(img, band)
        assertNear(0.15f, got.left?.bottomX)
        assertNear(0.85f, got.right?.bottomX)
    }

    @Test
    fun aVehiclesUprightStripesAreNotALine() {
        // A van ahead: a white pillar standing up its back, between the car and the lane's right line.
        val img = road(left = 0.15f, right = 0.85f)
        upright(img, at = 0.62f, fromY = 0.6f)
        assertNear(0.62f, LaneFinder.find(img, band).right?.bottomX)
        val got = LaneFinder.find(img, band, vehicles = listOf(Box(0.55f, 0.55f, 0.7f, 0.95f)))
        assertNear(0.85f, got.right?.bottomX)
    }

    @Test
    fun aPoleIsNotALine() {
        // Upright at the side, it does not run to the horizon ahead as a marking does.
        val img = road(lines = listOf(0.85f))
        upright(img, at = 0.2f, fromY = 0.5f)
        val got = LaneFinder.find(img, band)
        assertNull(got.left)
        assertNear(0.85f, got.right?.bottomX)
    }

    @Test
    fun aLaneTooNarrowLeavesOutTheLineTakenUpLast() {
        val t = LaneTracker()
        repeat(20) { t.update(LaneSighting(LaneLine(0.15f, 0.47f), LaneLine(0.85f, 0.53f)), it * 100L) }
        // The right line jumps to the van ahead and stays there: a 0.4 lane against the 0.7 learned.
        var state = LaneState()
        repeat(10) { state = t.update(LaneSighting(LaneLine(0.15f, 0.47f), LaneLine(0.55f, 0.505f)), 2_000 + it * 100L) }
        assertNull(state.right)
        assertNear(0.15f, state.left?.bottomX)
    }

    @Test
    fun theTrackerLearnsWhereTheCarDrives() {
        // The phone sits right of the windscreen's middle: the lane looks shifted left.
        val t = LaneTracker()
        val seen = LaneSighting(LaneLine(0.05f, 0.45f), LaneLine(0.75f, 0.52f))
        var state = LaneState()
        repeat(3_000) { state = t.update(seen, it * 100L, learn = true) }
        assertEquals(0.4f, t.carX, 0.01f)
        assertEquals(0f, state.offset!!, 0.02f)
    }

    @Test
    fun aJunctionsFarEdgeIsNotTheLanesLine() {
        val t = LaneTracker()
        repeat(20) { t.update(LaneSighting(LaneLine(0.3f, 0.47f), LaneLine(0.67f, 0.53f)), it * 100L) }
        // At a junction the right line jumps out to the road's far edge: the lane would be 1.9 times as wide.
        var state = LaneState()
        repeat(10) { state = t.update(LaneSighting(LaneLine(0.29f, 0.47f), LaneLine(1.0f, 0.56f)), 2_000 + it * 100L) }
        assertNull(state.right)
        assertNear(0.29f, state.left?.bottomX)
        assertEquals("the car is still in the middle of its lane", 0.05f, state.offset!!, 0.06f)
    }

    @Test
    fun aRoadThatStaysWiderIsTakenUp() {
        val t = LaneTracker()
        repeat(20) { t.update(LaneSighting(LaneLine(0.3f, 0.47f), LaneLine(0.67f, 0.53f)), it * 100L) }
        // A slip road joining a motorway: the lane is wider from here on.
        var state = LaneState()
        repeat(40) { state = t.update(LaneSighting(LaneLine(0.2f, 0.46f), LaneLine(0.8f, 0.54f)), 2_000 + it * 100L) }
        assertNear(0.8f, state.right?.bottomX)
    }

    @Test
    fun changingLanesTeachesNothing() {
        val t = LaneTracker()
        repeat(200) { t.update(LaneSighting(LaneLine(0.15f, 0.47f), LaneLine(0.85f, 0.53f)), it * 100L, learn = true) }
        // Ten seconds straddling the right line: the car is 0.4 of a lane off the middle.
        repeat(100) { t.update(LaneSighting(LaneLine(-0.13f, 0.46f), LaneLine(0.57f, 0.51f)), 20_000 + it * 100L, learn = true) }
        assertEquals(0.5f, t.carX, 0.005f)
    }

    @Test
    fun anUprightFrameFromAPhoneHeldSideways() {
        // 3 x 2 frame, pixel values 10, 20, ... by position; the camera turns it 90° clockwise.
        val w = 3
        val h = 2
        val buf = ByteBuffer.allocate(w * h * 4)
        for (i in 0 until w * h) {
            val v = (i + 1) * 10
            buf.put(i * 4, v.toByte()); buf.put(i * 4 + 1, v.toByte()); buf.put(i * 4 + 2, v.toByte())
        }
        val upright = Luma.fromRgba(buf, w, h, rowStride = w * 4, rotation = 90, outW = 2)
        assertEquals(2, upright.w)
        assertEquals(3, upright.h)
        // Turned clockwise, the frame's bottom-left pixel (40) is top-left.
        assertEquals(listOf(40, 10, 50, 20, 60, 30), upright.px.toList())
        val flipped = Luma.fromRgba(buf, w, h, rowStride = w * 4, rotation = 180, outW = 3)
        assertEquals(listOf(60, 50, 40, 30, 20, 10), flipped.px.toList())
    }

    @Test
    fun theGreyOfEachPixelIsTheWeightedMeanOfItsBytes() {
        // Every pixel its own colour, the frame read whole (one output pixel a source pixel) in both byte
        // orders: each grey is what the weights give the three bytes, whichever way the int was read.
        val w = 5
        val h = 3
        val rnd = Random(11)
        val bytes = ByteArray(w * h * 4) { rnd.nextInt(256).toByte() }
        val expected = IntArray(w * h) {
            val r = bytes[it * 4].toInt() and 0xFF
            val g = bytes[it * 4 + 1].toInt() and 0xFF
            val b = bytes[it * 4 + 2].toInt() and 0xFF
            (77 * r + 150 * g + 29 * b) shr 8
        }
        for (order in listOf(java.nio.ByteOrder.BIG_ENDIAN, java.nio.ByteOrder.LITTLE_ENDIAN)) {
            val buf = ByteBuffer.wrap(bytes).order(order)
            val luma = Luma.fromRgba(buf, w, h, rowStride = w * 4, rotation = 0, outW = w)
            assertEquals(order.toString(), expected.toList(), luma.px.toList())
        }
    }

    private fun topDepth() = band.depth(band.topY)

    /** A bright upright stripe at [at] of the width, from row [fromY] down to the bonnet. */
    private fun upright(img: Luma, at: Float, fromY: Float) {
        val x0 = (at * img.w).toInt()
        for (y in (fromY * img.h).toInt() until (band.bonnetY * img.h).toInt()) for (x in x0 - 1..x0 + 1) img.px[y * img.w + x] = 220
    }

    private fun assertNear(expected: Float, got: Float?) {
        assertNotNull("no line found", got)
        assertTrue("expected $expected, got $got", abs(expected - got!!) < 0.03f)
    }
}

/**
 * A road as a camera ahead sees it: sky above the horizon, grey tarmac with
 * some noise, lines running to a point on the horizon, the bonnet at the
 * bottom. [lines]: where each line crosses the bonnet's row, as fractions of
 * the width.
 */
internal fun road(
    left: Float = 0.15f,
    right: Float = 0.85f,
    lines: List<Float> = listOf(left, right),
    dashedLeft: Boolean = false,
    /** A line's width at the bonnet's row, a share of the picture's width. */
    lineWidth: Float = 0.03f,
    w: Int = 160,
    h: Int = 120,
    horizonY: Float = 0.5f,
    bonnetY: Float = 0.92f,
    roadGrey: Int = 95,
    lineGrey: Int = 200,
    skyGrey: Int = 175,
    seed: Int = 7
): Luma {
    val rnd = Random(seed)
    val img = Luma(w, h)
    val yh = horizonY * h
    val yb = bonnetY * h
    val vx = 0.5f * w
    for (y in 0 until h) {
        val t = (y - yh) / (yb - yh)
        for (x in 0 until w) {
            var v = when {
                y < yh -> skyGrey
                y > yb -> 40
                else -> roadGrey + rnd.nextInt(-8, 9)
            }
            if (y >= yh && y <= yb && t > 0f) {
                lines.forEachIndexed { i, at ->
                    val cx = vx + (at * w - vx) * t
                    val half = max(0.5f, lineWidth * w * t / 2)
                    // Dashes 3 m long every 9 m: on or off by distance, which goes as 1/t.
                    val on = !(dashedLeft && i == 0) || ((1f / t) * 2f).roundToInt() % 3 == 0
                    if (on && abs(x + 0.5f - cx) <= half) v = lineGrey + rnd.nextInt(-10, 11)
                }
            }
            img.px[y * w + x] = v.coerceIn(0, 255)
        }
    }
    return img
}
