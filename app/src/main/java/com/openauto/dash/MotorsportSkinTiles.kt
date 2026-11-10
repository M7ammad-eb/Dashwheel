package com.openauto.dash

import com.openauto.dash.vehicle.any
import com.openauto.dash.vehicle.rememberDoors
import com.openauto.dash.vehicle.rememberPanelGauges
import com.openauto.dash.vehicle.K
import android.location.Location
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LongState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openauto.dash.carphoto.CarPart
import com.openauto.dash.link.DriveSummary
import com.openauto.dash.vehicle.rememberTyres
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The Motorsport skin's tiles (MotorsportSkin.kt has the page, the bar and the
 * shared parts). Every tile sizes its type from its own box, from a 3 x 2 grid
 * cell to the full screen, upright or not, and drops its extras before it
 * would crowd them.
 */

private const val DOT = " · "

// --- Driver display (SPEED_HUD) -----------------------------------------------------

/** Blocks in the rev bar under the speed, as on a wheel display. */
private const val REV_BLOCKS = 14

/**
 * The steering-wheel display: an LCD gear panel (the gear the app works out
 * from revs and speed, lit over a faint unlit "8") beside the speed in big
 * condensed italic, the revs as a segmented bar and, on a tall tile while
 * driving a known commute with a route, the gap to the usual time. Upright
 * tiles stack the LCD over the speed. With no speed at all the tile connects
 * the adapter.
 */
@Composable
internal fun MsDriverDisplay(env: SkinTileEnv) {
    val speed = rememberSpeedKmh()
    val gear = rememberGear()
    val vs = rememberVsUsual()
    val idle = env.obdConnection.isIdle
    val obd = env.obdConnection == ObdConnectionState.CONNECTED
    val canConnect = speed == null && idle && !env.editing
    val profile by CarProfileStore.profile.collectAsState()
    val redline = profile.redlineRpm?.takeIf { it > 0 } ?: DEFAULT_REDLINE_RPM
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .msPanel()
            .msTap(canConnect, stringResource(R.string.skin_motorsport_connect_obd), env.onConnectObd)
    ) {
        val w = maxWidth
        val h = maxHeight
        val pad = (min(w.value, h.value) * 0.06f).coerceIn(10f, 20f).dp
        val tall = h > w * 1.15f
        if (tall) {
            Column(Modifier.fillMaxSize().padding(pad)) {
                LcdGear(gear.value.gear, Modifier.fillMaxWidth().weight(0.44f))
                Spacer(Modifier.height(pad))
                SpeedBlock(speed, obd, gear, redline, vs, idle, Modifier.fillMaxWidth().weight(0.56f))
            }
        } else {
            Row(Modifier.fillMaxSize().padding(pad)) {
                LcdGear(gear.value.gear, Modifier.fillMaxHeight().width((w - pad * 2) * 0.42f))
                Spacer(Modifier.width(pad))
                SpeedBlock(speed, obd, gear, redline, vs, idle, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/**
 * The gear on an LCD: a faint unlit "8" with the lit gear over it, glowing
 * yellow at night (dark segments on a grey-green reflector by day), amber in
 * reverse, dim when there is no gear to tell; "GEAR" under it where there is
 * room.
 */
@Composable
private fun LcdGear(gear: Gear, modifier: Modifier) {
    val glyph = gearGlyph(gear)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val label = stringResource(R.string.skin_motorsport_gear_caps)
    val ink = when (gear) {
        Gear.Reverse -> DashColors.Warning
        is Gear.Forward -> MsLcdInk
        else -> MsLcdInk.copy(alpha = 0.6f)
    }
    val light = DashColors.Light
    BoxWithConstraints(
        modifier
            .semantics { contentDescription = "$label $glyph" }
            .cachedDraw(light) {
                val r = CornerRadius(12.dp.toPx())
                val back = MsLcdBack
                val rim = MsLcdRim
                val rimStroke = Stroke(2.dp.toPx())
                val glow = msGlow
                val yellow = Color(0xFFFFD400)
                val innerA = Stroke(16.dp.toPx())
                val innerB = Stroke(6.dp.toPx())
                val reflection = Brush.linearGradient(
                    0f to Color.White.copy(alpha = if (light) 0.35f else 0.05f), 0.4f to Color.Transparent,
                    start = Offset.Zero, end = Offset(size.width, size.height)
                )
                onDrawBehind {
                    drawRoundRect(back, cornerRadius = r)
                    if (glow > 0f && !light) {
                        drawRoundRect(yellow.copy(alpha = 0.035f * glow), cornerRadius = r, style = innerA)
                        drawRoundRect(yellow.copy(alpha = 0.06f * glow), cornerRadius = r, style = innerB)
                    }
                    drawRoundRect(reflection, cornerRadius = r)
                    drawRoundRect(rim, Offset(1.dp.toPx(), 1.dp.toPx()), Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()), CornerRadius(r.x - 1.dp.toPx()), style = rimStroke)
                }
            }
    ) {
        val showLabel = maxHeight >= 120.dp
        val labelSp = (maxHeight.value * 0.06f).coerceIn(11f, 16f)
        val labelRoom = if (showLabel) labelSp * 1.6f + 8f else 0f
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { (maxHeight - labelRoom.dp).toPx() }
        // A condensed digit is about 0.72 of the size tall and 0.56 wide.
        val fsPx = min(hPx * 0.8f / 0.72f, wPx * 0.78f / 0.56f).coerceAtLeast(8f)
        val style = TextStyle(
            fontFamily = CondensedFamily,
            fontWeight = FontWeight.Black,
            fontSize = with(density) { fsPx.toSp() }
        )
        val ghost = remember(style, measurer) { measurer.measure("8", style) }
        val litStyle = style.copy(shadow = if (light) null else softTextShadow(ink.copy(alpha = 0.7f), fsPx * 0.09f))
        val lit = remember(glyph, litStyle, measurer) { measurer.measure(glyph, litStyle) }
        val ghostInk = if (light) Color(0xFF1E2410).copy(alpha = 0.08f) else Color(0xFFFFD400).copy(alpha = 0.07f)
        Box(
            Modifier.fillMaxSize().cachedDraw(ghost, lit, ink, ghostInk, hPx, fsPx) {
                // Centre each glyph's digit body (cap height ~0.71 of the size) in the room above the label.
                fun topOf(base: Float) = hPx / 2f - (base - fsPx * 0.355f)
                onDrawBehind {
                    drawText(ghost, color = ghostInk, topLeft = Offset((size.width - ghost.size.width) / 2f, topOf(ghost.firstBaseline)))
                    drawText(lit, color = ink, topLeft = Offset((size.width - lit.size.width) / 2f, topOf(lit.firstBaseline)))
                }
            }
        )
        if (showLabel) {
            MsText(
                label,
                msCaps(fixedSp(labelSp), MsLcdLabel),
                Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
            )
        }
    }
}

/** SPEED and its source, the speed, its unit, then the revs and the gap to the usual time where there is room. */
@Composable
private fun SpeedBlock(speed: Int?, obd: Boolean, gear: State<GearReading>, redline: Int, vs: Int?, idle: Boolean, modifier: Modifier) {
    val units = LocalUnits.current
    BoxWithConstraints(modifier) {
        val h = maxHeight.value
        val capsSp = (h * 0.065f).coerceIn(11f, 16f)
        val capsH = capsSp * 1.3f
        val barH = (h * 0.075f).coerceIn(10f, 24f)
        val showRpm = h >= 110f
        val rpmH = if (showRpm) capsH + 6f + barH + 8f else 0f
        val showVs = vs != null && h >= 240f
        val vsH = if (showVs) (h * 0.24f).coerceIn(58f, 100f) else 0f
        val showCaps = h >= 150f
        val unitSp = (capsSp * 1.1f).coerceIn(12f, 18f)
        val numH = h - rpmH - vsH - (if (showCaps) capsH else 0f) - unitSp * 1.3f - 8f
        val over = speedOver(speed)
        val color = when {
            speed == null -> DashColors.Muted
            over -> DashColors.Warning
            else -> DashColors.TextPrimary
        }
        val numSp = msFitSp("188", msHero(100.sp, color), maxWidth, numH.coerceAtLeast(20f).dp, 22f, 280f)
        val noSignal = stringResource(R.string.info_speed_no_signal).uppercase()
        val source = speedSource(if (idle) stringResource(R.string.skin_motorsport_tap_connect_caps) else noSignal)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                if (showCaps) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        MsText(stringResource(R.string.skin_motorsport_speed_caps), msCaps(fixedSp(capsSp)))
                        Spacer(Modifier.weight(1f))
                        MsText(source, msCaps(fixedSp(capsSp * 0.9f), if (speed != null) DashColors.Good else DashColors.Muted))
                    }
                }
                MsText(speed?.let { units.speed(it).toString() } ?: "–", msHero(numSp.sp, color, glow = speed != null), clip = true)
                MsText(units.speedUnit, msBody(fixedSp(unitSp), DashColors.Muted))
            }
            if (showRpm) {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        MsText(stringResource(R.string.skin_motorsport_rpm_caps), msCaps(fixedSp(capsSp)))
                        Spacer(Modifier.weight(1f))
                        val rpm = gear.value.rpm
                        MsText(rpm?.toString() ?: "----", msHero(fixedSp(capsSp * 1.45f), if (rpm != null) DashColors.TextPrimary else DashColors.Muted))
                    }
                    Spacer(Modifier.height(6.dp))
                    // The last blocks past 78 % of the red line are red (the red line itself ends the bar).
                    val redFrom = (0.78f * REV_BLOCKS).roundToInt().coerceIn(1, REV_BLOCKS)
                    val lit = remember(gear, redline) {
                        { gear.value.rpm?.let { (it.toFloat() / redline * REV_BLOCKS).toInt().coerceIn(0, REV_BLOCKS) } ?: 0 }
                    }
                    MsSegments(REV_BLOCKS, redFrom, lit, Modifier.fillMaxWidth().height(barH.dp))
                }
            }
            if (showVs) VsUsualBox(vs, Modifier.fillMaxWidth().height(vsH.dp))
        }
    }
}

/** "VS USUAL" over the gap in minutes: green when this drive is quicker, plain when slower. */
@Composable
private fun VsUsualBox(deltaMin: Int, modifier: Modifier) {
    val ahead = deltaMin <= 0
    val tint = if (ahead) DashColors.Good else DashColors.TextSecondary
    val sign = when {
        deltaMin < 0 -> "−"
        deltaMin > 0 -> "+"
        else -> "±"
    }
    val value = sign + stringResource(R.string.info_map_duration_min, abs(deltaMin))
    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.1f))
            .border(1.dp, tint.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        val capsSp = (maxHeight.value * 0.2f).coerceIn(10f, 14f)
        val valueSp = msFitSp(value, msHero(100.sp, tint), maxWidth, (maxHeight.value - capsSp * 1.3f).coerceAtLeast(14f).dp, 14f, 48f)
        Column {
            MsText(stringResource(R.string.skin_motorsport_vs_usual_caps), msCaps(fixedSp(capsSp), tint.copy(alpha = 0.8f)))
            MsText(value, msHero(valueSp.sp, if (ahead) DashColors.Good else DashColors.TextPrimary), clip = true)
        }
    }
}

/**
 * Minutes this drive will take against the usual for the same trip, or null
 * when that can't be told: it needs a drive under way, a route with an
 * arrival and the distance left, and home or work as the place the route
 * leads to with a few drives there logged. The place is the one whose
 * straight-line distance from the car fits the distance the route has left.
 */
@Composable
private fun rememberVsUsual(): Int? {
    val nav by NavDirections.state.collectAsState()
    val drive by DriveLog.current.collectAsState()
    val drives by DriveLog.drives.collectAsState()
    val places by PlacesStore.places.collectAsState()
    // Only the position to 1/200 degree is a key: the tile doesn't follow each fix.
    val here by rememberLocationCell(200)
    val now = rememberNow(60_000L).time
    val cell = here?.let { (it.latitude * 200).roundToInt() to (it.longitude * 200).roundToInt() }
    return remember(nav.active, nav.eta, drive?.startedAt, drives, places, cell, now) {
        // Worked out from the latest fix, not the one that entered the cell.
        if (!nav.active) null else vsUsualMinutes(nav.eta, now, drive?.startedAt, places, drives, LocationFeed.location.value ?: here)
    }
}

private fun vsUsualMinutes(eta: String, now: Long, startedAt: Long?, places: Places, drives: List<DriveSummary>, here: Location?): Int? {
    val start = startedAt ?: return null
    val at = here ?: return null
    val arrival = arrivalMs(eta, now) ?: return null
    val leftKm = kmLeft(eta) ?: return null
    val total = ((arrival - start) / 60_000L).toInt()
    val place = listOfNotNull(places.home, places.work)
        .map { p ->
            val out = FloatArray(1)
            Location.distanceBetween(at.latitude, at.longitude, p.lat, p.lng, out)
            p to out[0] / 1000f
        }
        // Roads run longer than the crow flies, but not three times longer nor shorter.
        .filter { (_, d) -> d <= leftKm * 1.05f + 0.3f && d >= leftKm / 2.5f }
        .minByOrNull { (_, d) -> abs(leftKm / d.coerceAtLeast(0.1f) - 1.3f) }
        ?.first ?: return null
    val usual = Commutes.to(drives, place.lat, place.lng).takeIf { it.minutes.size >= 2 }?.average ?: return null
    return total - usual
}

/** Kilometres left in an ETA line ("12 min · 6.4 km · 09:48"), null when it gives none. */
private fun kmLeft(eta: String): Float? {
    val m = NavState.DISTANCE.find(eta) ?: return null
    val v = m.groupValues[1].replace(',', '.').toFloatOrNull() ?: return null
    return when (m.groupValues[2].lowercase(Locale.ROOT)) {
        "km" -> v
        "m" -> v / 1000f
        "mi" -> v * 1.609344f
        "ft" -> v * 0.0003048f
        "yd" -> v * 0.0009144f
        else -> null
    }
}

// --- Car from above (TELEMETRY) -----------------------------------------------------

/** One reading at a wheel: its value, a small label and the state colour. */
private class MsChip(val value: String, val label: String, val tint: Color)

/** The drawn car's own box (DoorAlertOverlay's drawCar): the car with its mirrors is 98 x 190 in a 140 x 260 space. */
private const val CAR_W = 98f
private const val CAR_H = 190f
private const val CAR_X = 21f
private const val CAR_Y = 35f

/**
 * The car from above on a lit pad inside two dashed range rings: the driver's
 * own car when it has a view from above (MyCarLook), else a drawn one, its
 * open doors shown. At each wheel a chip: the tyre's pressure when the car
 * reports its tyres (green, amber when low, high or hot, red for a leak),
 * else coolant, battery, intake air and load from the adapter. A line under
 * it carries the rest, or how to connect; LIVE beside it picks another
 * adapter.
 */
@Composable
internal fun MsTelemetry(env: SkinTileEnv) {
    val tyres = rememberTyres()
    val problems by Tyres.problems.collectAsState()
    val pUnit by Tyres.unit.collectAsState()
    val doors = rememberDoors()
    val mine by MyCarLook.shown.collectAsState()
    val carStyle by MyCarLook.style.collectAsState()
    val state = env.obdConnection
    val connected = state == ObdConnectionState.CONNECTED
    val idle = state.isIdle
    // Whatever the car answers is shown, adapter or not; the rest reads "--".
    val g = rememberPanelGauges(K.SPEED, K.RPM, K.COOLANT_TEMP, K.INTAKE_TEMP, K.ENGINE_LOAD, K.BATTERY_VOLTS)
    val live = connected || g.any
    val units = LocalUnits.current
    val positions = listOf(TyrePos.FRONT_LEFT, TyrePos.FRONT_RIGHT, TyrePos.REAR_LEFT, TyrePos.REAR_RIGHT)
    val now = SystemClock.elapsedRealtime()
    val fresh = tyres.filterValues { now - it.at <= TYRE_STALE_MS }
    val hasTyres = positions.any { it in fresh }
    val posLabels = listOf(
        stringResource(R.string.skin_motorsport_tyre_fl), stringResource(R.string.skin_motorsport_tyre_fr),
        stringResource(R.string.skin_motorsport_tyre_rl), stringResource(R.string.skin_motorsport_tyre_rr)
    )
    val water = stringResource(R.string.skin_motorsport_water_caps)
    val batt = stringResource(R.string.skin_motorsport_batt_caps)
    val air = stringResource(R.string.skin_motorsport_air_caps)
    val load = stringResource(R.string.skin_motorsport_load_caps)
    val coolantC = g.coolantTempC?.takeIf { it > 0 }
    val voltage = g.voltage?.takeIf { it > 0.0 }
    val coolant = coolantC?.let { "${units.temp(it)}°" }
    val volts = voltage?.let { String.format(Locale.getDefault(), "%.1f V", it) }
    val muted = DashColors.Muted
    val chips = if (hasTyres) {
        positions.mapIndexed { i, pos ->
            val t = fresh[pos]
            val problem = problems[pos]
            when {
                t == null || t.noSignal -> MsChip("--", posLabels[i], muted)
                else -> MsChip(
                    pressureNumber(t.kPa, pUnit),
                    "${pressureWord(pUnit)} ${posLabels[i]}",
                    when (problem) {
                        null -> DashColors.Good
                        TyreProblem.LEAK -> DashColors.Critical
                        else -> DashColors.Warning
                    }
                )
            }
        }
    } else {
        listOf(
            MsChip(
                coolant ?: "--", water,
                when {
                    coolant == null -> muted
                    (coolantC ?: 0) >= 115 -> DashColors.Critical
                    (coolantC ?: 0) >= 105 -> DashColors.Warning
                    else -> DashColors.Good
                }
            ),
            MsChip(
                volts?.replace(" ", "") ?: "--", batt,
                when {
                    volts == null -> muted
                    (voltage ?: 0.0) < 11.8 || (voltage ?: 0.0) > 15.2 -> DashColors.Warning
                    else -> DashColors.Good
                }
            ),
            MsChip(g.intakeTempC?.let { "${units.temp(it)}°" } ?: "--", air, if (g.intakeTempC != null) DashColors.Good else muted),
            MsChip(g.engineLoadPct?.let { "$it%" } ?: "--", load, if (g.engineLoadPct != null) DashColors.Good else muted)
        )
    }
    val caption = when {
        live -> if (hasTyres) {
            listOfNotNull(coolant?.let { "$water $it" }, volts?.let { "$batt $it" }).joinToString(DOT)
        } else {
            listOfNotNull(
                g.rpm?.let { "$it ${stringResource(R.string.skin_motorsport_rpm_caps)}" },
                g.speedKmh?.let { units.speedText(it).uppercase() }
            ).joinToString(DOT)
        }
        state == ObdConnectionState.CONNECTING -> stringResource(R.string.skin_motorsport_connecting_caps)
        state == ObdConnectionState.ERROR -> stringResource(R.string.skin_motorsport_obd_error_caps)
        else -> stringResource(R.string.skin_motorsport_tap_connect_caps)
    }
    val connectLabel = stringResource(R.string.skin_motorsport_connect_obd)
    val chooseAdapter = stringResource(R.string.skin_motorsport_choose_adapter)
    val ownTop = mine?.top
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .msPanel(MsPanel.PAD)
            .msTap(idle && !env.editing, connectLabel, env.onConnectObd)
            .semantics(mergeDescendants = true) {}
    ) {
        val w = maxWidth.value
        val h = maxHeight.value
        val pad = 10f
        val captionSp = (min(w, h) * 0.05f).coerceIn(11f, 15f)
        val showCaption = h >= 150f
        // Room for LIVE as a 48 dp target while linked.
        val captionH = if (showCaption) max(captionSp * 1.5f + 14f, if (connected) 48f else 0f) else 0f
        val areaTop = pad
        val areaBottom = h - pad - captionH
        val areaH = (areaBottom - areaTop).coerceAtLeast(40f)
        val chipW = (w * 0.22f).coerceIn(54f, 92f)
        val chipH = (chipW * 0.66f).coerceAtMost(areaH / 2f - 3f).coerceAtLeast(30f)
        val carMaxW = (w - (chipW + pad) * 2f - 10f).coerceAtLeast(30f)
        val aspect = ownTop?.aspect ?: (CAR_W / CAR_H)
        val carH = min(areaH * 0.94f, carMaxW / aspect)
        val carW = carH * aspect
        val carTop = areaTop + (areaH - carH) / 2f
        val frontY = ownTop?.wheels?.getOrNull(0)?.y ?: ((77f - CAR_Y) / CAR_H)
        val rearY = ownTop?.wheels?.getOrNull(2)?.y ?: ((187f - CAR_Y) / CAR_H)
        fun chipTop(frac: Float) = (carTop + carH * frac - chipH / 2f).coerceIn(areaTop, areaBottom - chipH)
        val frontTop = chipTop(frontY)
        val rearTop = max(chipTop(rearY), frontTop + chipH + 4f).coerceAtMost(areaBottom - chipH)
        val light = DashColors.Light

        // Pad rings, the car's shadow and the car: their own layer, redrawn only when the car or the doors change.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .cachedDraw(w, h, carTop, carH, carW, areaTop, areaH, ownTop, carStyle, doors, light) {
                    val dp = density
                    val cx = size.width / 2f
                    val cy = (areaTop + areaH / 2f) * dp
                    val ringR = min(areaH, w) * 0.46f * dp
                    val ringInk = DashColors.TextPrimary
                    val dash = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx())))
                    val top = carTop * dp
                    val ch = carH * dp
                    val cw = carW * dp
                    val shadowR = cw * 0.8f
                    val shadow = Brush.radialGradient(listOf(msShade(0.75f), Color.Transparent), Offset(cx, top + ch * 0.56f), shadowR)
                    val warn = DashColors.Warning
                    val open = doors
                    fun f(b: Boolean?) = if (b == true) 1f else 0f
                    onDrawBehind {
                        drawCircle(ringInk.copy(alpha = 0.14f), ringR, Offset(cx, cy), style = dash)
                        drawCircle(ringInk.copy(alpha = 0.10f), ringR * 0.6f, Offset(cx, cy), style = dash)
                        scale(1f, ch / (cw * 1.6f), Offset(cx, top + ch * 0.56f)) {
                            drawCircle(shadow, shadowR, Offset(cx, top + ch * 0.56f))
                        }
                        if (ownTop != null) {
                            val openParts = if (open == null) emptyMap() else mapOf(
                                CarPart.FRONT_LEFT to f(open.frontLeft), CarPart.FRONT_RIGHT to f(open.frontRight),
                                CarPart.REAR_LEFT to f(open.rearLeft), CarPart.REAR_RIGHT to f(open.rearRight),
                                CarPart.TAILGATE to f(open.tailgate), CarPart.BONNET to f(open.bonnet)
                            )
                            drawCarView(ownTop, carStyle, Rect(cx - cw / 2f, top, cx + cw / 2f, top + ch), DashColors.TextPrimary, warn, open = openParts)
                        } else {
                            val s = ch / CAR_H
                            val body = msTone(Color(0xFF2B2B31), Color(0xFFFFFFFF))
                            val edge = DashColors.TextSecondary
                            withTransform({
                                translate(cx - (CAR_X + CAR_W / 2f) * s, top - CAR_Y * s)
                                scale(s, s, Offset.Zero)
                            }) {
                                drawCar(
                                    body, edge.copy(alpha = 0.24f), edge, warn,
                                    f(open?.frontLeft), f(open?.frontRight), f(open?.rearLeft), f(open?.rearRight),
                                    f(open?.tailgate), f(open?.bonnet)
                                )
                            }
                        }
                    }
                }
        )
        TyreChip(chips[0], Modifier.offset(pad.dp, frontTop.dp).size(chipW.dp, chipH.dp))
        TyreChip(chips[1], Modifier.offset((w - pad - chipW).dp, frontTop.dp).size(chipW.dp, chipH.dp))
        TyreChip(chips[2], Modifier.offset(pad.dp, rearTop.dp).size(chipW.dp, chipH.dp))
        TyreChip(chips[3], Modifier.offset((w - pad - chipW).dp, rearTop.dp).size(chipW.dp, chipH.dp))
        if (showCaption) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(captionH.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MsText(
                    caption,
                    msCaps(fixedSp(captionSp), if (idle) DashColors.Accent else DashColors.Muted).copy(textAlign = TextAlign.Center),
                    Modifier.weight(1f)
                )
                if (connected) {
                    Row(
                        Modifier
                            .height(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !env.editing, role = Role.Button, onClickLabel = chooseAdapter, onClick = env.onPickDevice)
                            .semantics { contentDescription = chooseAdapter }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(DashColors.Good))
                        Spacer(Modifier.width(5.dp))
                        MsText(stringResource(R.string.skin_motorsport_live_caps), msCaps(fixedSp(captionSp), DashColors.Good))
                    }
                }
            }
        }
    }
}

/** A pressure as a bare number in the TPMS app's own unit: "2.3", "230", "33". */
private fun pressureNumber(kPa: Int, unit: PressureUnit): String = when (unit) {
    PressureUnit.BAR -> String.format(Locale.getDefault(), "%.1f", kPa / 100.0)
    PressureUnit.KPA -> "$kPa"
    PressureUnit.PSI -> "${(kPa / 6.895).roundToInt()}"
}

private fun pressureWord(unit: PressureUnit): String = when (unit) {
    PressureUnit.BAR -> "bar"
    PressureUnit.KPA -> "kPa"
    PressureUnit.PSI -> "psi"
}

/** A reading at a wheel: the value big in condensed type over its label, on a tint of its state colour. */
@Composable
private fun TyreChip(chip: MsChip, modifier: Modifier) {
    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(chip.tint.copy(alpha = 0.14f))
            .border(1.dp, chip.tint.copy(alpha = if (chip.tint == DashColors.Muted) 0.4f else 1f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        val valueSp = (maxHeight.value * 0.44f).coerceIn(12f, 30f)
        val labelSp = (maxHeight.value * 0.2f).coerceIn(9f, 13f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MsText(
                chip.value,
                TextStyle(
                    color = if (chip.tint == DashColors.Good) DashColors.TextPrimary else chip.tint,
                    fontFamily = CondensedFamily, fontWeight = FontWeight.ExtraBold, fontSize = fixedSp(valueSp)
                ),
                clip = true
            )
            MsText(chip.label, msBody(fixedSp(labelSp), DashColors.Muted), Modifier.padding(horizontal = 3.dp))
        }
    }
}

// --- Track map (NAVIGATION) ---------------------------------------------------------

/**
 * The race track the route is drawn as (the mockup's circuit, in its own
 * 390 x 230 space): three corners joined smoothly, one per sector. The
 * notification gives no route shape, so the track is a picture of the trip,
 * and the car's place on it is how much of the distance is behind it.
 */
private object MsTrack {
    const val W = 390f
    const val H = 230f
    private val P = floatArrayOf(
        30f, 200f,
        20f, 120f, 60f, 60f, 140f, 70f,
        220f, 80f, 240f, 160f, 300f, 120f,
        360f, 80f, 370f, 30f, 330f, 20f
    )

    /** The track scaled by [s] and moved by ([ox], [oy]); [turned] lays it upright (a quarter turn). */
    fun path(s: Float, ox: Float, oy: Float, turned: Boolean): Path {
        fun x(i: Int) = if (turned) ox + (H - P[i + 1]) * s else ox + P[i] * s
        fun y(i: Int) = if (turned) oy + P[i] * s else oy + P[i + 1] * s
        return Path().apply {
            moveTo(x(0), y(0))
            var i = 2
            while (i + 5 < P.size) {
                cubicTo(x(i), y(i), x(i + 2), y(i + 2), x(i + 4), y(i + 4))
                i += 6
            }
        }
    }

    /** Where each sector ends, as a share of the lap: the end of each corner. */
    val ends: FloatArray by lazy {
        val m = PathMeasure()
        fun len(n: Int): Float {
            val p = Path().apply {
                moveTo(P[0], P[1])
                var i = 2
                repeat(n) {
                    cubicTo(P[i], P[i + 1], P[i + 2], P[i + 3], P[i + 4], P[i + 5])
                    i += 6
                }
            }
            m.setPath(p, false)
            return m.length
        }
        val total = len(3).coerceAtLeast(1f)
        floatArrayOf(len(1) / total, len(2) / total, 1f)
    }
}

/**
 * The route under way as a race: when it started, the time it was planned to
 * take, the most distance (or time) left seen, and when each sector was done.
 */
private class MsRace(var startedAt: Long, var plannedMs: Long?) {
    var maxLeft = 0f
    val ends = LongArray(3)

    /** A new route under the same guidance: the race starts over. */
    fun restart(now: Long, planned: Long?) {
        startedAt = now
        plannedMs = planned
        maxLeft = 0f
        ends.fill(0L)
    }
}

/** The race for the route on now and how far along it the car is, 0..1. */
@Composable
private fun rememberRace(nav: NavState): Pair<MsRace, Float> {
    // Keyed on the destination handed off too, and started over when what is left grows by a
    // third (a new destination picked inside the navigation app): the old route's start, plan
    // and sectors otherwise judged the new one. Times are differences: a clock GPS cannot set.
    val handed by NavHandoff.handedOff.collectAsState()
    val race = remember(nav.active, nav.packageName, handed?.at) {
        MsRace(SystemClock.elapsedRealtime(), etaMinutes(nav.eta)?.let { it * 60_000L })
    }
    val left = kmLeft(nav.eta) ?: etaMinutes(nav.eta)?.toFloat()
    val progress = remember(race, left) {
        if (left != null && race.maxLeft > 0f && left > race.maxLeft * 1.3f) race.restart(SystemClock.elapsedRealtime(), etaMinutes(nav.eta)?.let { it * 60_000L })
        if (left != null) race.maxLeft = max(race.maxLeft, left)
        val p = if (left == null || race.maxLeft <= 0f) 0f else (1f - left / race.maxLeft).coerceIn(0f, 1f)
        val now = SystemClock.elapsedRealtime()
        for (i in 0 until 3) if (race.ends[i] == 0L && p >= MsTrack.ends[i] - 0.001f) race.ends[i] = now
        p
    }
    return race to progress
}

/** A sector's share of the lap. */
private fun sectorShare(i: Int): Float = MsTrack.ends[i] - (if (i == 0) 0f else MsTrack.ends[i - 1])

/** A done sector's colour: purple when it beat its share of the planned time, green otherwise. */
private fun sectorColor(race: MsRace, i: Int): Color? {
    val end = race.ends[i]
    if (end == 0L) return null
    val start = if (i == 0) race.startedAt else race.ends[i - 1]
    val planned = race.plannedMs?.let { it * sectorShare(i) } ?: return DashColors.Good
    return if (end - start <= planned) DashColors.Accent2 else DashColors.Good
}

/** "R 300 M": the side of the next turn when the navigation says it, and the distance to it. */
@Composable
private fun nextTurnText(nav: NavState): String {
    val side = when (nav.turnSide()) {
        TurnSide.LEFT -> stringResource(R.string.skin_motorsport_turn_left)
        TurnSide.RIGHT -> stringResource(R.string.skin_motorsport_turn_right)
        TurnSide.AHEAD -> null
    }
    val (value, unit) = nav.distanceParts
    val dist = listOf(value, unit.uppercase()).filter { it.isNotEmpty() }.joinToString(" ")
    return listOfNotNull(side, dist.ifEmpty { null }).joinToString(" ").ifEmpty { nav.instruction }
}

/**
 * Directions as a race: the route drawn as a track whose sectors colour in
 * as they are driven (purple ahead of the planned time, green otherwise,
 * dashed grey still to come) with the car as a pulsing dot, the next turn as
 * "R 300 M", the finish time, and the sector times along the bottom. Small
 * tiles keep the next turn and the finish. Tap opens the navigation app.
 */
@Composable
internal fun MsTrackMap(env: SkinTileEnv) {
    val nav by NavDirections.state.collectAsState()
    val context = env.context
    val canTap = !env.editing
    when {
        !env.hasMediaAccess -> MsEmpty(
            stringResource(R.string.info_directions_access_title).uppercase(),
            stringResource(R.string.skin_motorsport_tap_allow_caps),
            if (canTap) ({ CarMediaController.openNotificationAccessSettings(context) }) else null,
            track = true
        )
        !nav.active -> MsEmpty(
            stringResource(R.string.info_directions_no_route).uppercase(),
            stringResource(R.string.skin_motorsport_tap_maps_caps),
            if (canTap) ({ openNavigationApp(context, nav) }) else null,
            track = true
        )
        else -> MsRoute(nav, env)
    }
}

@Composable
private fun MsRoute(nav: NavState, env: SkinTileEnv) {
    val context = env.context
    val (race, progress) = rememberRace(nav)
    val units = LocalUnits.current
    val now = rememberNow(60_000L)
    val timeFmt = rememberDateFormat(units.timePattern())
    val arrival = arrivalMs(nav.eta, now.time)
    val finish = arrival?.let { stringResource(R.string.skin_motorsport_finish_caps, timeFmt.format(java.util.Date(it)).uppercase()) }
    val turn = nextTurnText(nav)
    val colors = List(3) { sectorColor(race, it) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .msPanel()
            .msTap(!env.editing, stringResource(R.string.skin_motorsport_open_navigation)) { openNavigationApp(context, nav) }
    ) {
        val w = maxWidth.value
        val h = maxHeight.value
        val pad = (min(w, h) * 0.06f).coerceIn(10f, 20f)
        val compact = h < 170f || w < 260f
        val capsSp = (min(w, h) * 0.055f).coerceIn(11f, 18f)
        if (compact) {
            CompactTurn(nav, turn, finish, Modifier.fillMaxSize().padding(pad.dp))
            return@BoxWithConstraints
        }
        val wide = w >= 520f
        val showSectors = h >= 230f
        val sectorH = (h * 0.16f).coerceIn(44f, 70f)
        Column(Modifier.fillMaxSize().padding(pad.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (wide) {
                    MsText(stringResource(R.string.skin_motorsport_track_caps), msCaps(fixedSp(capsSp * 1.15f), DashColors.TextPrimary, FontWeight.ExtraBold))
                } else {
                    ManeuverGlyph(nav, (capsSp * 1.8f).dp)
                    Spacer(Modifier.width(6.dp))
                    MsText(turn, msHero(fixedSp(capsSp * 1.6f), DashColors.TextPrimary), Modifier.weight(1f, fill = false), clip = true)
                }
                Spacer(Modifier.weight(1f))
                if (finish != null) MsText(finish, msCaps(fixedSp(capsSp), DashColors.Muted), Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(6.dp))
            TrackCanvas(progress, colors, dim = false, Modifier.weight(1f).fillMaxWidth())
            if (showSectors) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().height(sectorH.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (i in 0 until 3) {
                        SectorCell(race, i, colors[i], Modifier.weight(1f).fillMaxHeight())
                        if (i < 2) Spacer(Modifier.width(8.dp))
                    }
                    if (wide) {
                        Spacer(Modifier.width(12.dp))
                        NextTurnBlock(nav, turn, Modifier.weight(1.3f).fillMaxHeight())
                    }
                }
            } else if (wide) {
                Spacer(Modifier.height(6.dp))
                NextTurnBlock(nav, turn, Modifier.fillMaxWidth().height(sectorH.dp))
            }
        }
    }
}

/** A small tile's directions: the glyph, "R 300 M" big, the street and the finish time. */
@Composable
private fun CompactTurn(nav: NavState, turn: String, finish: String?, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val glyph = (min(maxHeight.value * 0.5f, maxWidth.value * 0.26f)).coerceIn(28f, 80f)
        val lineSp = (maxHeight.value * 0.11f).coerceIn(11f, 16f)
        val turnSp = msFitSp(msTemplate(turn), msHero(100.sp, DashColors.TextPrimary), maxWidth - glyph.dp - 12.dp, (maxHeight.value * 0.42f).dp, 16f, 80f)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            ManeuverGlyph(nav, glyph.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                MsText(turn, msHero(turnSp.sp, DashColors.TextPrimary, glow = true), clip = true)
                MsText(nav.instruction, msBody(fixedSp(lineSp), DashColors.TextSecondary))
                if (finish != null) MsText(finish, msCaps(fixedSp(lineSp * 0.9f), DashColors.Good))
            }
        }
    }
}

/** The next turn at the bottom right of a wide tile: the glyph, "R 300 M" and the street. */
@Composable
private fun NextTurnBlock(nav: NavState, turn: String, modifier: Modifier) {
    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(DashColors.TextPrimary.copy(alpha = 0.06f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        val glyph = (maxHeight.value * 0.62f).coerceIn(22f, 56f)
        val turnSp = msFitSp(msTemplate(turn), msHero(100.sp, DashColors.TextPrimary), maxWidth - glyph.dp - 10.dp, (maxHeight.value * 0.6f).dp, 14f, 48f)
        val showStreet = maxHeight >= 50.dp
        val streetSp = (maxHeight.value * 0.2f).coerceIn(10f, 14f)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            ManeuverGlyph(nav, glyph.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                MsText(turn, msHero(turnSp.sp, DashColors.TextPrimary), clip = true)
                if (showStreet) MsText(nav.instruction, msBody(fixedSp(streetSp), DashColors.Muted))
            }
        }
    }
}

/** The manoeuvre arrow from the navigation notification in the text colour; a generic arrow without one. */
@Composable
private fun ManeuverGlyph(nav: NavState, size: Dp) {
    val bitmap = remember(nav.icon) { nav.icon?.asImageBitmap() }
    val ink = DashColors.TextPrimary
    if (bitmap != null) {
        Image(bitmap, contentDescription = nav.instruction, contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(ink), modifier = Modifier.size(size))
    } else {
        Icon(Icons.Filled.Directions, contentDescription = nav.instruction, tint = ink, modifier = Modifier.size(size))
    }
}

/**
 * One sector's time under a bar of its colour: done sectors show the time
 * they took, the one being driven counts up each second, the ones to come
 * show dashes.
 */
@Composable
private fun SectorCell(race: MsRace, i: Int, done: Color?, modifier: Modifier) {
    val current = done == null && (i == 0 || race.ends[i - 1] != 0L)
    val bar = done ?: if (current) DashColors.Good else MsTrackInk
    BoxWithConstraints(
        modifier.cachedDraw(bar) {
            val bw = 4.dp.toPx()
            onDrawBehind { drawRect(bar, size = Size(bw, size.height)) }
        }.padding(start = 10.dp)
    ) {
        val labelSp = (maxHeight.value * 0.22f).coerceIn(10f, 14f)
        val valueSp = (maxHeight.value * 0.42f).coerceIn(14f, 28f)
        Column(verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxHeight()) {
            MsText(stringResource(R.string.skin_motorsport_sector, i + 1), msBody(fixedSp(labelSp), DashColors.Muted))
            when {
                done != null -> {
                    val start = if (i == 0) race.startedAt else race.ends[i - 1]
                    MsText(lapTime(race.ends[i] - start), msHero(fixedSp(valueSp), DashColors.TextPrimary), clip = true)
                }
                current -> {
                    val start = if (i == 0) race.startedAt else race.ends[i - 1]
                    RunningTime(start, msHero(fixedSp(valueSp), DashColors.Good))
                }
                else -> MsText("--:--", msHero(fixedSp(valueSp), DashColors.Muted), clip = true)
            }
        }
    }
}

/** A sector being driven, counting up each second; in its own scope so only it follows the clock. */
@Composable
private fun RunningTime(start: Long, style: TextStyle) {
    val clock = rememberWallClock(1_000L)
    MsText(lapTime(clock.longValue - start), style, clip = true)
}

/** "8:02", or "1:08:02" past the hour. */
private fun lapTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * The track: tarmac, then each sector driven in its colour with a glow
 * (green for the one under way), dashed grey ahead, a chequered flag at the
 * finish and the car as a white dot that pulses. Upright boxes turn the track
 * a quarter. [dim] draws it faint behind a message.
 */
@Composable
private fun TrackCanvas(progress: Float, colors: List<Color?>, dim: Boolean, modifier: Modifier) {
    val pulse = if (dim) null else rememberLoop(1_200, reverse = true)
    Box(
        modifier.cachedDraw(progress, colors, dim, pulse) {
            if (size.minDimension < 24f) return@cachedDraw onDrawBehind { }
            val turned = size.height > size.width * 1.2f
            val tw = if (turned) MsTrack.H else MsTrack.W
            val th = if (turned) MsTrack.W else MsTrack.H
            // Room for the tarmac's width round the line.
            val s = min(size.width / (tw + 24f), size.height / (th + 24f))
            val ox = (size.width - tw * s) / 2f
            val oy = (size.height - th * s) / 2f
            val path = MsTrack.path(s, ox, oy, turned)
            val measure = PathMeasure().apply { setPath(path, false) }
            val total = measure.length
            val at = progress * total
            val glow = if (dim) 0f else msGlow
            val tarmac = Stroke(18f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val line = Stroke(7f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val halo = Stroke(14f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val ahead = Stroke(7f * s, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f * s, 10f * s)))
            val aheadInk = msTone(Color(0xFF3A3A40), Color(0xFFB8B8C0))
            val tarmacInk = MsTrackInk
            val driven = Array(3) { Path() }
            val todo = Array(3) { Path() }
            val driveInk = Array(3) { i -> colors[i] ?: DashColors.Good }
            for (i in 0 until 3) {
                val a = (if (i == 0) 0f else MsTrack.ends[i - 1]) * total
                val b = MsTrack.ends[i] * total
                if (!dim && at > a) measure.getSegment(a, min(at, b), driven[i], true)
                if (at < b) measure.getSegment(max(a, at), b, todo[i], true)
            }
            val dot = measure.getPosition(at)
            val dotR = 6f * s
            val dotInk = msTone(Color.White, Color(0xFF111114))
            val dotHalo = Brush.radialGradient(listOf(dotInk.copy(alpha = 0.45f * glow), Color.Transparent), dot, dotR * 3f)
            // The chequered flag at the finish, three by two squares.
            val end = measure.getPosition(total)
            val sq = 5f * s
            val flagAt = Offset(end.x - sq * 1.5f, end.y - sq * 3.2f)
            val flagInk = DashColors.TextPrimary
            val alpha = if (dim) 0.35f else 1f
            onDrawBehind {
                drawPath(path, tarmacInk, style = tarmac, alpha = alpha)
                for (i in 0 until 3) {
                    drawPath(todo[i], aheadInk, style = ahead, alpha = alpha)
                    if (!dim) {
                        if (glow > 0f) drawPath(driven[i], driveInk[i].copy(alpha = 0.3f * glow), style = halo)
                        drawPath(driven[i], driveInk[i], style = line)
                    }
                }
                for (r in 0 until 2) for (c in 0 until 3) {
                    val on = (r + c) % 2 == 0
                    drawRect(if (on) flagInk else flagInk.copy(alpha = 0.25f), Offset(flagAt.x + c * sq, flagAt.y + r * sq), Size(sq, sq), alpha = alpha)
                }
                if (!dim) {
                    val p = pulse?.value ?: 0f
                    if (glow > 0f) drawCircle(dotHalo, dotR * 3f, dot, alpha = 0.5f + 0.5f * p)
                    drawCircle(dotInk, dotR * (1f + 0.3f * p), dot)
                }
            }
        }
    )
}

// --- Team radio (MEDIA) -------------------------------------------------------------

/**
 * Music as team radio: a red-washed panel, "TEAM RADIO · ON AIR" with a
 * pulsing on-air light while playing, the title in large sans, the artist and
 * the time, a red progress line and three glossy keys (play in red). Roomy
 * tiles add the album art; wide short ones put the keys beside the words.
 * Without notification access the whole tile asks for it.
 */
@Composable
internal fun MsTeamRadio(env: SkinTileEnv) {
    val ms = env.mediaState
    val access = env.hasMediaAccess
    val hasTrack = ms.hasMedia && ms.title.isNotBlank()
    val context = env.context
    val red = DashColors.Critical
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .msPanel(MsPanel.RADIO)
            .msTap(!access && !env.editing, stringResource(R.string.skin_motorsport_allow_media)) {
                CarMediaController.openNotificationAccessSettings(context)
            }
    ) {
        val w = maxWidth.value
        val h = maxHeight.value
        val pad = (min(w, h) * 0.07f).coerceIn(12f, 22f)
        val wide = w > h * 2.6f && w >= 520f
        val keyH = (h * if (wide) 0.42f else 0.22f).coerceIn(48f, 64f)
        val art = ms.artwork
        val showArt = access && art != null && w >= 460f && h >= 190f && !wide
        val artSize = if (showArt) min(h - pad * 2f, w * 0.3f) else 0f
        val textW = w - pad * 2f - (if (showArt) artSize + 14f else 0f) - (if (wide) keyH * 1.4f * 3f + 30f else 0f)
        val titleSp = min(h * if (wide) 0.24f else 0.15f, textW / 9f).coerceIn(16f, 40f)
        val subSp = (titleSp * 0.52f).coerceIn(12f, 20f)
        val capsSp = (titleSp * 0.48f).coerceIn(11f, 16f)
        val titleLines = if (!wide && h > 300f) 2 else 1
        val showProgress = access && hasTrack && ms.durationMs > 0L && h >= 150f
        val info: @Composable (Modifier) -> Unit = { mod ->
            Column(mod) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OnAirLight(ms.isPlaying && access, (capsSp * 0.65f).dp)
                    Spacer(Modifier.width(8.dp))
                    val head = stringResource(R.string.skin_motorsport_team_radio_caps) + when {
                        !access || !hasTrack -> ""
                        ms.isPlaying -> DOT + stringResource(R.string.skin_motorsport_on_air_caps)
                        else -> DOT + stringResource(R.string.info_paused)
                    }
                    MsText(head, msCaps(fixedSp(capsSp), MsRadioInk, FontWeight.ExtraBold))
                }
                Spacer(Modifier.height(6.dp))
                MsText(
                    when {
                        !access -> stringResource(R.string.info_media_access_needed)
                        hasTrack -> ms.title
                        else -> stringResource(R.string.info_nothing_playing)
                    },
                    msBody(fixedSp(titleSp), if (hasTrack && access) DashColors.TextPrimary else DashColors.TextSecondary, FontWeight.Bold)
                        .copy(fontStyle = if (hasTrack && access) FontStyle.Normal else FontStyle.Italic),
                    maxLines = titleLines
                )
                when {
                    !access -> MsText(stringResource(R.string.skin_motorsport_tap_allow_caps), msCaps(fixedSp(subSp * 0.85f), MsRadioInk))
                    !hasTrack -> MsText(stringResource(R.string.skin_motorsport_start_music_caps), msCaps(fixedSp(subSp * 0.85f)))
                    else -> RadioSubline(ms, env.mediaController, ms.artist, fixedSp(subSp), showTimes = h >= 130f)
                }
                if (showProgress) {
                    Spacer(Modifier.height(8.dp))
                    RadioProgress(ms, env.mediaController, red, Modifier.fillMaxWidth().height(6.dp))
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(pad.dp), verticalAlignment = Alignment.CenterVertically) {
                info(Modifier.weight(1f))
                if (access) {
                    Spacer(Modifier.width(16.dp))
                    RadioKeys(env, keyH.dp, (keyH * 1.4f).dp)
                }
            }
        } else {
            Row(Modifier.fillMaxSize().padding(pad.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    info(Modifier.fillMaxWidth())
                    if (access) {
                        val keyW = ((textW - 20f) / 3f).coerceIn(48f, 96f)
                        RadioKeys(env, keyH.dp, keyW.dp)
                    }
                }
                if (showArt) {
                    Spacer(Modifier.width(14.dp))
                    val bmp = remember(art) { art.asImageBitmap() }
                    Image(
                        bmp, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(artSize.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, red.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    )
                }
            }
        }
    }
}

/** The on-air light: red with a halo, breathing while playing; a dark glass when not. */
@Composable
private fun OnAirLight(on: Boolean, size: Dp) {
    val pulse = if (on) rememberLoop(1_000, reverse = true) else null
    Box(
        Modifier.size(size).cachedDraw(on, pulse) {
            val r = this.size.minDimension / 2f
            val c = Offset(this.size.width / 2f, this.size.height / 2f)
            val red = DashColors.Critical
            val glow = msGlow
            val halo = Brush.radialGradient(listOf(red.copy(alpha = 0.6f * glow), Color.Transparent), c, r * 2.4f)
            onDrawBehind {
                if (on) {
                    val a = 0.6f + 0.4f * (pulse?.value ?: 1f)
                    if (glow > 0f) drawCircle(halo, r * 2.4f, c, alpha = a)
                    drawCircle(red, r, c, alpha = a)
                } else {
                    drawCircle(red.copy(alpha = 0.3f), r, c)
                }
            }
        }
    )
}

/** "Artist · 1:35 / 4:03": in its own scope, so only it follows the playback position. */
@Composable
private fun RadioSubline(ms: MediaState, controller: CarMediaController, artist: String, size: androidx.compose.ui.unit.TextUnit, showTimes: Boolean) {
    val times = if (showTimes && ms.durationMs > 0L) {
        val pos = rememberMediaPosition(ms, controller)
        "${formatTrackTime(pos)} / ${formatTrackTime(ms.durationMs)}"
    } else null
    val who = artist.ifBlank { stringResource(R.string.skin_motorsport_unknown_artist) }
    MsText(listOfNotNull(who, times).joinToString(DOT), msBody(size, DashColors.TextSecondary))
}

/** The red progress line with a glow at its head. */
@Composable
private fun RadioProgress(ms: MediaState, controller: CarMediaController, red: Color, modifier: Modifier) {
    val fraction = rememberMediaFraction(ms, controller)
    Box(
        modifier.cachedDraw(fraction, red) {
            val track = MsSegOff
            val glow = msGlow
            val r = CornerRadius(size.height / 2f)
            val fw = size.width * fraction
            val fill = Brush.horizontalGradient(listOf(lerp(red, Color.Black, 0.25f), red), startX = 0f, endX = fw.coerceAtLeast(1f))
            onDrawBehind {
                drawRoundRect(track, cornerRadius = r)
                if (fw > 0f) {
                    if (glow > 0f) drawRoundRect(red.copy(alpha = 0.25f * glow), Offset(0f, -size.height * 0.5f), Size(fw, size.height * 2f), CornerRadius(size.height))
                    drawRoundRect(fill, size = Size(fw, size.height), cornerRadius = r)
                }
            }
        }
    )
}

/** Previous, play / pause and next as glossy keys; play is red. */
@Composable
private fun RadioKeys(env: SkinTileEnv, h: Dp, w: Dp) {
    val playing = env.mediaState.isPlaying
    val enabled = !env.editing
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RadioKey(Icons.Filled.SkipPrevious, stringResource(R.string.skin_motorsport_previous), false, enabled, w, h) { env.mediaController.previous() }
        RadioKey(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            stringResource(if (playing) R.string.skin_motorsport_pause else R.string.skin_motorsport_play),
            true, enabled, w, h
        ) { env.mediaController.playPause() }
        RadioKey(Icons.Filled.SkipNext, stringResource(R.string.skin_motorsport_next), false, enabled, w, h) { env.mediaController.next() }
    }
}

/** One glossy key: dark graphite, or red with a glow under it for [primary]; it sinks while pressed. */
@Composable
private fun RadioKey(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, primary: Boolean, enabled: Boolean, w: Dp, h: Dp, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tap = rememberTapFeedback()
    Box(
        Modifier
            .size(w, h)
            .graphicsLayer { translationY = if (pressed) 2.dp.toPx() else 0f }
            .cachedDraw(primary, pressed) {
                val r = CornerRadius(12.dp.toPx())
                val red = DashColors.Critical
                val glow = msGlow
                val face = if (primary) {
                    Brush.verticalGradient(listOf(lerp(red, Color.White, 0.18f), lerp(red, Color.Black, 0.22f)))
                } else {
                    Brush.verticalGradient(listOf(msTone(Color(0xFF2E2E33), Color.White), msTone(Color(0xFF1E1E22), Color(0xFFE2E2E8))))
                }
                val rim = if (primary) Color.Transparent else msTone(Color(0xFF3A3A40), Color(0xFFC0C0C8))
                val under = Brush.radialGradient(
                    listOf(red.copy(alpha = 0.5f * glow), Color.Transparent),
                    Offset(size.width / 2f, size.height * 0.85f), size.width * 0.7f
                )
                val spec = Color.White.copy(alpha = if (primary) 0.4f else if (DashColors.Light) 0.9f else 0.1f)
                val stroke = Stroke(1.dp.toPx())
                onDrawBehind {
                    if (primary && glow > 0f && !pressed) {
                        scale(1f, 0.5f, Offset(size.width / 2f, size.height * 0.85f)) {
                            drawCircle(under, size.width * 0.7f, Offset(size.width / 2f, size.height * 0.85f))
                        }
                    }
                    drawRoundRect(face, cornerRadius = r)
                    if (rim != Color.Transparent) drawRoundRect(rim, cornerRadius = r, style = stroke)
                    drawLine(spec, Offset(r.x, 1.5f), Offset(size.width - r.x, 1.5f), 1.dp.toPx())
                    if (pressed) drawRoundRect(Color.Black.copy(alpha = 0.15f), cornerRadius = r)
                }
            }
            .clip(RoundedCornerShape(12.dp))
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClickLabel = label) { tap(); onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        MsIcon(icon, if (primary) Color.White else DashColors.TextPrimary, (min(h.value, w.value) * 0.46f).coerceIn(18f, 30f).dp)
    }
}

// --- Empty states -------------------------------------------------------------------

/** A panel with a caps title and a hint (red when tapping does something), the track faint behind it when [track]. */
@Composable
private fun MsEmpty(title: String, hint: String, onTap: (() -> Unit)?, track: Boolean = false) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .msPanel()
            .msTap(onTap != null, hint) { onTap?.invoke() },
        contentAlignment = Alignment.Center
    ) {
        if (track && maxHeight >= 140.dp) TrackCanvas(0f, listOf(null, null, null), dim = true, Modifier.fillMaxSize().padding(16.dp))
        val titleSp = min(maxHeight.value * 0.16f, maxWidth.value / (title.length.coerceAtLeast(4) * 0.5f)).coerceIn(16f, 44f)
        val hintSp = (titleSp * 0.42f).coerceIn(14f, 16f)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(14.dp)) {
            MsText(title, msHero(fixedSp(titleSp), DashColors.TextPrimary).copy(textAlign = TextAlign.Center), maxLines = 2)
            Spacer(Modifier.height(4.dp))
            MsText(
                hint,
                msCaps(fixedSp(hintSp), if (onTap != null) MsRadioInk else DashColors.Muted).copy(textAlign = TextAlign.Center),
                maxLines = 2
            )
        }
    }
}

// --- Clock --------------------------------------------------------------------------

/** Skeleton of weekday, day and month, in the locale's own order. */
private const val LONG_DATE = "EEEEdMMMM"

/**
 * Local time as a timing screen: "LOCAL TIME", the time in big italic with
 * the seconds ticking in yellow beside it, the date in caps and, on roomy
 * tiles, sixty marks lit up to the current second. Tap opens the alarms.
 */
@Composable
internal fun MsClock(env: SkinTileEnv) {
    val now = rememberNow(60_000L)
    val locale = Locale.getDefault()
    val units = LocalUnits.current
    val timeFmt = rememberDateFormat(units.digitsPattern())
    val dateFmt = rememberDateFormat(LONG_DATE, best = true)
    val context = env.context
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .msPanel()
            .msTap(!env.editing, stringResource(R.string.skin_motorsport_open_clock)) { openClockApp(context) }
    ) {
        val w = maxWidth.value
        val h = maxHeight.value
        val pad = (min(w, h) * 0.07f).coerceIn(12f, 22f)
        val capsSp = (min(w, h) * 0.06f).coerceIn(11f, 16f)
        val showCaps = h >= 140f
        val showDate = h >= 110f
        val showMarks = h >= 190f && w >= 240f
        val below = (if (showCaps) capsSp * 1.4f else 0f) + (if (showDate) capsSp * 1.5f else 0f) + (if (showMarks) 22f else 0f) + 10f
        val timeSp = msFitSp("00:00", msHero(100.sp, DashColors.TextPrimary), ((w - pad * 2f) * 0.8f).dp, (h - pad * 2f - below).coerceAtLeast(20f).dp, 22f, 300f)
        val amPm = units.amPm(now, locale)
        val clock = rememberWallClock(1_000L)
        Column(Modifier.fillMaxSize().padding(pad.dp), verticalArrangement = Arrangement.Center) {
            if (showCaps) MsText(stringResource(R.string.skin_motorsport_local_time_caps), msCaps(fixedSp(capsSp)))
            Row(verticalAlignment = Alignment.Bottom) {
                MsText(timeFmt.format(now), msHero(timeSp.sp, DashColors.TextPrimary, glow = true), Modifier.alignByBaseline(), clip = true)
                Spacer(Modifier.width((timeSp * 0.05f).dp))
                Seconds(clock, msHero(fixedSp((timeSp * 0.3f).coerceAtLeast(12f)), DashColors.Accent), Modifier.alignByBaseline())
                if (amPm != null) {
                    Spacer(Modifier.width(4.dp))
                    MsText(amPm, msCaps(fixedSp((timeSp * 0.16f).coerceAtLeast(11f)), DashColors.TextSecondary), Modifier.alignByBaseline())
                }
            }
            if (showDate) MsText(dateFmt.format(now).uppercase(locale), msCaps(fixedSp(capsSp * 1.05f), DashColors.TextSecondary))
            if (showMarks) {
                Spacer(Modifier.height(8.dp))
                SecondMarks(clock, Modifier.fillMaxWidth().height(12.dp))
            }
        }
    }
}

/** ":42", following the wall clock in its own scope. */
@Composable
private fun Seconds(clock: LongState, style: TextStyle, modifier: Modifier) {
    val s = (clock.longValue / 1000L) % 60L
    MsText(":%02d".format(s), style, modifier, clip = true)
}

/** Sixty marks, every fifteenth taller, lit up to the current second: read while drawing, so only they redraw. */
@Composable
private fun SecondMarks(clock: LongState, modifier: Modifier) {
    Box(
        modifier.cachedDraw(DashColors.Light) {
            val n = 60
            val step = size.width / n
            val bw = (step * 0.5f).coerceAtLeast(1f)
            val on = DashColors.Accent
            val off = MsSegOff
            onDrawBehind {
                val s = ((clock.longValue / 1000L) % 60L).toInt()
                for (i in 0 until n) {
                    val tall = i % 15 == 0
                    val hh = if (tall) size.height else size.height * 0.6f
                    drawRect(if (i <= s) on else off, Offset(i * step, size.height - hh), Size(bw, hh))
                }
            }
        }
    )
}

// --- Weather ------------------------------------------------------------------------

/** The tyres the weather calls for, as a pit wall would: slicks (soft, or hard when cold), inters, wets. */
private enum class Compound(val mark: String) { SOFT("S"), HARD("H"), INTER("I"), WET("W") }

private fun compoundFor(code: Int, tempC: Double): Compound = when (code) {
    in 51..57, 61, 80 -> Compound.INTER
    in 63..67, 81, 82, in 71..77, 85, 86, in 95..99 -> Compound.WET
    else -> if (tempC < 7.0) Compound.HARD else Compound.SOFT
}

/**
 * Track conditions: the temperature in big italic, the sky with its icon, a
 * feels / wind line and a tyre-compound badge for the weather (slicks when
 * dry, inters in drizzle, wets in rain or snow). Without weather yet, why.
 */
@Composable
internal fun MsWeather() {
    val w = rememberWeather()
    val wait = rememberWeatherWait()
    if (w == null) {
        val title = when (wait) {
            WeatherWait.LOADING -> R.string.skin_motorsport_loading_caps
            WeatherWait.NO_GPS -> R.string.info_waiting_gps
            WeatherWait.UNAVAILABLE -> R.string.info_weather_unavailable
        }
        MsEmpty(stringResource(title).uppercase(), stringResource(R.string.skin_motorsport_conditions_caps), null)
        return
    }
    val units = LocalUnits.current
    val compound = compoundFor(w.code, w.tempC)
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer().msPanel()) {
        val ww = maxWidth.value
        val h = maxHeight.value
        val pad = (min(ww, h) * 0.07f).coerceIn(12f, 22f)
        val capsSp = (min(ww, h) * 0.06f).coerceIn(11f, 16f)
        val showBadge = ww >= 260f && h >= 120f
        val badge = if (showBadge) (min(h - pad * 2f, ww * 0.3f)).coerceIn(56f, 150f) else 0f
        val textW = ww - pad * 2f - (if (showBadge) badge + 14f else 0f)
        val lineSp = (min(textW / 18f, h * 0.08f)).coerceIn(11f, 18f)
        val showCaps = h >= 140f
        val below = lineSp * 2.8f + (if (showCaps) capsSp * 1.4f else 0f) + 6f
        val temp = "${units.temp(w.tempC)}°"
        val tempSp = msFitSp(msTemplate(temp), msHero(100.sp, DashColors.TextPrimary), textW.dp, (h - pad * 2f - below).coerceAtLeast(20f).dp, 22f, 220f)
        val feels = stringResource(R.string.skin_motorsport_feels_caps, units.temp(w.feelsC))
        val wind = stringResource(R.string.skin_motorsport_wind_caps, units.speed(w.windKmh), units.speedUnit.uppercase())
        Row(Modifier.fillMaxSize().padding(pad.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                if (showCaps) MsText(stringResource(R.string.skin_motorsport_conditions_caps), msCaps(fixedSp(capsSp)))
                MsText(temp, msHero(tempSp.sp, DashColors.TextPrimary, glow = true), clip = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(weatherIcon(w.code), contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size((lineSp * 1.3f).dp))
                    Spacer(Modifier.width(6.dp))
                    MsText(w.condition, msBody(fixedSp(lineSp), DashColors.TextPrimary, FontWeight.Medium))
                }
                MsText("$feels$DOT$wind", msCaps(fixedSp(lineSp * 0.85f)))
            }
            if (showBadge) {
                Spacer(Modifier.width(14.dp))
                CompoundBadge(compound, badge.dp)
            }
        }
    }
}

/** A tyre seen side on: black rubber, the compound's coloured band and its letter, with its name under it. */
@Composable
private fun CompoundBadge(compound: Compound, size: Dp) {
    val name = stringResource(
        when (compound) {
            Compound.SOFT, Compound.HARD -> R.string.skin_motorsport_tyre_slicks
            Compound.INTER -> R.string.skin_motorsport_tyre_inters
            Compound.WET -> R.string.skin_motorsport_tyre_wets
        }
    )
    val band = when (compound) {
        Compound.SOFT -> DashColors.Critical
        Compound.HARD -> Color(0xFFF2F2F2)
        Compound.INTER -> DashColors.Good
        Compound.WET -> DashColors.Secondary
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val tyreSize = size * 0.78f
    val letter = remember(compound, tyreSize, density) {
        measurer.measure(
            compound.mark,
            TextStyle(
                fontFamily = CondensedFamily, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic,
                fontSize = with(density) { (tyreSize * 0.34f).toSp() }
            )
        )
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = name }) {
        Box(
            Modifier.size(tyreSize).cachedDraw(band, letter) {
                val r = this.size.minDimension / 2f
                val c = Offset(this.size.width / 2f, this.size.height / 2f)
                val rubber = Brush.radialGradient(listOf(Color(0xFF2A2A2E), Color(0xFF0A0A0B)), c, r)
                val bandStroke = Stroke(r * 0.1f)
                val glow = msGlow
                val bandGlow = Stroke(r * 0.22f)
                val shadow = msShade(0.5f)
                onDrawBehind {
                    drawCircle(shadow, r, c + Offset(0f, r * 0.08f))
                    drawCircle(rubber, r, c)
                    if (glow > 0f) drawCircle(band.copy(alpha = 0.25f * glow), r * 0.68f, c, style = bandGlow)
                    drawCircle(band, r * 0.68f, c, style = bandStroke)
                    drawCircle(Color(0xFF1A1A1E), r * 0.42f, c)
                    drawText(letter, color = band, topLeft = Offset(c.x - letter.size.width / 2f, c.y - letter.size.height / 2f))
                }
            }
        )
        Spacer(Modifier.height(4.dp))
        MsText(name, msCaps(fixedSp((size.value * 0.11f).coerceIn(10f, 14f)), DashColors.TextSecondary, FontWeight.ExtraBold))
    }
}

// --- Pit board (RANGE) --------------------------------------------------------------

/**
 * Fuel as a pit board: "PIT BOARD", the tank in big italic percent beside
 * the range, a blue gradient bar with a glow (amber when low), and the litres
 * left with the car's usual consumption, or "BOX BOX" when it is time to
 * fill up. Unknown fuel falls back to the standard tile, which explains how
 * to learn it.
 */
@Composable
internal fun MsPitBoard(item: DashboardItem, env: SkinTileEnv) {
    val fuel = rememberFuel()
    if (fuel == null) {
        StandardSkinnedTile(item, env)
        return
    }
    val units = LocalUnits.current
    val low = fuel.percent <= SKIN_LOW_FUEL_PCT
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer().msPanel()) {
        val w = maxWidth.value
        val h = maxHeight.value
        val pad = (min(w, h) * 0.07f).coerceIn(12f, 22f)
        val capsSp = (min(w, h) * 0.065f).coerceIn(11f, 18f)
        val barH = (h * 0.06f).coerceIn(8f, 16f)
        val showFooter = h >= 150f
        val footSp = (capsSp * 0.95f).coerceIn(11f, 17f)
        val below = capsSp * 1.4f + barH + 16f + (if (showFooter) footSp * 1.5f else 0f)
        val pct = "${if (fuel.percentEstimated) "≈" else ""}${fuel.percent}%"
        val range = "${units.distance(fuel.rangeKm)} ${units.distanceUnit}"
        val pctSp = msFitSp(msTemplate(pct), msHero(100.sp, DashColors.TextPrimary), ((w - pad * 2f) * 0.58f).dp, (h - pad * 2f - below).coerceAtLeast(20f).dp, 22f, 200f)
        val rangeSp = (pctSp * 0.3f).coerceIn(13f, 40f)
        Column(Modifier.fillMaxSize().padding(pad.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MsText(stringResource(R.string.skin_motorsport_pit_board_caps), msCaps(fixedSp(capsSp), MsPitBlue, FontWeight.ExtraBold))
                Spacer(Modifier.weight(1f))
                MsText(fuel.source.uppercase(), msCaps(fixedSp(capsSp * 0.8f)))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                MsText(pct, msHero(pctSp.sp, if (low) DashColors.Warning else DashColors.TextPrimary, glow = true), Modifier.alignByBaseline(), clip = true)
                Spacer(Modifier.width(12.dp))
                MsText(range, msBody(fixedSp(rangeSp), DashColors.TextPrimary, FontWeight.Bold), Modifier.alignByBaseline())
            }
            FuelBar(fuel.percent / 100f, low, Modifier.fillMaxWidth().height(barH.dp))
            if (showFooter) {
                if (low) {
                    MsText(stringResource(R.string.skin_motorsport_box_box_caps), msCaps(fixedSp(footSp), DashColors.Warning, FontWeight.ExtraBold))
                } else {
                    val litres = String.format(Locale.getDefault(), "%.0f L", fuel.liters)
                    MsText("$litres$DOT${units.economyText(fuel.avgUse)}", msBody(fixedSp(footSp), DashColors.TextSecondary))
                }
            }
        }
    }
}

/** The tank as a gradient bar with a glow, from dark to light blue, amber when low. */
@Composable
private fun FuelBar(fraction: Float, low: Boolean, modifier: Modifier) {
    Box(
        modifier.cachedDraw(fraction, low) {
            val from = if (low) lerp(DashColors.Warning, Color.Black, 0.25f) else DashColors.Secondary
            val to = if (low) DashColors.Warning else MsPitBlue
            val fw = size.width * fraction.coerceIn(0f, 1f)
            val fill = Brush.horizontalGradient(listOf(from, to), startX = 0f, endX = fw.coerceAtLeast(1f))
            val r = CornerRadius(size.height / 2f)
            val glow = msGlow
            onDrawBehind {
                drawRoundRect(MsSegOff, cornerRadius = r)
                if (fw > 0f) {
                    if (glow > 0f) drawRoundRect(to.copy(alpha = 0.3f * glow), Offset(-3f, -size.height * 0.4f), Size(fw + 6f, size.height * 1.8f), CornerRadius(size.height))
                    drawRoundRect(fill, size = Size(fw, size.height), cornerRadius = r)
                }
            }
        }
    )
}

// --- Apps ---------------------------------------------------------------------------

/** Each app keeps one of the wheel's button colours, picked from its package so it stays put. */
private fun domeColor(pkg: String): Color {
    val colors = listOf(DashColors.Secondary, DashColors.Accent, DashColors.Good, DashColors.Critical, MsSilver, DashColors.Accent2)
    return colors[abs(pkg.hashCode() % colors.size)]
}

/**
 * An app as a glossy round wheel button: the dome in its colour with the
 * app's icon set in a dark well under a glass glint, and the name in
 * condensed caps below (beside on a wide tile). While arranging it dims and
 * does not launch.
 */
@Composable
internal fun MsAppButton(item: DashboardItem.AppShortcut, env: SkinTileEnv) {
    val app = env.appsByPackage[item.packageName]
    val name = appLabel(app, item.packageName)
    val color = domeColor(item.packageName)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tap = rememberTapFeedback()
    val openLabel = stringResource(R.string.skin_motorsport_open_app, name)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .padding(MS_INSET)
            .clip(MsPanelShape)
            .clickable(interaction, indication = null, enabled = !env.editing, role = Role.Button, onClickLabel = openLabel) {
                tap()
                env.onLaunchApp(item.packageName)
            }
            .semantics(mergeDescendants = true) { contentDescription = name },
        contentAlignment = Alignment.Center
    ) {
        val wide = maxWidth > maxHeight * 1.6f
        val dome = (if (wide) maxHeight.value * 0.7f else min(maxHeight.value * 0.6f, maxWidth.value * 0.62f)).coerceIn(40f, 120f)
        val labelSp = (dome * 0.17f).coerceIn(12f, 18f)
        val labelFits = maxHeight.value >= dome + labelSp * 1.6f + 6f
        if (wide) {
            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                AppDome(app, color, dome.dp, pressed, env.editing)
                Spacer(Modifier.width(12.dp))
                MsText(name.uppercase(), msCaps(fixedSp(labelSp), DashColors.TextPrimary, FontWeight.ExtraBold), Modifier.weight(1f, fill = false))
            }
        } else {
            Column(Modifier.padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AppDome(app, color, dome.dp, pressed, env.editing)
                if (labelFits) {
                    Spacer(Modifier.height(6.dp))
                    MsText(name.uppercase(), msCaps(fixedSp(labelSp), DashColors.TextPrimary, FontWeight.ExtraBold).copy(textAlign = TextAlign.Center))
                }
            }
        }
    }
}

/** The dome, the dark well with the app's icon, and a glass glint over it; pressed it sinks, arranging it dims. */
@Composable
private fun AppDome(app: AppEntry?, color: Color, size: Dp, pressed: Boolean, dim: Boolean) {
    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                val s = if (pressed) 0.93f else 1f
                scaleX = s
                scaleY = s
                alpha = if (dim) 0.55f else 1f
            }
            .glossyDome(color),
        contentAlignment = Alignment.Center
    ) {
        val well = size * 0.72f
        Box(Modifier.size(well).clip(CircleShape).background(MsWell), contentAlignment = Alignment.Center) {
            if (app != null) AppIcon(icon = app.icon, size = well * 0.8f)
            else Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(well * 0.55f))
        }
        Box(
            Modifier.size(well).cachedDraw(DashColors.Effects) {
                val glint = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0f)), startY = 0f, endY = this.size.height * 0.5f
                )
                val on = DashColors.Effects != DashEffects.NONE
                onDrawBehind {
                    if (on) drawOval(glint, Offset(this.size.width * 0.12f, this.size.height * 0.03f), Size(this.size.width * 0.76f, this.size.height * 0.46f))
                }
            }
        )
    }
}

/**
 * A launch bar as the wheel's button field: a row of glossy domes with their
 * names under them on a tall bar (beside them on a wide short one where they
 * fit), and a silver pencil dome at the end that edits the bar. Never fewer
 * than 64 dp per app.
 */
@Composable
internal fun MsWheelButtons(item: DashboardItem.LaunchBar, env: SkinTileEnv) {
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer().msPanel(MsPanel.WHEEL)) {
        val w = maxWidth.value
        val h = maxHeight.value
        val pencilW = 60f
        val room = w - 16f - pencilW
        val pkgs = item.packages.take((room / 64f).toInt().coerceAtLeast(1))
        val per = room / pkgs.size.coerceAtLeast(1)
        val tall = h >= 120f
        val dome = if (tall) min(h * 0.5f, per * 0.7f).coerceIn(36f, 96f) else min(h * 0.66f, 56f).coerceIn(32f, 56f)
        val labelSp = if (tall) (dome * 0.2f).coerceIn(11f, 16f) else (h * 0.16f).coerceIn(11f, 15f)
        val labels = if (tall) h >= dome + labelSp * 1.6f + 14f else per >= dome + 80f
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (pkgs.isEmpty()) {
                MsText(
                    stringResource(R.string.skin_motorsport_launch_bar_empty_caps),
                    msCaps(fixedSp((h * 0.16f).coerceIn(11f, 15f))),
                    Modifier.weight(1f).padding(start = 8.dp),
                    maxLines = 2
                )
            } else {
                pkgs.forEach { pkg ->
                    WheelEntry(env.appsByPackage[pkg], pkg, dome.dp, labels, tall, labelSp, env, Modifier.weight(1f).fillMaxHeight())
                }
            }
            val editLabel = stringResource(R.string.skin_motorsport_edit_launch_bar)
            val tap = rememberTapFeedback()
            Box(
                Modifier
                    .size(pencilW.dp, min(h, 64f).dp.coerceAtLeast(48.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button, onClickLabel = editLabel) { tap(); env.onEditLaunchBar() }
                    .semantics { contentDescription = editLabel },
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(36.dp).glossyDome(MsSilver, halo = false), contentAlignment = Alignment.Center) {
                    MsIcon(Icons.Filled.Edit, MsDomeInk, 18.dp)
                }
            }
        }
    }
}

/** One app of the launch bar: its dome, and its name under it (or beside it) when [label]. */
@Composable
private fun WheelEntry(
    app: AppEntry?,
    pkg: String,
    dome: Dp,
    label: Boolean,
    tall: Boolean,
    labelSp: Float,
    env: SkinTileEnv,
    modifier: Modifier
) {
    val name = appLabel(app, pkg)
    val color = domeColor(pkg)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tap = rememberTapFeedback()
    val openLabel = stringResource(R.string.skin_motorsport_open_app, name)
    val base = modifier
        .clip(RoundedCornerShape(14.dp))
        .clickable(interaction, indication = null, enabled = !env.editing, role = Role.Button, onClickLabel = openLabel) {
            tap()
            env.onLaunchApp(pkg)
        }
        .semantics(mergeDescendants = true) { contentDescription = name }
        .padding(horizontal = 4.dp)
    val text = msCaps(fixedSp(labelSp), DashColors.TextPrimary, FontWeight.ExtraBold)
    if (tall) {
        Column(base, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            AppDome(app, color, dome, pressed, env.editing)
            if (label) {
                Spacer(Modifier.height(6.dp))
                MsText(name.uppercase(), text.copy(textAlign = TextAlign.Center))
            }
        }
    } else {
        Row(base, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            AppDome(app, color, dome, pressed, env.editing)
            if (label) {
                Spacer(Modifier.width(8.dp))
                MsText(name.uppercase(), text, Modifier.weight(1f, fill = false))
            }
        }
    }
}
