package com.openauto.dash.roadwatch

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Road watch's eyes for speed-limit signs: round, a red ring round a light
 * middle that holds the number. This finds the rings with plain image maths;
 * the phone reads the number in the middle (SignReader, ML Kit) and
 * [SignConfirm] believes it once read twice. Red brick, tail lights and red
 * cars are red too, but none is a thin round ring round something light.
 *
 * It also finds the sign that ends a limit: a light disc with a thin dark rim
 * and a dark bar across it corner to corner (the number, struck through, or
 * nothing at all for "end of all restrictions"). Nothing on it needs reading:
 * seen twice, it drops the limit the last sign set. The bar is what gives it
 * away, so it is found from the bar outwards: a dark run along a diagonal, a
 * light face either side of it, a dark rim round the face.
 */

enum class SignKind {
    /** A red ring with the limit in it. */
    LIMIT,
    /** A light disc struck through: the limit ends. */
    END
}

/** A round sign: [box] in the upright frame's fractions, [sidePx] across in its pixels, [kind] what it says. */
data class SignSpot(val box: Box, val sidePx: Int, val kind: SignKind = SignKind.LIMIT)

object SignFinder {
    /** Narrower than this (pixels of the frame) a sign's number can't be read. */
    const val MIN_SIDE_PX = 16
    /** Narrower than this an end sign's bar is a pixel or two: a twig against the sky would do as well. */
    const val MIN_END_SIDE_PX = 20
    /** Every this many pixels: the ring is wider than that. */
    private const val STEP = 2
    /** A ring's red covers this share of its square: more is a red patch, less a scatter. */
    private const val MIN_FILL = 0.2f
    private const val MAX_FILL = 0.65f
    /** Its middle is this much light and at most this much red. */
    private const val MIN_LIGHT_MIDDLE = 0.3f
    private const val MAX_RED_MIDDLE = 0.25f

    /** Darker than this (luma) is a sign's ink: the bar and the rim of an end sign, black or grey. */
    private const val INK_MAX = 150
    /** An end sign's bar is at most this share as bright as its face (grey on white in the sun: 0.6), and so is its rim. */
    private const val BAR_SHADE = 0.65f
    private const val RIM_SHADE = 0.75f
    /** The rim is dark round this share of the face; a twig against the sky has none. */
    private const val MIN_RIM = 0.55f
    /** The face either side of the bar is light over this share (the number takes some). */
    private const val MIN_LIGHT_FACE = 0.4f
    private const val RIM_ANGLES = 32
    /** The rim is looked for at this many radii, 0.86 to 1.22 of the bar's half-length: the bar may stop short of it. */
    private const val RIM_RADII = 10
    /** A bar's cell has the face this many cells away at most, across it; the number it strikes is wider. */
    private const val BAR_REACH = 4
    /** A walk goes on this many cells past the last bar cell: through the number the bar strikes. */
    private const val MAX_GAP = 6

    /**
     * The round signs in the RGBA frame [rgba] (see [Upright.index] for the
     * other arguments), above [bottomY] of the upright frame's height (below
     * it is the bonnet): the red rings, then the end signs that aren't inside
     * one (a 7's stroke is a bar too).
     */
    fun find(rgba: ByteBuffer, width: Int, height: Int, rowStride: Int, rotation: Int, bottomY: Float = 1f): List<SignSpot> {
        val uw = Upright.width(width, height, rotation)
        val uh = Upright.height(width, height, rotation)
        val gw = uw / STEP
        val gh = (uh * bottomY.coerceIn(0f, 1f)).toInt() / STEP
        if (gw < 8 || gh < 8) return emptyList()
        val s = scratch.get().fit(gw * gh)
        val red = s.red
        val light = s.light
        val luma = s.luma
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val i = Upright.index(gx * STEP, gy * STEP, width, height, rowStride, rotation)
            val r = rgba.get(i).toInt() and 0xFF
            val g = rgba.get(i + 1).toInt() and 0xFF
            val b = rgba.get(i + 2).toInt() and 0xFF
            red[gy * gw + gx] = isRed(r, g, b)
            light[gy * gw + gx] = isLight(r, g, b)
            luma[gy * gw + gx] = lumaOf(r, g, b).toByte()
        }
        val limits = rings(s, red, light, gw, gh, uw, uh)
        return limits + ends(s, Frame(rgba, width, height, rowStride, rotation, uw, uh), red, light, luma, gw, gh, limits)
    }

    /**
     * The grids a frame is worked on, kept between frames: at 1280 x 960 they
     * weigh some 4 MB, which allocated and zeroed at every frame kept the
     * collector busy on the phone. One set per thread; a frame larger than any
     * before grows them. They may be larger than the frame: the loops stop at
     * gw * gh, never at the arrays' end.
     */
    private class Scratch {
        var red = BooleanArray(0)
        var light = BooleanArray(0)
        var luma = ByteArray(0)
        var ink = BooleanArray(0)
        var bar = BooleanArray(0)
        var seen = BooleanArray(0)
        var stack = IntArray(0)

        fun fit(n: Int): Scratch {
            if (red.size < n) {
                red = BooleanArray(n); light = BooleanArray(n); luma = ByteArray(n)
                ink = BooleanArray(n); bar = BooleanArray(n); seen = BooleanArray(n); stack = IntArray(n)
            }
            return this
        }
    }

    private val scratch = ThreadLocal.withInitial { Scratch() }

    /** A frame and its upright size, for sampling single pixels. */
    private class Frame(val rgba: ByteBuffer, val width: Int, val height: Int, val rowStride: Int, val rotation: Int, val uw: Int, val uh: Int) {
        /** The luma of the upright pixel ([u], [v]), or -1 outside the frame. */
        fun luma(u: Int, v: Int): Int {
            if (u < 0 || v < 0 || u >= uw || v >= uh) return -1
            val i = Upright.index(u, v, width, height, rowStride, rotation)
            return lumaOf(rgba.get(i).toInt() and 0xFF, rgba.get(i + 1).toInt() and 0xFF, rgba.get(i + 2).toInt() and 0xFF)
        }
    }

    /** The red rings round a light middle: speed limits. */
    private fun rings(s: Scratch, red: BooleanArray, light: BooleanArray, gw: Int, gh: Int, uw: Int, uh: Int): List<SignSpot> {
        val n = gw * gh
        val seen = s.seen
        seen.fill(false, 0, n)
        val stack = s.stack
        val out = ArrayList<SignSpot>()
        val minSide = MIN_SIDE_PX / STEP
        for (start in 0 until n) {
            if (!red[start] || seen[start]) continue
            // The red pixels touching this one, and the square round them.
            var top = 0
            stack[top++] = start
            seen[start] = true
            var count = 0
            var x0 = gw; var y0 = gh; var x1 = -1; var y1 = -1
            while (top > 0) {
                val p = stack[--top]
                count++
                val x = p % gw
                val y = p / gw
                x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
                if (x > 0 && red[p - 1] && !seen[p - 1]) { seen[p - 1] = true; stack[top++] = p - 1 }
                if (x < gw - 1 && red[p + 1] && !seen[p + 1]) { seen[p + 1] = true; stack[top++] = p + 1 }
                if (y > 0 && red[p - gw] && !seen[p - gw]) { seen[p - gw] = true; stack[top++] = p - gw }
                if (y < gh - 1 && red[p + gw] && !seen[p + gw]) { seen[p + gw] = true; stack[top++] = p + gw }
            }
            val bw = x1 - x0 + 1
            val bh = y1 - y0 + 1
            if (bw < minSide || bh < minSide || bh > gh / 2 || bw > gw / 3) continue
            if (bw < 0.7f * bh || bw > 1.4f * bh) continue
            val fill = count.toFloat() / (bw * bh)
            if (fill < MIN_FILL || fill > MAX_FILL) continue
            // The middle third: light (the sign's face), not red (a red disc or a brick wall).
            var n = 0; var reds = 0; var lights = 0
            for (y in y0 + bh / 3..y1 - bh / 3) for (x in x0 + bw / 3..x1 - bw / 3) {
                n++
                if (red[y * gw + x]) reds++
                if (light[y * gw + x]) lights++
            }
            if (n == 0 || reds > MAX_RED_MIDDLE * n || lights < MIN_LIGHT_MIDDLE * n) continue
            out += SignSpot(
                Box(x0 * STEP / uw.toFloat(), y0 * STEP / uh.toFloat(), (x1 + 1) * STEP / uw.toFloat(), (y1 + 1) * STEP / uh.toFloat()),
                max(bw, bh) * STEP
            )
        }
        return out
    }

    /**
     * The light discs struck through by a dark bar: the end of a limit. The
     * bar runs from a corner of the disc to the opposite one, rising to the
     * right on the Continent, so the picture is walked down both diagonals.
     * A walk starts on a bar cell (dark, with something much lighter a few
     * cells away on both sides across the diagonal: the face), and goes on
     * over dark cells (a step aside allowed, for a bar not quite at 45°),
     * through the number the bar strikes, until it has left the last bar
     * cell behind by [MAX_GAP]. A walk as tall as it is wide is a bar, and
     * the disc it crosses is drawn round it: its face either side of the bar
     * is light and much lighter than the bar, and a dark rim runs round it
     * (sampled on the frame's own pixels: it is thin). A bar inside a red
     * ring is a digit's.
     */
    private fun ends(s: Scratch, frame: Frame, red: BooleanArray, light: BooleanArray, luma: ByteArray, gw: Int, gh: Int, limits: List<SignSpot>): List<SignSpot> {
        val n = gw * gh
        val ink = s.ink
        for (i in 0 until n) ink[i] = !red[i] && (luma[i].toInt() and 0xFF) < INK_MAX
        val out = ArrayList<SignSpot>()
        val minRun = MIN_END_SIDE_PX / STEP / 2
        val bar = s.bar
        val seen = s.seen
        for (dir in intArrayOf(1, -1)) {
            for (i in 0 until n) bar[i] = ink[i] && isBarCell(i % gw, i / gw, dir, light, luma, gw, gh)
            seen.fill(false, 0, n)
            for (start in 0 until n) {
                if (!bar[start] || seen[start]) continue
                var x = start % gw
                var y = start / gw
                var n = 0
                var gap = 0
                var x0 = x; var x1 = x; var y0 = y; var y1 = y
                while (true) {
                    seen[y * gw + x] = true
                    if (bar[y * gw + x]) {
                        n++
                        gap = 0
                        x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
                    } else if (++gap > MAX_GAP) break
                    val nx = x + dir
                    val ny = y + 1
                    val diagonal = nx in 0 until gw && ny < gh && ink[ny * gw + nx] && !seen[ny * gw + nx]
                    val aside = nx in 0 until gw && ink[y * gw + nx] && !seen[y * gw + nx]
                    val below = ny < gh && ink[ny * gw + x] && !seen[ny * gw + x]
                    when {
                        diagonal -> { x = nx; y = ny }
                        aside -> x = nx
                        below -> y = ny
                        else -> break
                    }
                    if (x1 - x0 > gw / 4 || y1 - y0 > gw / 4) break
                }
                val dx = x1 - x0 + 1
                val dy = y1 - y0 + 1
                if (n < minRun || dx > gw / 4 || dy > gw / 4) continue
                if (dx < 0.7f * dy || dx > 1.4f * dy) continue
                // The bar crosses the disc through its middle: its half-length is the disc's radius.
                val cx = (x0 + x1) / 2f
                val cy = (y0 + y1) / 2f
                val r = kotlin.math.sqrt((dx * dx + dy * dy).toFloat()) / 2
                if (2 * r * STEP < MIN_END_SIDE_PX) continue
                val box = Box((cx + 0.5f - r) * STEP / frame.uw, (cy + 0.5f - r) * STEP / frame.uh, (cx + 0.5f + r) * STEP / frame.uw, (cy + 0.5f + r) * STEP / frame.uh)
                val cxF = (cx + 0.5f) * STEP / frame.uw
                val cyF = (cy + 0.5f) * STEP / frame.uh
                if (limits.any { it.box.contains(cxF, cyF) }) continue
                if (out.any { it.kind == SignKind.END && abs(it.box.centerX - cxF) * frame.uw < 2 * r * STEP && abs((it.box.top + it.box.bottom) / 2 - cyF) * frame.uh < 2 * r * STEP }) continue
                if (!isEndSign(frame, red, light, luma, gw, gh, cx, cy, r, dir)) continue
                out += SignSpot(box, (2 * r * STEP).toInt(), SignKind.END)
            }
        }
        return out
    }

    /**
     * The dark cell ([x], [y]) has a light cell much lighter than it within
     * [BAR_REACH] cells on both sides across the diagonal [dir]: a bar's cell,
     * or a rim's, not a dark wall's or the road's.
     */
    private fun isBarCell(x: Int, y: Int, dir: Int, light: BooleanArray, luma: ByteArray, gw: Int, gh: Int): Boolean {
        val l = luma[y * gw + x].toInt() and 0xFF
        var one = false
        var other = false
        for (k in 1..BAR_REACH) {
            // Across a diagonal (dir, 1) is (1, -dir).
            val ax = x + k; val ay = y - dir * k
            val bx = x - k; val by = y + dir * k
            if (!one && ax < gw && ay in 0 until gh && light[ay * gw + ax] && (luma[ay * gw + ax].toInt() and 0xFF) * BAR_SHADE > l) one = true
            if (!other && bx >= 0 && by in 0 until gh && light[by * gw + bx] && (luma[by * gw + bx].toInt() and 0xFF) * BAR_SHADE > l) other = true
            if (one && other) return true
        }
        return false
    }

    /** The disc of radius [r] (cells) at ([cx], [cy]) with a bar along the diagonal [dir] through it: an end sign? */
    private fun isEndSign(frame: Frame, red: BooleanArray, light: BooleanArray, luma: ByteArray, gw: Int, gh: Int, cx: Float, cy: Float, r: Float, dir: Int): Boolean {
        // The face: the cells inside the disc off the bar, and the bar's own cells well inside the rim.
        val barHalf = max(1.5f, 0.18f * r)
        var faceN = 0; var faceLight = 0; var faceRed = 0
        val faceLumas = IntArray(256)
        var barN = 0; var barLuma = 0L
        val inner = 0.85f * r
        for (y in max(0, (cy - inner).toInt())..min(gh - 1, (cy + inner).toInt() + 1)) {
            for (x in max(0, (cx - inner).toInt())..min(gw - 1, (cx + inner).toInt() + 1)) {
                val ex = x - cx
                val ey = y - cy
                if (ex * ex + ey * ey > inner * inner) continue
                val i = y * gw + x
                val l = luma[i].toInt() and 0xFF
                val offBar = abs(ex - dir * ey) / SQRT2
                if (offBar < barHalf * 0.5f) {
                    barN++; barLuma += l
                } else if (offBar > barHalf) {
                    faceN++
                    if (light[i]) { faceLight++; faceLumas[l]++ }
                    if (red[i]) faceRed++
                }
            }
        }
        if (faceN < 8 || barN < 2 || faceLight < MIN_LIGHT_FACE * faceN || faceRed > 0.1f * faceN) return false
        // The face's white: the brighter half of its light cells (the number is grey on some signs, and light enough to count).
        var left = (faceLight + 1) / 2
        var faceSum = 0L
        var faceCount = 0
        for (l in 255 downTo 0) {
            if (left == 0) break
            val take = min(left, faceLumas[l])
            faceSum += take.toLong() * l
            faceCount += take
            left -= take
        }
        val face = faceSum.toFloat() / faceCount
        if (barLuma.toFloat() / barN > BAR_SHADE * face) return false
        // The rim: a thin dark ring just outside the face, on the frame's own pixels.
        var sampled = 0; var dark = 0
        for (k in 0 until RIM_ANGLES) {
            val a = 2 * Math.PI * k / RIM_ANGLES
            var lo = Int.MAX_VALUE
            var inside = true
            for (f in 0 until RIM_RADII) {
                val rr = r * (0.86f + 0.04f * f)
                val u = ((cx + 0.5f + rr * kotlin.math.cos(a).toFloat()) * STEP).toInt()
                val v = ((cy + 0.5f + rr * kotlin.math.sin(a).toFloat()) * STEP).toInt()
                val l = frame.luma(u, v)
                if (l < 0) { inside = false; break }
                lo = min(lo, l)
            }
            if (!inside) continue
            sampled++
            if (lo < RIM_SHADE * face) dark++
        }
        return sampled >= RIM_ANGLES * 2 / 3 && dark >= MIN_RIM * sampled
    }

    private const val SQRT2 = 1.4142135f

    private fun lumaOf(r: Int, g: Int, b: Int): Int = (r * 2 + g * 5 + b) / 8

    /** A sign's red: red the strongest, well coloured, the hue within about 20° of red. */
    internal fun isRed(r: Int, g: Int, b: Int): Boolean {
        val lo = min(g, b)
        val range = r - lo
        return r > 60 && r >= g && r >= b && range > 0.35f * r && abs(g - b) * 3 < range
    }

    /** A sign's face: light and hardly coloured, white in the sun, grey in the shade. */
    internal fun isLight(r: Int, g: Int, b: Int): Boolean {
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        return lo > 95 && hi - lo < 0.3f * hi
    }
}

/**
 * The speed limit read off the signs, believed once the same number was read
 * [READS] times within [WINDOW_MS]: one frame's misreading ("80" for "30" in a
 * blur) doesn't change it. An end sign ([SignKind.END]) is read as [END], and
 * believed the same way: seen on two frames.
 */
class SignConfirm {
    private class Read(val kmh: Int, val at: Long)
    private val reads = ArrayDeque<Read>()

    /** The limit [kmh] (or [END]) read at [now] (ms): the limit now believed, or null while it isn't yet. */
    fun read(kmh: Int, now: Long): Int? {
        reads.addLast(Read(kmh, now))
        while (reads.isNotEmpty() && now - reads.first().at > WINDOW_MS) reads.removeFirst()
        return kmh.takeIf { reads.count { it.kmh == kmh } >= READS }
    }

    companion object {
        const val READS = 2
        const val WINDOW_MS = 3_000L
        /** Read off an end sign: no limit from a sign any more. */
        const val END = 0
        /** The limits a sign can show; anything else read is a misreading. */
        val LIMITS = setOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130)

        /** The limit in the text [read] off a sign's middle, or null when it isn't one. */
        fun limitIn(read: String): Int? {
            // Digits read as the letters they look like: O for 0 is the usual one (not S for 5: STOP would read 50).
            val digits = read.map {
                when (it) {
                    'O', 'o', 'D', 'Q' -> '0'
                    'I', 'l', '|' -> '1'
                    else -> it
                }
            }.filter { it.isDigit() }.joinToString("")
            digits.toIntOrNull()?.takeIf { it in LIMITS }?.let { return it }
            // Read as if upside down: a 90 comes back "06" (seen on the road, 2026-10-26). Turned round, 6 and 9 swap.
            if (digits.isEmpty() || digits.any { it !in "01689" }) return null
            val turned = digits.reversed().map { if (it == '6') '9' else if (it == '9') '6' else it }.joinToString("")
            return turned.toIntOrNull()?.takeIf { it in LIMITS }
        }
    }
}
