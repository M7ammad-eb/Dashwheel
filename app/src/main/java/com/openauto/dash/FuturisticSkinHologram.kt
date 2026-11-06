package com.openauto.dash

import com.openauto.dash.vehicle.rememberDoors
import com.openauto.dash.vehicle.rememberGaugeInt
import com.openauto.dash.vehicle.rememberReadings
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.rememberTyres
import com.openauto.dash.vehicle.rememberVehicle
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The Futuristic skin's hologram: the driver's own car (MyCarLook.kt) as a
 * cyan line drawing with a double glow, floating over a holographic pad of
 * squashed rings that spin, a scan line sweeping over it, and its readings
 * (engine temperature, tyres, fuel, doors) pointed out by dots and leader
 * lines to labels at its sides. Without the driver's car, a drawn profile
 * of a compact MPV takes its place, so the tile is never empty.
 *
 * Android 10 has no blur, so the glow is made once per picture on the CPU:
 * the outline is shrunk, box-blurred and kept as two small white masks (a
 * tight one and a wide one) that the GPU stretches back over the car, which
 * reads as a soft bloom for the price of two textured quads a frame.
 */

/** The drawn car's box, 100 x 42 units, in a fraction (its width over its height). */
internal const val FUT_CAR_ASPECT = 100f / 42f

/** One loop holds whole cycles of every motion below, so one ticker drives them all. */
private const val HOLO_LOOP_MS = 36_000
private const val FLOAT_CYCLES = 6
private const val SCAN_CYCLES = 10
private const val DASH_TURNS = 2
private const val DOT_TURNS = 3

/** The pad's height over its width (the mockup's scaleY), and its radius over the car's width. */
private const val PAD_SQUASH = 0.2f
private const val PAD_SPAN = 0.52f

/** How high the car floats, over its width. */
private const val BOB = 0.013f

private const val TWO_PI = (2 * PI).toFloat()

/**
 * The drawn car's body in profile, nose to the left, in a [width] x [height]
 * box at ([left], [top]): low bonnet, the long glasshouse of a monospace and
 * a tall tail, wheel arches cut in.
 */
internal fun futCarProfilePath(left: Float, top: Float, width: Float, height: Float): Path {
    val sx = width / 100f
    val sy = height / 42f
    fun x(v: Float) = left + v * sx
    fun y(v: Float) = top + v * sy
    return Path().apply {
        moveTo(x(3f), y(30f))
        lineTo(x(3f), y(24.5f))
        cubicTo(x(3.5f), y(21f), x(8f), y(19.5f), x(16f), y(18.5f))
        lineTo(x(27f), y(17.5f))
        cubicTo(x(32f), y(12f), x(37f), y(6.5f), x(44f), y(5.5f))
        lineTo(x(71f), y(5f))
        cubicTo(x(79f), y(5f), x(86f), y(9f), x(93f), y(14f))
        cubicTo(x(96f), y(16.5f), x(97f), y(20f), x(97f), y(24f))
        lineTo(x(96.5f), y(30f))
        lineTo(x(85f), y(30f))
        arcTo(Rect(Offset(x(76f), y(30f)), 8.5f * sx), 0f, -180f, false)
        lineTo(x(30.5f), y(30f))
        arcTo(Rect(Offset(x(22f), y(30f)), 8.5f * sx), 0f, -180f, false)
        close()
    }
}

/** The drawn car's glass, door cuts, belt line and wheels in [car] (nose to the left; drawn mirrored). */
private class FuturisticDrawnCar(private val car: Rect) {
    private val sx = car.width / 100f
    private val sy = car.height / 42f
    private fun x(v: Float) = car.left + v * sx
    private fun y(v: Float) = car.top + v * sy

    val body = futCarProfilePath(car.left, car.top, car.width, car.height)
    val glass = Path().apply {
        moveTo(x(30f), y(17.2f))
        cubicTo(x(34f), y(12.5f), x(38f), y(8.5f), x(44.5f), y(7.8f))
        lineTo(x(70f), y(7.4f))
        cubicTo(x(76f), y(7.6f), x(81f), y(10.5f), x(86f), y(15.8f))
        close()
    }
    val details = Path().apply {
        moveTo(x(55f), y(7.4f)); lineTo(x(55f), y(29.5f))
        moveTo(x(31f), y(17.4f)); lineTo(x(33f), y(29.5f))
        moveTo(x(74f), y(16.5f)); lineTo(x(69f), y(26f))
        moveTo(x(8f), y(24f)); lineTo(x(94f), y(23f))
    }
    val wheels = Path().apply {
        for (cx in floatArrayOf(22f, 76f)) {
            addOval(Rect(Offset(x(cx), y(30f)), 7f * sx))
            addOval(Rect(Offset(x(cx), y(30f)), 4f * sx))
            for (k in 0 until 5) {
                val a = k * TWO_PI / 5f
                moveTo(x(cx) + cos(a) * 1.4f * sx, y(30f) + kotlin.math.sin(a) * 1.4f * sx)
                lineTo(x(cx) + cos(a) * 4f * sx, y(30f) + kotlin.math.sin(a) * 4f * sx)
            }
        }
    }
    val lamp = Path().apply { moveTo(x(4.5f), y(21.5f)); lineTo(x(10f), y(20f)) }
    val unit = sx
}

/** Where readings sit on the car: on the engine, the front wheel, the fuel filler and the roof. */
private enum class FutHoloSlot { FRONT, WHEEL, REAR, ROOF }

/** The four slots as fractions of the car's picture. */
private class FutHoloAnchors(val front: Offset, val wheel: Offset, val rear: Offset, val roof: Offset) {
    fun of(slot: FutHoloSlot): Offset = when (slot) {
        FutHoloSlot.FRONT -> front
        FutHoloSlot.WHEEL -> wheel
        FutHoloSlot.REAR -> rear
        FutHoloSlot.ROOF -> roof
    }
}

/** The drawn car is shown nose to the right (mirrored), as in the mockup. */
private val DrawnAnchors = FutHoloAnchors(Offset(0.88f, 0.48f), Offset(0.78f, 0.71f), Offset(0.14f, 0.43f), Offset(0.42f, 0.15f))

/**
 * The slots on [view]: from the pack's nose, tail and wheel anchors where it
 * has them, else the mockup's places on a 3/4 view, turned to the side the
 * nose points to.
 */
private fun anchorsOf(view: CarView?): FutHoloAnchors {
    if (view == null) return DrawnAnchors
    val nose = view.nose
    val tail = view.tail
    val noseRight = when {
        nose != null && tail != null -> nose.x >= tail.x
        view.wheels.size >= 2 -> view.wheels[0].x >= view.wheels[1].x
        else -> true
    }
    fun m(x: Float, y: Float) = if (noseRight) Offset(x, y) else Offset(1f - x, y)
    val front = nose?.let { Offset(it.x + (0.5f - it.x) * 0.2f, (it.y - 0.06f).coerceIn(0.25f, 0.75f)) } ?: m(0.70f, 0.57f)
    val rear = tail?.let { Offset(it.x + (0.5f - it.x) * 0.16f, (it.y - 0.12f).coerceIn(0.2f, 0.7f)) } ?: m(0.17f, 0.42f)
    val wheel = view.wheels.firstOrNull() ?: m(0.54f, 0.86f)
    return FutHoloAnchors(front, wheel, rear, m(0.42f, 0.13f))
}

/** The scene's layout in px: the car, its pad and the label columns either side. */
private class FutHoloGeo(val car: Rect, val padC: Offset, val padR: Float, val bob: Float, val colW: Float, val gap: Float, val w: Float, val h: Float)

/**
 * The car as large as fits between the label columns ([colW] each side, 0
 * without callouts) with its float above it and its pad's lower half below
 * it, the whole stack centred in [h].
 */
private fun holoGeo(w: Float, h: Float, aspect: Float, colW: Float, gap: Float): FutHoloGeo {
    val areaL = colW + gap
    val areaW = (w - 2f * (colW + gap)).coerceAtLeast(1f)
    val perWidth = BOB + 0.96f / aspect + PAD_SPAN * PAD_SQUASH
    val carW = min(areaW * 0.9f, h * 0.94f / perWidth).coerceAtLeast(1f)
    val carH = carW / aspect
    val bob = carW * BOB
    val stackTop = (h - carW * perWidth) / 2f
    val car = Rect(Offset(areaL + (areaW - carW) / 2f, stackTop + bob), Size(carW, carH))
    val padC = Offset(car.center.x, car.bottom - carH * 0.04f)
    return FutHoloGeo(car, padC, carW * PAD_SPAN, bob, colW, gap, w, h)
}

/** A reading pointed out on the car: [values] longest first, the first that fits is shown. */
private class FutHoloCallout(val slot: FutHoloSlot, val label: String, val values: List<String>, val alert: Boolean)

/** Glows of a car's outline, blurred once: [tight] and [wide] masks with the room each adds around the car, as a share of its size. */
private class FutHoloGlow(val tight: ImageBitmap, val tightSpread: Float, val wide: ImageBitmap, val wideSpread: Float)

/** The glow of the car on screen, kept so the next tile or page does not blur it again. */
private object FutHoloGlowCache {
    private var view: CarView? = null
    private var glow: FutHoloGlow? = null

    @Synchronized
    fun get(v: CarView): FutHoloGlow? = if (view === v) glow else null

    @Synchronized
    fun put(v: CarView, g: FutHoloGlow) {
        view = v
        glow = g
    }
}

private fun buildGlow(view: CarView): FutHoloGlow {
    val src = view.outline.asAndroidBitmap()
    val (tight, tightSpread) = blurredMask(src, 260, 0.07f, 2, 5f)
    val (wide, wideSpread) = blurredMask(src, 150, 0.16f, 3, 7f)
    return FutHoloGlow(tight, tightSpread, wide, wideSpread)
}

/**
 * [src]'s alpha shrunk to [width] px across (with [margin] of it left free on
 * each side for the glow to spread into), box-blurred three times (close to
 * a gaussian) by [radius] and brightened by [gain], as a white mask. Also
 * returns the margin over the car's own width.
 */
private fun blurredMask(src: Bitmap, width: Int, margin: Float, radius: Int, gain: Float): Pair<ImageBitmap, Float> {
    val inner = (width * (1f - 2f * margin)).roundToInt().coerceAtLeast(8)
    val innerH = (inner * src.height.toFloat() / src.width.coerceAtLeast(1)).roundToInt().coerceAtLeast(4)
    val padX = ((width - inner) / 2).coerceAtLeast(1)
    val padY = (innerH * padX.toFloat() / inner).roundToInt().coerceAtLeast(1)
    val bw = inner + 2 * padX
    val bh = innerH + 2 * padY
    val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(bmp).drawBitmap(
        src, null, android.graphics.Rect(padX, padY, padX + inner, padY + innerH),
        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    )
    val px = IntArray(bw * bh)
    bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
    val a = FloatArray(px.size) { (px[it] ushr 24).toFloat() }
    val tmp = FloatArray(px.size)
    repeat(3) {
        boxBlur(a, tmp, bw, bh, radius, horizontal = true)
        boxBlur(tmp, a, bw, bh, radius, horizontal = false)
    }
    for (i in px.indices) px[i] = ((a[i] * gain).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
    bmp.setPixels(px, 0, bw, 0, 0, bw, bh)
    return bmp.asImageBitmap() to padX.toFloat() / inner
}

/** One running-sum box blur pass of [r] along rows or columns, edges clamped. */
private fun boxBlur(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    val step = if (horizontal) 1 else w
    val div = 2f * r + 1f
    for (line in 0 until lines) {
        val start = if (horizontal) line * w else line
        var sum = 0f
        for (k in -r..r) sum += src[start + k.coerceIn(0, len - 1) * step]
        for (i in 0 until len) {
            dst[start + i * step] = sum / div
            sum += src[start + (i + r + 1).coerceIn(0, len - 1) * step] - src[start + (i - r).coerceIn(0, len - 1) * step]
        }
    }
}

/**
 * The hologram, filling [modifier]: the car on its pad, and with [callouts]
 * its readings pointed out at its sides. The pad, the float and the scan
 * line move on one ambient ticker in their own layer; the callouts are drawn
 * apart and only change with the readings.
 */
@Composable
internal fun FutHologram(env: SkinTileEnv, callouts: Boolean, modifier: Modifier = Modifier) {
    val mine by MyCarLook.shown.collectAsState()
    val view = mine?.portrait ?: mine?.top
    // The previous car's glow is dropped at once, so it never shines around a new picture while that one's is made.
    val glow by produceState(view?.let { FutHoloGlowCache.get(it) }, view) {
        val v = view
        value = v?.let { FutHoloGlowCache.get(it) }
        if (v == null || value != null) return@produceState
        value = withContext(Dispatchers.Default) { runCatching { buildGlow(v) }.getOrNull() }?.also { FutHoloGlowCache.put(v, it) }
    }
    val items = if (callouts) rememberHoloCallouts() else emptyList()
    val doorsOpen = items.any { it.slot == FutHoloSlot.ROOF && it.alert }
    val anchors = remember(view) { anchorsOf(view) }
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val colW = if (callouts) with(density) { (maxWidth * 0.22f).coerceIn(104.dp, 190.dp).toPx() } else 0f
        val gap = with(density) { 10.dp.toPx() }
        val aspect = view?.aspect ?: FUT_CAR_ASPECT
        val geo = remember(w, h, colW, gap, aspect) { holoGeo(w, h, aspect, colW, gap) }
        HoloScene(view, glow, geo, doorsOpen)
        if (items.isNotEmpty()) HoloCallouts(items, geo, anchors)
    }
}

/**
 * What the hologram points out: the coolant on the engine; the tyres on the
 * front wheel (the one in trouble, else the lowest), or the battery, or the
 * revs when the unit has no tyre sensors; fuel and range at the filler (the
 * lights there when the car gives no fuel level); the
 * doors on the roof. A reading not known yet is left out, except the engine,
 * which shows "--" until OBD answers.
 */
@Composable
private fun rememberHoloCallouts(): List<FutHoloCallout> {
    val units = LocalUnits.current
    val locale = Locale.getDefault()
    val d = rememberReadings(K.COOLANT_TEMP, K.BATTERY_VOLTS)
    val revs = rememberGaugeInt(K.RPM)
    val fuel = rememberFuel()
    val doors = rememberDoors()
    val lights = rememberVehicle(K.HEADLIGHTS)
    val tyres = rememberTyres()
    val problems by Tyres.problems.collectAsState()
    val pressureUnit by Tyres.unit.collectAsState()
    val engine = stringResource(R.string.skin_futuristic_engine)
    val tyresLabel = stringResource(R.string.skin_futuristic_tyres)
    val battery = stringResource(R.string.info_chip_battery)
    val rpm = stringResource(R.string.skin_futuristic_rpm)
    val fuelLabel = stringResource(R.string.vehicle_fuel)
    val doorsLabel = stringResource(R.string.skin_futuristic_doors)
    val closed = stringResource(R.string.skin_futuristic_doors_closed)
    val open = stringResource(R.string.skin_futuristic_door_open)
    val lightsLabel = stringResource(R.string.car_status_lights)
    val lightsOn = stringResource(R.string.car_status_on)
    val lightsOff = stringResource(R.string.car_status_off)
    val list = ArrayList<FutHoloCallout>(4)
    list += FutHoloCallout(
        FutHoloSlot.FRONT, engine,
        listOf(if (d.coolantTempC > 0) units.tempTight(d.coolantTempC) else "--"),
        d.coolantTempC >= COOLANT_WARNING_C
    )
    val tyre = problems.keys.firstNotNullOfOrNull { tyres[it] }
        ?: tyres.filterKeys { it != TyrePos.SPARE }.values.minByOrNull { it.kPa }
    when {
        tyre != null -> list += FutHoloCallout(FutHoloSlot.WHEEL, tyresLabel, listOf(formatPressure(tyre.kPa, pressureUnit)), problems.isNotEmpty())
        d.voltage > 0.0 -> list += FutHoloCallout(
            FutHoloSlot.WHEEL, battery, listOf(String.format(locale, "%.1f V", d.voltage)), d.voltage < 11.8
        )
        revs != null -> list += FutHoloCallout(FutHoloSlot.WHEEL, rpm, listOf(groupThousands(revs)), false)
    }
    if (fuel != null) {
        val range = "${units.distance(fuel.rangeKm)} ${units.distanceUnit}"
        list += FutHoloCallout(
            FutHoloSlot.REAR, fuelLabel, listOf("${fuel.percent}% · $range", "${fuel.percent}%"), fuel.percent <= SKIN_LOW_FUEL_PCT
        )
    } else if (lights != null) {
        // shortcut: the lights only get the rear slot a car without a fuel level leaves free; a fifth slot needs a new layout.
        list += FutHoloCallout(FutHoloSlot.REAR, lightsLabel, listOf(if (lights) lightsOn else lightsOff), false)
    }
    doors?.let { list += FutHoloCallout(FutHoloSlot.ROOF, doorsLabel, listOf(if (it.anyOpen) open else closed), it.anyOpen) }
    return list
}

/**
 * The moving part: the pad (a glowing solid ring with a lit core, a dashed
 * violet ring turning one way and a dotted cyan ring the other, all squashed
 * flat as if lying on the floor), the car floating over it with its double
 * glow and a faint wash of its body, and the scan line sweeping down over
 * both. With effects reduced the car stays put and the dotted ring stills;
 * with effects off nothing moves, and nothing glows by day or with effects off.
 */
@Composable
private fun HoloScene(view: CarView?, glow: FutHoloGlow?, geo: FutHoloGeo, alert: Boolean) {
    val effects = DashColors.Effects
    val loop = rememberLoop(HOLO_LOOP_MS)
    val ink = DashColors.Accent
    val violet = DashColors.Secondary
    val warn = DashColors.Warning
    val light = DashColors.Light
    val glowK = futGlow
    val core = FutCore
    Spacer(
        Modifier
            .fillMaxSize()
            // Its own layer: each step redraws the scene alone, never the callouts or the tile.
            .graphicsLayer()
            .cachedDraw(view, glow, geo, alert, ink, violet, warn, light, glowK, core, effects, loop) {
                val car = geo.car
                val s = car.width / 600f
                val padC = geo.padC
                val r = geo.padR
                val glowing = glowK > 0f && !light
                val lineInk = if (alert) warn else core
                // Widths as the mockup's on a 600 px car, never so thin that the squashed front of a ring vanishes.
                fun px(mockup: Float, least: Float) = max(mockup * s, least.dp.toPx())
                val padRing = Stroke(px(3f, 4f))
                val padGlowWide = Stroke(px(16f, 18f))
                val padGlowMid = Stroke(px(8f, 9f))
                val dashRing = Stroke(px(6f, 7f), pathEffect = PathEffect.dashPathEffect(floatArrayOf(px(16f, 18f), px(10f, 11f))))
                val dotRing = Stroke(px(3f, 4f), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, px(9f, 10f))))
                val padCore = Brush.radialGradient(
                    listOf(ink.copy(alpha = if (light) 0.25f else 0.55f), Color.Transparent), center = padC, radius = r * 0.62f
                )
                val ringInk = ink.copy(alpha = if (light) 0.6f else 0.75f)
                val dashInk = violet.copy(alpha = if (light) 0.6f else 0.8f)
                val dotInk = ink.copy(alpha = if (light) 0.7f else 0.9f)

                // The picture's place and its glows', fixed: the float is a translation.
                fun box(spread: Float): Pair<IntOffset, IntSize> {
                    val dx = car.width * spread
                    val dy = car.height * spread
                    return IntOffset((car.left - dx).roundToInt(), (car.top - dy).roundToInt()) to
                        IntSize((car.width + 2 * dx).roundToInt().coerceAtLeast(1), (car.height + 2 * dy).roundToInt().coerceAtLeast(1))
                }
                val (carOff, carSize) = box(0f)
                val tightBox = glow?.let { box(it.tightSpread) }
                val wideBox = glow?.let { box(it.wideSpread) }
                val tintGlow = ColorFilter.tint(if (alert) warn else ink, BlendMode.SrcIn)
                val tintLine = ColorFilter.tint(lineInk, BlendMode.SrcIn)
                val tintBody = ColorFilter.tint(ink, BlendMode.SrcIn)
                val photoSize = view?.let { IntSize(it.photo.width, it.photo.height) }
                val outlineSize = view?.let { IntSize(it.outline.width, it.outline.height) }
                val tightSize = glow?.let { IntSize(it.tight.width, it.tight.height) }
                val wideSize = glow?.let { IntSize(it.wide.width, it.wide.height) }

                // The drawn car when there is no picture of the driver's own.
                val drawn = if (view == null) FuturisticDrawnCar(car) else null
                val drawnFill = Brush.verticalGradient(listOf(ink.copy(alpha = if (light) 0.08f else 0.16f), Color.Transparent), startY = car.top, endY = car.bottom)
                val u = drawn?.unit ?: 1f
                val drawnLine = Stroke(1.1f * u, join = StrokeJoin.Round)
                val drawnWide = Stroke(3.2f * u, join = StrokeJoin.Round)
                val drawnBloom = Stroke(7f * u, join = StrokeJoin.Round)

                // The scan line: a hot core and a soft band, sweeping from above the car to the pad's front edge.
                val scanTop = car.top - car.height * 0.04f
                val scanBottom = padC.y + r * PAD_SQUASH
                val scanL = car.left - car.width * 0.05f
                val scanW = car.width * 1.1f
                val scanCore = Brush.horizontalGradient(
                    listOf(Color.Transparent, ink, if (light) ink else Color.White, ink, Color.Transparent),
                    startX = scanL, endX = scanL + scanW
                )
                val band = px(11f, 10f)
                val scanBand = Brush.verticalGradient(
                    listOf(Color.Transparent, ink.copy(alpha = 0.35f), Color.Transparent), startY = -band, endY = band
                )
                val coreH = px(3f, 2f)
                val floating = effects == DashEffects.FULL
                val moving = effects != DashEffects.NONE

                onDrawBehind {
                    val t = loop.value

                    withTransform({ scale(1f, PAD_SQUASH, padC) }) {
                        if (glowing) {
                            drawCircle(ink.copy(alpha = 0.10f * glowK), r, padC, style = padGlowWide)
                            drawCircle(ink.copy(alpha = 0.22f * glowK), r, padC, style = padGlowMid)
                        }
                        if (glowK > 0f) drawCircle(padCore, r * 0.62f, padC)
                        drawCircle(ringInk, r, padC, style = padRing)
                        rotate((t * DASH_TURNS * 360f) % 360f, padC) {
                            drawCircle(dashInk, r * 0.84f, padC, style = dashRing)
                        }
                        val back = if (effects == DashEffects.FULL) -(t * DOT_TURNS * 360f) % 360f else 0f
                        rotate(back, padC) {
                            drawCircle(dotInk, r * 0.61f, padC, style = dotRing)
                        }
                    }

                    val bob = if (floating) -geo.bob * (0.5f - 0.5f * cos(TWO_PI * FLOAT_CYCLES * t)) else 0f
                    translate(top = bob) {
                        if (view != null && photoSize != null && outlineSize != null) {
                            if (glowing && glow != null && wideBox != null && tightBox != null && wideSize != null && tightSize != null) {
                                drawImage(glow.wide, IntOffset.Zero, wideSize, wideBox.first, wideBox.second, alpha = 0.55f * glowK, colorFilter = tintGlow)
                                drawImage(glow.tight, IntOffset.Zero, tightSize, tightBox.first, tightBox.second, alpha = 0.95f * glowK, colorFilter = tintGlow)
                            }
                            // The body's volume, faint, under its lines.
                            drawImage(view.photo, IntOffset.Zero, photoSize, carOff, carSize, alpha = if (light) 0.07f else 0.10f, colorFilter = tintBody)
                            // The outline as drawCarView's OUTLINE style draws it, with its tint made once
                            // here: drawCarView builds a new colour filter on each call, and this runs every step.
                            drawImage(view.outline, IntOffset.Zero, outlineSize, carOff, carSize, colorFilter = tintLine)
                        } else if (drawn != null) {
                            withTransform({ scale(-1f, 1f, car.center) }) {
                                drawDrawnCar(drawn, drawnFill, if (alert) warn else ink, lineInk, glowing, glowK, drawnLine, drawnWide, drawnBloom)
                            }
                        }
                    }

                    if (moving) {
                        val p = (t * SCAN_CYCLES) % 1f
                        val e = p * p * (3f - 2f * p)
                        val y = scanTop + (scanBottom - scanTop) * e
                        val a = when {
                            p < 0.1f -> p / 0.1f
                            p > 0.9f -> (1f - p) / 0.1f
                            else -> 1f
                        }
                        if (glowing) translate(top = y) { drawRect(scanBand, Offset(scanL, -band), Size(scanW, band * 2f), alpha = a * glowK) }
                        drawRect(scanCore, Offset(scanL, y - coreH / 2f), Size(scanW, coreH), alpha = a * (if (light) 0.7f else 1f))
                    }
                }
            }
    )
}

/** The drawn car as a hologram: a faint body, its bloom (glowing only), the outline, glass, cuts, wheels and the head lamp. */
private fun DrawScope.drawDrawnCar(
    car: FuturisticDrawnCar, fill: Brush, ink: Color, line: Color, glowing: Boolean, glowK: Float,
    thin: Stroke, wide: Stroke, bloom: Stroke
) {
    drawPath(car.body, fill)
    if (glowing) {
        drawPath(car.body, ink.copy(alpha = 0.10f * glowK), style = bloom)
        drawPath(car.body, ink.copy(alpha = 0.25f * glowK), style = wide)
    }
    drawPath(car.body, line, style = thin)
    drawPath(car.glass, ink.copy(alpha = 0.08f))
    drawPath(car.glass, line.copy(alpha = 0.75f), style = thin)
    drawPath(car.details, line.copy(alpha = 0.5f), style = thin)
    drawPath(car.wheels, line.copy(alpha = 0.8f), style = thin)
    drawPath(car.lamp, line, style = wide)
}

/** One callout laid out: the dot on the car, the elbow of its leader, the end of its shelf and where its text starts. */
private class FutHoloPlaced(val callout: FutHoloCallout, val dot: Offset, val elbow: Offset, val shelfEnd: Offset, val textX: Float, val textW: Float)

/**
 * Callouts to the side of the car their dot is on, two a side at most, each
 * on a shelf: its label above the shelf line, its value under it, the shelf
 * a little above the dot (the second on a side a little below), kept inside
 * the tile and apart from each other.
 */
private fun placeCallouts(items: List<FutHoloCallout>, geo: FutHoloGeo, anchors: FutHoloAnchors, labelH: Float, valueH: Float, gap: Float, edge: Float): List<FutHoloPlaced> {
    val cx = geo.car.center.x
    val dots = items.map { it to geo.car.at(anchors.of(it.slot)) }
    val left = dots.filter { it.second.x < cx }.toMutableList()
    val right = dots.filter { it.second.x >= cx }.toMutableList()
    while (left.size > 2 && right.size < 2) right += left.maxBy { it.second.x }.also { left.remove(it) }
    while (right.size > 2 && left.size < 2) left += right.minBy { it.second.x }.also { right.remove(it) }
    val minY = labelH + gap + edge
    val maxY = max(minY, geo.h - valueH - gap - edge)
    val rowH = labelH + valueH + 2f * gap + edge
    fun side(list: List<Pair<FutHoloCallout, Offset>>, isLeft: Boolean): List<FutHoloPlaced> {
        val sorted = list.sortedBy { it.second.y }.take(2)
        val ys = FloatArray(sorted.size) { i ->
            val dy = sorted[i].second.y
            val want = when {
                sorted.size == 1 -> dy - geo.h * 0.12f
                i == 0 -> dy - geo.h * 0.14f
                else -> dy + geo.h * 0.14f
            }
            want.coerceIn(minY, maxY)
        }
        if (ys.size == 2 && ys[1] - ys[0] < rowH) {
            val mid = (ys[0] + ys[1]) / 2f
            ys[0] = (mid - rowH / 2f).coerceIn(minY, max(minY, maxY - rowH))
            ys[1] = ys[0] + rowH
        }
        val elbowX = if (isLeft) geo.colW + geo.gap * 0.5f else geo.w - geo.colW - geo.gap * 0.5f
        val endX = if (isLeft) edge else geo.w - edge
        val textX = if (isLeft) edge else elbowX + gap
        val textW = if (isLeft) elbowX - edge - gap else geo.w - edge - textX
        return sorted.mapIndexed { i, (c, dot) -> FutHoloPlaced(c, dot, Offset(elbowX, ys[i]), Offset(endX, ys[i]), textX, textW) }
    }
    return side(left, true) + side(right, false)
}

/**
 * The callouts: a dot with a ring on the car, a leader up or down to the
 * side and a shelf under the label, then the label in spaced caps and the
 * value in glowing wide numerals; a reading out of range turns amber.
 */
@Composable
private fun HoloCallouts(items: List<FutHoloCallout>, geo: FutHoloGeo, anchors: FutHoloAnchors) {
    val density = LocalDensity.current
    val colDp = with(density) { geo.colW.toDp() }.value
    val capsDp = (colDp / 11f).coerceIn(11f, 15f)
    val valueDp = (colDp / 8f).coerceIn(14f, 24f)
    val gap = with(density) { 3.dp.toPx() }
    val edge = with(density) { 2.dp.toPx() }
    val labelH = capsDp * 1.25f * density.density
    val valueH = valueDp * 1.08f * density.density
    val placed = remember(items, geo, anchors, labelH, valueH) { placeCallouts(items, geo, anchors, labelH, valueH, gap, edge) }
    val ink = DashColors.Accent
    val warn = DashColors.Warning
    val glowK = futGlow
    val light = DashColors.Light
    Spacer(
        Modifier
            .fillMaxSize()
            .cachedDraw(placed, ink, warn, glowK, light) {
                val line = 1.5.dp.toPx()
                val dotR = 4.5.dp.toPx()
                val ringR = 9.dp.toPx()
                val haloR = 15.dp.toPx()
                val ring = Stroke(line)
                val glowing = glowK > 0f && !light
                onDrawBehind {
                    for (p in placed) {
                        val c = if (p.callout.alert) warn else ink
                        drawLine(c.copy(alpha = 0.9f), p.dot, p.elbow, line)
                        drawLine(c.copy(alpha = 0.9f), p.elbow, p.shelfEnd, line)
                        if (glowing) drawCircle(c.copy(alpha = 0.22f * glowK), haloR, p.dot)
                        drawCircle(c.copy(alpha = 0.45f), ringR, p.dot, style = ring)
                        drawCircle(c, dotR, p.dot)
                    }
                }
            }
    )
    val locale = Locale.getDefault()
    for (p in placed) {
        val c = p.callout
        val textW = with(density) { p.textW.coerceAtLeast(1f).toDp() }
        val valueStyle = futNum(valueDp, if (c.alert) warn else DashColors.TextPrimary, glow = if (c.alert) warn else ink)
        val value = futFirstFitting(c.values, valueStyle, textW)
        Box(
            Modifier
                .offset { IntOffset(p.textX.roundToInt(), (p.elbow.y - labelH - gap).roundToInt()) }
                .width(textW)
        ) {
            Column {
                FutText(c.label.uppercase(locale), futCaps(capsDp), Modifier.height(with(density) { labelH.toDp() }))
                Spacer(Modifier.height(with(density) { (gap * 2f).toDp() }))
                FutText(value, valueStyle)
            }
        }
    }
}
