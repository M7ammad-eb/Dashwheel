package com.openauto.dash.roadwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.hypot
import kotlin.random.Random

class SignFinderTest {
    private val w = 640
    private val h = 480

    /** A grey street, RGBA. */
    private fun street(): ByteBuffer {
        val buf = ByteBuffer.allocate(w * h * 4)
        val rnd = Random(3)
        for (i in 0 until w * h) {
            val v = 110 + rnd.nextInt(-10, 11)
            buf.put(v.toByte()).put(v.toByte()).put((v + 5).toByte()).put(255.toByte())
        }
        return buf
    }

    private fun set(buf: ByteBuffer, x: Int, y: Int, r: Int, g: Int, b: Int) {
        if (x !in 0 until w || y !in 0 until h) return
        val i = (y * w + x) * 4
        buf.put(i, r.toByte()); buf.put(i + 1, g.toByte()); buf.put(i + 2, b.toByte())
    }

    /** A speed-limit sign [d] pixels across at ([cx], [cy]): a red ring, a white face, two black digits. */
    private fun sign(buf: ByteBuffer, cx: Int, cy: Int, d: Int, digits: Boolean = true) {
        val r = d / 2f
        for (y in cy - d..cy + d) for (x in cx - d..cx + d) {
            val e = hypot((x - cx).toFloat(), (y - cy).toFloat())
            when {
                e <= 0.78f * r -> {
                    val digit = digits && kotlin.math.abs(y - cy) < 0.4f * r && (kotlin.math.abs(x - cx + 0.25f * r) < 0.12f * r || kotlin.math.abs(x - cx - 0.25f * r) < 0.12f * r)
                    if (digit) set(buf, x, y, 30, 30, 30) else set(buf, x, y, 225, 225, 220)
                }
                e <= r -> set(buf, x, y, 200, 30, 40)
            }
        }
    }

    /**
     * An end-of-limit sign [d] pixels across at ([cx], [cy]): a white face in a
     * thin black rim, two digits, and a black bar corner to corner, [rising]
     * to the right (the Continent) or falling (Britain).
     */
    private fun endSign(buf: ByteBuffer, cx: Int, cy: Int, d: Int, rising: Boolean = true, digits: Boolean = true, grey: Boolean = false, barDeg: Float = 45f) {
        val r = d / 2f
        val ink = if (grey) 125 else 30
        // The bar's direction, [barDeg] up from the horizontal, rising or falling to the right.
        val bx = kotlin.math.cos(Math.toRadians(barDeg.toDouble())).toFloat()
        val by = kotlin.math.sin(Math.toRadians(barDeg.toDouble())).toFloat() * (if (rising) -1 else 1)
        for (y in cy - d..cy + d) for (x in cx - d..cx + d) {
            val e = hypot((x - cx).toFloat(), (y - cy).toFloat())
            if (e > r) continue
            val ex = x - cx
            val ey = y - cy
            val offBar = kotlin.math.abs(ex * by - ey * bx)
            val digit = digits && kotlin.math.abs(ey) < 0.4f * r && (kotlin.math.abs(ex + 0.25f * r) < 0.12f * r || kotlin.math.abs(ex - 0.25f * r) < 0.12f * r)
            when {
                e > 0.92f * r -> set(buf, x, y, ink, ink, ink)
                offBar < 0.07f * d -> set(buf, x, y, ink, ink, ink)
                digit -> set(buf, x, y, ink, ink, ink)
                else -> set(buf, x, y, 225, 225, 220)
            }
        }
    }

    /** An overcast sky across the top of the street. */
    private fun sky(buf: ByteBuffer) {
        for (y in 0 until 240) for (x in 0 until w) set(buf, x, y, 205, 208, 212)
    }

    @Test
    fun aSpeedLimitSignIsFound() {
        val buf = street()
        sign(buf, 500, 150, 40)
        val got = SignFinder.find(buf, w, h, w * 4, 0)
        assertEquals(1, got.size)
        assertEquals(SignKind.LIMIT, got[0].kind)
        assertEquals(500f / w, got[0].box.centerX, 0.01f)
        assertTrue("about 40 px across: ${got[0].sidePx}", got[0].sidePx in 36..44)
    }

    @Test
    fun anEndOfLimitSignIsFound() {
        val buf = street()
        sky(buf)
        endSign(buf, 500, 150, 40)
        val got = SignFinder.find(buf, w, h, w * 4, 0)
        assertEquals(got.toString(), 1, got.size)
        assertEquals(SignKind.END, got[0].kind)
        assertEquals(500f / w, got[0].box.centerX, 0.02f)
        assertEquals(150f / h, (got[0].box.top + got[0].box.bottom) / 2, 0.02f)
        assertTrue("about 40 px across: ${got[0].sidePx}", got[0].sidePx in 32..48)
    }

    @Test
    fun anEndSignIsFoundAgainstTheStreetAndWithoutDigits() {
        val buf = street()
        endSign(buf, 300, 300, 32, digits = false)
        val got = SignFinder.find(buf, w, h, w * 4, 0)
        assertEquals(got.toString(), 1, got.size)
        assertEquals(SignKind.END, got[0].kind)
    }

    @Test
    fun aBritishEndSignFallsToTheRight() {
        val buf = street()
        sky(buf)
        endSign(buf, 200, 120, 48, rising = false)
        val got = SignFinder.find(buf, w, h, w * 4, 0)
        assertEquals(got.toString(), 1, got.size)
        assertEquals(SignKind.END, got[0].kind)
    }

    @Test
    fun anEndSignLeaningInTheHolderIsFound() {
        // The phone a little turned, or the sign seen from the side: the bar is not quite at 45°.
        for (deg in floatArrayOf(37f, 53f)) {
            val buf = street()
            sky(buf)
            endSign(buf, 500, 150, 40, barDeg = deg)
            val got = SignFinder.find(buf, w, h, w * 4, 0)
            assertEquals("at $deg°: $got", 1, got.size)
            assertEquals(SignKind.END, got[0].kind)
        }
    }

    @Test
    fun aGreyEndSignIsFound() {
        // Germany's: grey ink, not black.
        val buf = street()
        sky(buf)
        endSign(buf, 400, 100, 44, grey = true)
        val got = SignFinder.find(buf, w, h, w * 4, 0)
        assertEquals(got.toString(), 1, got.size)
        assertEquals(SignKind.END, got[0].kind)
    }

    @Test
    fun anEndSignTooSmallIsLeft() {
        val buf = street()
        sky(buf)
        endSign(buf, 500, 150, 14)
        assertEquals(0, SignFinder.find(buf, w, h, w * 4, 0).size)
    }

    @Test
    fun aTwigAgainstTheSkyIsNoEndSign() {
        val buf = street()
        sky(buf)
        // A dark branch at 45°, as long as a sign's bar, with nothing round it.
        for (k in 0 until 40) for (t in 0 until 4) set(buf, 480 + k + t, 170 - k, 35, 30, 25)
        assertEquals(0, SignFinder.find(buf, w, h, w * 4, 0).size)
    }

    @Test
    fun aDigitsStrokeInALimitSignIsNoEndSign() {
        // A big "7": its stroke is a dark bar, inside a red ring.
        val buf = street()
        sky(buf)
        sign(buf, 320, 160, 80, digits = false)
        for (k in 0 until 32) for (t in 0 until 5) set(buf, 320 - 24 + k + t, 160 + 16 - k, 30, 30, 30)
        for (x in 296..316) for (t in 0 until 5) set(buf, x, 144 + t, 30, 30, 30)
        val got = SignFinder.find(buf, w, h, w * 4, 0)
        assertEquals(got.toString(), 1, got.size)
        assertEquals(SignKind.LIMIT, got[0].kind)
    }

    @Test
    fun aSignTooSmallToReadIsLeft() {
        val buf = street()
        sign(buf, 500, 150, 10)
        assertEquals(0, SignFinder.find(buf, w, h, w * 4, 0).size)
    }

    @Test
    fun aBrickWallIsNoSign() {
        val buf = street()
        val rnd = Random(5)
        // Bricks: red-brown blocks with light mortar between them.
        for (y in 100 until 300) for (x in 380 until 620) {
            val mortar = y % 12 < 2 || (x + (y / 12 % 2) * 12) % 24 < 2
            if (mortar) set(buf, x, y, 170, 165, 155) else set(buf, x, y, 160 + rnd.nextInt(-15, 16), 70, 55)
        }
        assertEquals(0, SignFinder.find(buf, w, h, w * 4, 0).size)
    }

    @Test
    fun aRedDiscIsNoSpeedLimit() {
        // No entry: a red disc with a white bar, red in its middle.
        val buf = street()
        for (y in 130 until 170) for (x in 480 until 520) {
            if (hypot(x - 500f, y - 150f) <= 20f) {
                if (kotlin.math.abs(y - 150) < 4 && kotlin.math.abs(x - 500) < 14) set(buf, x, y, 230, 230, 230) else set(buf, x, y, 200, 30, 40)
            }
        }
        assertEquals(0, SignFinder.find(buf, w, h, w * 4, 0).size)
    }

    @Test
    fun framesOfDifferentSizesOneAfterAnotherAreReadLikeFirstFrames() {
        // The finder keeps its grids between frames, per thread: a smaller frame after a larger one
        // works on arrays bigger than it, and must see nothing of the frame before.
        val big = street()
        sky(big)
        sign(big, 500, 150, 40)
        endSign(big, 200, 300, 40)
        val sw = 320
        val sh = 240
        val small = ByteBuffer.allocate(sw * sh * 4)
        for (i in 0 until sw * sh) small.put((205).toByte()).put((208).toByte()).put((212).toByte()).put(255.toByte())
        // One end sign against the sky, in a frame a quarter the size.
        for (y in 100 until 180) for (x in 210 until 290) {
            val e = hypot(x - 250f, y - 140f)
            val offBar = kotlin.math.abs((x - 250) * 0.7071f + (y - 140) * 0.7071f)
            val ink = e > 0.92f * 20f || offBar < 0.07f * 40
            val i = (y * sw + x) * 4
            if (e <= 20f) { val v = if (ink) 30 else 225; small.put(i, v.toByte()); small.put(i + 1, v.toByte()); small.put(i + 2, v.toByte()) }
        }
        val onOneThread = listOf(
            SignFinder.find(big, w, h, w * 4, 0),
            SignFinder.find(small, sw, sh, sw * 4, 0),
            SignFinder.find(big, w, h, w * 4, 0)
        )
        // A new thread starts with fresh grids: what a first frame gets.
        fun fresh(block: () -> List<SignSpot>): List<SignSpot> {
            var got: List<SignSpot>? = null
            Thread { got = block() }.apply { start(); join() }
            return got!!
        }
        assertEquals(2, onOneThread[0].size)
        assertEquals(1, onOneThread[1].size)
        assertEquals(SignKind.END, onOneThread[1][0].kind)
        assertEquals(fresh { SignFinder.find(big, w, h, w * 4, 0) }, onOneThread[0])
        assertEquals(fresh { SignFinder.find(small, sw, sh, sw * 4, 0) }, onOneThread[1])
        assertEquals(onOneThread[0], onOneThread[2])
    }

    @Test
    fun theNumberIsBelievedOnceReadTwice() {
        val c = SignConfirm()
        assertNull(c.read(30, 0))
        assertEquals(30, c.read(30, 400))
        // A blur read as 80 once doesn't change it.
        assertNull(c.read(80, 800))
        // An end sign, seen on two frames, ends it.
        assertNull(c.read(SignConfirm.END, 1_200))
        assertEquals(SignConfirm.END, c.read(SignConfirm.END, 1_500))
        // Seconds apart, two reads don't make one.
        val d = SignConfirm()
        assertNull(d.read(50, 0))
        assertNull(d.read(50, 5_000))
    }

    @Test
    fun onlyRealLimitsAreRead() {
        assertEquals(30, SignConfirm.limitIn("30"))
        assertEquals(90, SignConfirm.limitIn("9O"))
        assertEquals(110, SignConfirm.limitIn("1 10"))
        assertNull(SignConfirm.limitIn("STOP"))
        assertNull(SignConfirm.limitIn("35"))
        assertNull(SignConfirm.limitIn("3.5t"))
        // Read upside down, as ML Kit sometimes does.
        assertEquals(90, SignConfirm.limitIn("06"))
        assertEquals(80, SignConfirm.limitIn("08"))
        assertEquals(110, SignConfirm.limitIn("011"))
        assertNull(SignConfirm.limitIn("07"))
    }
}
