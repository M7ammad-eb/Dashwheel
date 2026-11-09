package com.openauto.dash

import com.openauto.dash.vehicle.rememberFuelInfo
import com.openauto.dash.vehicle.rememberGaugeInt
import com.openauto.dash.vehicle.rememberSpeedSourceKind
import com.openauto.dash.vehicle.speedSourceWord
import android.content.Context
import android.content.Intent
import android.location.Location
import android.provider.AlarmClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LongState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Vehicle
import com.openauto.dash.vehicle.rememberDisplaySpeedKmh
import com.openauto.dash.vehicle.rememberVehicle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Shared helpers for the whole-design skins: live values (speed, fuel, weather,
 * time, playback), system font families and small formatting bits, so each
 * skin file only has to draw.
 */

/** Condensed sans for dial numerals and engraved labels (Roboto Condensed on Android). */
internal val CondensedFamily: FontFamily =
    FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL))

/**
 * Whether the dashboard page this is composed on can be seen: the current
 * page, or any page while the pages are sliding. The pagers keep the pages
 * beside the current one composed, and their tickers ([rememberWallClock],
 * [rememberLoop], [rememberSpin], [rememberMediaPosition]) would otherwise
 * keep the whole window redrawing for tiles nobody sees. True outside the
 * pages (the bars, sheets, dialogs).
 */
internal val LocalPageActive = compositionLocalOf { true }

/**
 * True while the car moves (DriveLock.kt's [rememberMoving], whatever the drive
 * lock setting). Background motion holds its frame then: movement at the edge
 * of the eye pulls the driver's look off the road. It resumes once the car has
 * stood still for a moment (a red light, parked). Functional motion (needles,
 * clocks, media progress, status blinks) keeps running.
 */
internal val LocalCarMoving = compositionLocalOf { false }

/**
 * Wall-clock milliseconds, updated on each [stepMs] boundary (so a 1 s step
 * ticks exactly on the second). The one ticker every clock, blink and sweep
 * in the skins is built on. It waits for a frame before each step, so it
 * sleeps while the app is in the background, and it stands still on a page
 * off screen ([LocalPageActive]), catching up as soon as the page shows.
 */
@Composable
internal fun rememberWallClock(stepMs: Long): LongState {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val active = LocalPageActive.current
    // The wait below stands still while the unit sleeps: read again at each switch-on.
    val ignition = rememberVehicle(K.IGNITION)
    LaunchedEffect(stepMs, active, ignition) {
        if (!active) return@LaunchedEffect
        now.longValue = System.currentTimeMillis()
        while (true) {
            delay(stepMs - System.currentTimeMillis() % stepMs)
            withFrameMillis { now.longValue = System.currentTimeMillis() }
        }
    }
    return now
}

/** The current time, re-read on each [periodMs] boundary. */
@Composable
internal fun rememberNow(periodMs: Long = 1_000L): Date {
    val clock = rememberWallClock(periodMs)
    val ms = clock.longValue
    return remember(ms) { Date(ms) }
}

/** On for one [halfPeriodMs], off for the next, aligned to the wall clock so several blinkers agree. */
@Composable
internal fun rememberBlink(halfPeriodMs: Long = 500L): State<Boolean> {
    val clock = rememberWallClock(halfPeriodMs)
    return remember(clock, halfPeriodMs) { derivedStateOf { (clock.longValue / halfPeriodMs) % 2L == 0L } }
}

/**
 * A date format for [pattern] used as is ("HH:mm", "EEEE"), or with [best] the
 * locale's own pattern for that skeleton ("EEEdMMM" → "Wed 23 Sep" / "mer. 23 sept.");
 * built once per locale.
 */
@Composable
internal fun rememberDateFormat(pattern: String, best: Boolean = false): SimpleDateFormat {
    val locale = Locale.getDefault()
    val zone = rememberZone()
    return remember(pattern, best, locale, zone) {
        SimpleDateFormat(if (best) android.text.format.DateFormat.getBestDateTimePattern(locale, pattern) else pattern, locale).inZone(zone)
    }
}

/**
 * The smallest text the dashboard draws, in dp: on the head unit's 1280x720
 * at 160 dpi a dp is a pixel, and below 12 a label read at arm's length in
 * a moving car is a grey smudge. Sizes worked out from a tile stop here.
 */
internal const val MIN_TEXT_DP = 12f

/**
 * The smallest spaced capitals ("TRAJET", "KM/H"), in dp: a capital is only
 * about 0.7 of the size tall and has no ascenders to help, so labels set in
 * capitals stop higher than plain text.
 */
internal const val MIN_CAPS_DP = 14f

/** [size] kept at [MIN_TEXT_DP] or above (sp sizes only; em and unspecified pass through). */
internal fun readable(size: TextUnit): TextUnit =
    if (size.isSp && size.value < MIN_TEXT_DP) MIN_TEXT_DP.sp else size

/** [readable] for a label in spaced capitals: [MIN_CAPS_DP] or above. */
internal fun readableCaps(size: TextUnit): TextUnit =
    if (size.isSp && size.value < MIN_CAPS_DP) MIN_CAPS_DP.sp else size

/**
 * Text size that follows a dp geometry whatever the system font scale, so text sized from a tile never outgrows it;
 * never under [MIN_TEXT_DP].
 */
@Composable
internal fun fixedSp(dp: Float): TextUnit = (max(dp, MIN_TEXT_DP) / LocalDensity.current.fontScale).sp

/** [fixedSp] for a [Dp]. */
@Composable
internal fun Dp.fixedSp(): TextUnit = fixedSp(value)

/** "2 400": thousands split by [separator] (a narrow no-break space unless a skin sets its own). */
internal fun groupThousands(n: Int, separator: Char = '\u202F'): String =
    if (n < 1000) "$n" else "${n / 1000}$separator${(n % 1000).toString().padStart(3, '0')}"

/**
 * Where a speed readout comes from, named from the resolved speed's source ([speedSourceWord]: "OBD", "GPS" or "CAR"),
 * or the skin's own [none] wording when there is no speed.
 */
@Composable
internal fun speedSource(none: String): String = speedSourceWord(rememberSpeedSourceKind()) ?: none

/** Fuel at or under this share (%) reads as the reserve in every skin. */
internal const val SKIN_LOW_FUEL_PCT = 12

/** Top of the skins' rev scales (the 1.6 HDi's tachometer), in r/min. */
internal const val SKIN_RPM_MAX = 7000f

/** The red line taken when the car's profile has none: a diesel's. */
internal const val DEFAULT_REDLINE_RPM = 4_500

/**
 * A rev scale for the car on the dashboard: where it tops out and where it
 * turns red, from the profile's red line. The top stays at [SKIN_RPM_MAX]
 * until a red line comes within a tenth of it; a petrol engine's 7 500 then
 * gets a scale to 9 000 instead of a needle pinned at the end.
 */
@androidx.compose.runtime.Immutable
internal data class RevScale(val top: Float, val redFrom: Float) {
    /** [rpm] along the scale, 0 to 1. */
    fun fraction(rpm: Number?): Float = rpm?.let { (it.toFloat() / top).coerceIn(0f, 1f) } ?: 0f
}

internal fun revScale(redlineRpm: Int?): RevScale {
    val redline = redlineRpm?.takeIf { it > 0 } ?: DEFAULT_REDLINE_RPM
    val top = if (redline * 1.1f <= SKIN_RPM_MAX) SKIN_RPM_MAX else kotlin.math.ceil(redline * 1.1f / 1000f) * 1000f
    return RevScale(top, (redline / top).coerceIn(0.1f, 1f))
}

/** The car's rev scale as state: it follows the profile when the red line is typed in or fetched. */
@Composable
internal fun rememberRevScale(): RevScale {
    val profile by CarProfileStore.profile.collectAsState()
    return remember(profile.redlineRpm) { revScale(profile.redlineRpm) }
}

// --- Effects ----------------------------------------------------------------------
//
// The effects setting (DashColors.Effects) decides how much decoration the skins
// draw. FULL is the designed look; REDUCED halves the lamp halos, drops blurred
// text glows and runs background motion at half the rate; NONE is still: no
// ambient motion, no lamp halos, no blur. Functional motion (needles on live
// values, media progress, clocks, status blinks) runs whatever the setting.

/** Ambient motion's step in ms for [effects]: about 20 fps at full, 10 when reduced, none (a still frame) when off. */
/** Status pulses step at this rate whatever the effects setting. */
private const val STATUS_STEP_MS = 50L

private fun ambientStepMs(effects: DashEffects): Long? = when (effects) {
    DashEffects.FULL -> 50L
    DashEffects.REDUCED -> 100L
    DashEffects.NONE -> null
}

/**
 * Wall-clock ms for background motion, advanced every [stepMs] (all ambient
 * tickers share the same boundaries, so they land in one frame). It waits
 * for a frame before each step, so it sleeps while the app is in the
 * background, and it holds on a page off screen ([LocalPageActive]) and,
 * when [holdWhileMoving], while the car moves ([LocalCarMoving]).
 */
@Composable
private fun rememberAmbientClock(stepMs: Long, holdWhileMoving: Boolean): LongState {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val active = LocalPageActive.current
    val held = holdWhileMoving && LocalCarMoving.current
    LaunchedEffect(stepMs, active, held) {
        if (!active || held) return@LaunchedEffect
        while (true) {
            delay(stepMs - System.currentTimeMillis() % stepMs)
            withFrameMillis { now.longValue = System.currentTimeMillis() }
        }
    }
    return now
}

/**
 * A text glow or soft shadow of [color] blurred [blurRadius] px, only with
 * effects at full: a blurred text shadow is costly on the head unit. Reduced
 * keeps an offset shadow crisp (it still lifts text off a busy page) and drops
 * a pure glow; with effects off there is none.
 */
internal fun softTextShadow(color: Color, blurRadius: Float, offset: Offset = Offset.Zero): Shadow? = when (DashColors.Effects) {
    DashEffects.FULL -> Shadow(color, offset, blurRadius)
    DashEffects.REDUCED -> if (offset != Offset.Zero) Shadow(color, offset, 0f) else null
    DashEffects.NONE -> null
}

/**
 * A soft round halo of [color] behind the content, centred, its radius [reach]
 * times the box's smaller side, fading to [fade]. Half as strong with reduced
 * effects, gone with effects off. The gradient is built once per size.
 */
internal fun Modifier.glowHalo(color: Color, reach: Float, fade: Color = Color.Transparent): Modifier =
    this then GlowHaloElement(color, reach, fade)

private data class GlowHaloElement(val color: Color, val reach: Float, val fade: Color) : ModifierNodeElement<GlowHaloNode>() {
    override fun create() = GlowHaloNode(color, reach, fade)

    override fun update(node: GlowHaloNode) {
        node.color = color
        node.reach = reach
        node.fade = fade
        node.brush = null
        node.invalidateDraw()
    }
}

private class GlowHaloNode(var color: Color, var reach: Float, var fade: Color) : Modifier.Node(), DrawModifierNode {
    var brush: Brush? = null
    private var brushSize = Size.Unspecified
    private var brushScale = -1f

    override fun ContentDrawScope.draw() {
        val scale = DashColors.Effects.scale
        if (scale > 0f) {
            val r = size.minDimension * reach
            val cached = brush
            val b = if (cached != null && brushSize == size && brushScale == scale) cached else {
                Brush.radialGradient(listOf(color.copy(alpha = color.alpha * scale), fade), center, r).also {
                    brush = it
                    brushSize = size
                    brushScale = scale
                }
            }
            drawCircle(b, r)
        }
        drawContent()
    }
}

/**
 * [drawWithCache] that survives recomposition: the cache is only rebuilt when
 * one of [keys] changes (or the size, or a state read inside [block]). Every
 * value [block] captures from the composition must be one of the [keys].
 */
@Composable
internal fun Modifier.cachedDraw(vararg keys: Any?, block: CacheDrawScope.() -> DrawResult): Modifier =
    this then remember(*keys) { Modifier.drawWithCache(block) }

/**
 * The speed to show, in km/h: the car's (OBD, car app, unit bus), else a GPS fix
 * under 5 s old, else null; with the driver's correction ([rememberDisplaySpeedKmh]).
 * Keeps the GPS feed running while on screen.
 */
@Composable
internal fun rememberSpeedKmh(): Int? {
    UseLocationFeed()
    return rememberDisplaySpeedKmh()
}

/** Whether [rememberSpeedKmh] has a speed to give, without following the speed itself (it changes at every reading). */
@Composable
internal fun rememberHasSpeed(): Boolean {
    UseLocationFeed()
    val has by remember { Vehicle.bus.value(K.SPEED).map { it != null }.distinctUntilChanged() }
        .collectAsState(initial = remember { Vehicle.bus.now(K.SPEED) != null })
    return has
}

/**
 * The car's position as [LocationFeed.cell] gives it: it only moves when the
 * car leaves its cell of 1/[perDegree] degree, so a tile keyed on that
 * rounding doesn't redraw on each GPS fix (one a second). Doesn't start the
 * GPS itself ([UseLocationFeed]).
 */
@Composable
internal fun rememberLocationCell(vararg perDegree: Int): State<Location?> =
    remember { LocationFeed.cell(*perDegree) }.collectAsState(initial = remember { LocationFeed.location.value })

/** Keeps the forecast at the car fresh while composed, the way the standard weather tile does; reads nothing. */
@Composable
internal fun KeepWeatherFresh() {
    UseLocationFeed()
    // The fetch is keyed on the position to 1/20 degree: nothing else of it is read.
    val location by rememberLocationCell(20)
    LaunchedEffect(location?.latitude?.let { (it * 20).roundToInt() }, location?.longitude?.let { (it * 20).roundToInt() }) {
        val l = location ?: return@LaunchedEffect
        while (true) {
            WeatherRepo.refresh(l.latitude, l.longitude)
            delay(60_000)
        }
    }
}

/** Weather at the car, refreshed the way the standard weather tile does it ([KeepWeatherFresh]); null until the first fetch. */
@Composable
internal fun rememberWeather(): Weather? {
    KeepWeatherFresh()
    val weather by WeatherRepo.weather.collectAsState()
    return weather
}

/**
 * The outside temperature in °C ([K.AMBIENT_TEMP]): the car's own sensor when it sends one
 * (offline too), else the forecast's; null until either is known. The forecast is kept fresh
 * for as long as this is composed, since it is what shows when the car says nothing.
 */
@Composable
internal fun rememberOutsideTempC(): Double? {
    KeepWeatherFresh()
    return rememberVehicle(K.AMBIENT_TEMP)
}

/** Why [rememberWeather] has nothing yet, so a skin says "no GPS" or "offline" in its own words and not "loading" all drive. */
@Composable
internal fun rememberWeatherWait(): WeatherWait {
    val hasFix by LocationFeed.hasFix.collectAsState()
    val error by WeatherRepo.error.collectAsState()
    return weatherWait(hasFix, error)
}

/**
 * The fuel level ([K.FUEL_LEVEL]: the learned CANbox byte, else the car app's, else the OBD PID, by
 * rank) with the car's own distance to empty ([K.RANGE]) when it is known; null when none of them is
 * (the standard [RangeCard] explains how to learn them). Keeps the CANbox
 * stream running while on screen.
 */
@Composable
internal fun rememberFuel(): FuelInfo? {
    DisposableEffect(Unit) {
        McuReader.start()
        onDispose { McuReader.stop() }
    }
    return rememberFuelInfo()
}

/** Playback progress 0..1, and 0 while the duration is unknown. */
@Composable
internal fun rememberMediaFraction(mediaState: MediaState, controller: CarMediaController): Float {
    val positionMs = rememberMediaPosition(mediaState, controller)
    return if (mediaState.durationMs > 0L) (positionMs.toFloat() / mediaState.durationMs).coerceIn(0f, 1f) else 0f
}

/** "2:14" style track time. */
internal fun formatTrackTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** Label under an app icon: the app's name, or the last part of its package while apps load. */
internal fun appLabel(app: AppEntry?, packageName: String): String = app?.label ?: packageName.substringAfterLast('.')

/** Opens the clock / alarms app, as tapping the standard clock tile does. */
internal fun openClockApp(context: Context) {
    runCatching {
        context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Rotation in degrees that keeps turning while [running] and holds its angle
 * when paused (a record or reel that stops where it is). Read it in a draw or
 * graphicsLayer lambda so only drawing reruns each step. Ambient motion: it
 * steps at the effects setting's rate, stands still with effects off, and
 * holds on a page off screen ([LocalPageActive]) or while the car moves.
 */
@Composable
internal fun rememberSpin(periodMs: Int, running: Boolean = true): State<Float> {
    val angle = remember { mutableFloatStateOf(0f) }
    val step = ambientStepMs(DashColors.Effects)
    val active = LocalPageActive.current
    val held = LocalCarMoving.current
    LaunchedEffect(running, periodMs, step, active, held) {
        if (!running || !active || held || step == null) return@LaunchedEffect
        var last = System.currentTimeMillis()
        while (true) {
            delay(step - System.currentTimeMillis() % step)
            withFrameMillis {
                val now = System.currentTimeMillis()
                angle.floatValue = (angle.floatValue + (now - last) * 360f / periodMs) % 360f
                last = now
            }
        }
    }
    return angle
}

/**
 * 0→1 looping every [periodMs] (restarting, or reversing when [reverse]), for
 * background motion, in phase with the wall clock. It steps at the effects
 * setting's rate; with effects off it holds at [rest]. A [status] loop (a
 * link being made) tells the driver something, so it always runs.
 */
@Composable
internal fun rememberLoop(
    periodMs: Int,
    reverse: Boolean = false,
    rest: Float = if (reverse) 1f else 0f,
    status: Boolean = false
): State<Float> {
    val step = (if (status) STATUS_STEP_MS else ambientStepMs(DashColors.Effects))
        ?: return remember(rest) { mutableFloatStateOf(rest) }
    val clock = rememberAmbientClock(step, holdWhileMoving = !status)
    return remember(clock, periodMs, reverse) {
        derivedStateOf {
            if (reverse) {
                val p = (clock.longValue % (2L * periodMs)).toFloat() / periodMs
                if (p > 1f) 2f - p else p
            } else {
                (clock.longValue % periodMs).toFloat() / periodMs
            }
        }
    }
}
