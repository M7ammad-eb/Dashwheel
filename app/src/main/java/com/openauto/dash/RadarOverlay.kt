package com.openauto.dash

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Vehicle
import com.openauto.dash.vehicle.reversingFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.min

/*
 * Dashwheel's parking radar, in place of the car app's ([RomPopups]), in the
 * design the driver chose ([AlertStyle]; never full screen, it would hide the
 * reversing camera): the car from above with an arc per sensor, closer to the
 * car and redder as something gets near. Up while reversing, or while any
 * sensor sees something on a car with front or side sensors; gone a second after. Out of reverse it also goes
 * once nothing has come closer for a while (the car stopped by a wall with its
 * radar on), or at a tap on it or beside it, and comes back when something
 * gets closer than it was, or in reverse. Drawn through the accessibility
 * service, the only window Dashwheel can put above the ROM's reversing camera
 * ([AlertWindow]).
 */

object RadarOverlay {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var window: AlertWindow? = null
    /** Where [window] was built to sit: a new one when the driver moves the radar (#124). */
    private var windowAt: CardAt? = null
    private var hiding: Job? = null

    /** A second without anything near before the radar goes, so it doesn't flicker. */
    private const val LINGER_MS = 1_000L

    /** Out of reverse, how long the radar stays with nothing coming closer. */
    private const val QUIET_MS = 8_000L

    /** The car's radar as it would show, before the driver sends it away. */
    private val carRadar: StateFlow<Radar?> =
        combine(RomPopups.replaced, Vehicle.bus.value(K.RADAR), reversingFlow(), ReverseView.covering) { replaced, radar, reversing, reverseView ->
            // The reverse view has its own radar. Out of reverse, only for a car with
            // front or side sensors: rear ones alone have nothing ahead to show (#157).
            radar.takeIf {
                !reverseView && RomPopups.Kind.RADAR in replaced && it != null && it.present && (reversing || (it.active && it.seesAhead))
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Sent away (a tap, or quiet for [QUIET_MS]) at this closest level: the
     * radar stays away until something is closer than that. Null: shown.
     */
    private val snoozedAt = MutableStateFlow<Int?>(null)

    /** How close the nearest thing is, 1 the closest; past [Radar.MAX_LEVEL] for nothing near. */
    private fun level(r: Radar): Int = r.closest ?: (Radar.MAX_LEVEL + 1)

    /** The sensors to show, or null when there's no radar up: the picker's made-up ones while it is tried. */
    val alert: StateFlow<Radar?> =
        combine(carRadar, snoozedAt, AlertPreview.radar) { radar, snooze, preview ->
            preview ?: radar?.takeIf { snooze == null || level(it) < snooze }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** A tap on the radar or beside it: away until something gets closer. */
    fun dismiss() {
        carRadar.value?.let { snoozedAt.value = level(it) }
    }

    val style: StateFlow<AlertStyle?> =
        combine(alert, AlertStyleStore.styles) { radar, styles -> radar?.let { styles.of(AlertKind.RADAR) } }
            .arbitrated(AlertKind.RADAR)
            .stateIn(scope, SharingStarted.Eagerly, null)

    fun start(context: Context) {
        if (started) return
        started = true
        CarPaintStore.load(context)
        val app = AppLanguage.wrap(context.applicationContext)
        // Out of reverse, the radar goes once nothing has come closer for a while.
        scope.launch {
            var wasReversing = false
            var nearest = Int.MAX_VALUE
            var quiet: Job? = null
            combine(carRadar, reversingFlow()) { r, reversing -> r to reversing }.collect { (r, reversing) ->
                // Gone, or a new reverse: whatever was sent away comes back next time.
                if (r == null || (reversing && !wasReversing)) snoozedAt.value = null
                wasReversing = reversing
                if (r == null || reversing) {
                    quiet?.cancel()
                    quiet = null
                    nearest = Int.MAX_VALUE
                    return@collect
                }
                val l = level(r)
                if (l < nearest || quiet == null) {
                    nearest = l
                    quiet?.cancel()
                    quiet = launch {
                        delay(QUIET_MS)
                        carRadar.value?.let { snoozedAt.value = level(it) }
                        nearest = Int.MAX_VALUE
                        quiet = null
                    }
                }
            }
        }
        scope.launch {
            combine(style, AlertStyleStore.radarAt) { s, at -> s to at }.collect { (style, chosen) ->
                if (style != null) {
                    hiding?.cancel()
                    val at = placementOf(AlertKind.RADAR, chosen, app)
                    if (at != windowAt) {
                        window?.removeNow()
                        window = null
                        windowAt = at
                    }
                    val w = window ?: AlertWindow(
                        app, "radar", at.gravity, aboveCamera = { true }, rank = AlertKind.RADAR.rank,
                        onOutsideTap = ::dismiss
                    ).also { window = it }
                    if (w.canShow()) w.show(style) { RadarAlertContent(style) }
                } else if (hiding?.isActive != true) {
                    hiding = launch {
                        delay(LINGER_MS)
                        window?.hide()
                    }
                }
            }
        }
    }
}

@Composable
internal fun RadarAlertContent(style: AlertStyle) {
    val live by RadarOverlay.alert.collectAsState()
    val radar = rememberLast(live) ?: return
    // A tap on it sends it away, as one beside it does (AlertWindow's onOutsideTap).
    Box(Modifier.clickable(interactionSource = null, indication = null, onClick = RadarOverlay::dismiss)) { RadarAlert(radar, style) }
}

@Composable
private fun RadarAlert(radar: Radar, style: AlertStyle) = when (style) {
    AlertStyle.PILL -> RadarPill(radar)
    AlertStyle.PANEL -> RadarPanel(radar)
    else -> RadarCard(radar)
}

/** A level's colour: red when close, amber, then green far away; faint when clear. */
@Composable
private fun levelColor(level: Int?): Color = when {
    level == null || level !in 1..Radar.MAX_LEVEL -> DashColors.Muted.copy(alpha = 0.25f)
    level <= 3 -> DashColors.Critical
    level <= 6 -> DashColors.Warning
    else -> DashColors.Good
}

/** The nearest side, for the pill: the rear when reversing and both see something. */
@Composable
private fun nearestLabel(radar: Radar): String {
    fun min(l: List<Int?>) = l.filterNotNull().filter { it in 1..Radar.MAX_LEVEL }.minOrNull() ?: Int.MAX_VALUE
    return stringResource(if (min(radar.front) < min(radar.rear)) R.string.alert_radar_front else R.string.alert_radar_rear)
}

@Composable
private fun RadarPill(radar: Radar) {
    val closest = radar.closest
    AlertSurface(AlertStyle.PILL, tone = levelColor(closest)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Sensors, contentDescription = null, tint = levelColor(closest), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                if (closest != null) nearestLabel(radar) else stringResource(R.string.alert_radar_title),
                color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.width(12.dp))
            // Fuller as something gets closer.
            val fill = if (closest == null) 0f else (Radar.MAX_LEVEL + 1 - closest) / Radar.MAX_LEVEL.toFloat()
            val bar = if (alertSquare) DashShape.Small else DashShape.Pill
            Box(Modifier.width(90.dp).height(8.dp).background(DashColors.CardHi, bar)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(levelColor(closest), bar))
            }
        }
    }
}

@Composable
private fun RadarCard(radar: Radar) {
    AlertSurface(AlertStyle.CARD, tone = levelColor(radar.closest)) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RadarFromAbove(radar, Modifier.size(width = 130.dp, height = 240.dp))
        }
    }
}

@Composable
private fun RadarPanel(radar: Radar) {
    AlertSurface(AlertStyle.PANEL, tone = levelColor(radar.closest)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.alert_radar_title).uppercase(), color = DashColors.Accent, letterSpacing = 1.5.sp,
                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge
            )
            RadarFromAbove(radar, Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp))
            if (radar.closest != null) {
                Text(nearestLabel(radar), color = levelColor(radar.closest), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}

/**
 * The car from above (doors shut) with an arc for each sensor it has: front
 * sensors above, rear below, left to right. An arc sits closer to the bumper
 * and turns red as its sensor's level drops towards 1 (closest). The driver's
 * own car when it has a view from above (MyCarLook.kt), its bumpers where the
 * drawn car's are.
 */
@Composable
internal fun RadarFromAbove(radar: Radar, modifier: Modifier = Modifier) {
    // A translucent CardHi (the glass and skinned looks) laid on the card, not made opaque as it is: that is white.
    // In the paint the driver chose (CarPaint), else the theme's.
    val paint by CarPaintStore.paint.collectAsState()
    val (body, glass, edge) = paint.paint(
        DashColors.CardHi.compositeOver(DashColors.Card.copy(alpha = 1f)), DashColors.TextSecondary.copy(alpha = 0.24f), DashColors.TextSecondary
    )
    val warn = DashColors.Warning
    val front = radar.front.map { it to levelColor(it) }
    val rear = radar.rear.map { it to levelColor(it) }
    val mine by MyCarLook.shown.collectAsState()
    val style by MyCarLook.style.collectAsState()
    val own = mine?.top
    val ink = DashColors.Accent
    Canvas(modifier) {
        val s = min(size.width / VIEW_W, size.height / VIEW_H)
        translate((size.width - VIEW_W * s) / 2f, (size.height - VIEW_H * s) / 2f) {
            // The own car is drawn at screen scale (sharp), on the drawn car's 70 x 190 body.
            if (own != null) drawCarView(own, style, fitCarOn(own, Rect(35f * s, 35f * s, 105f * s, 225f * s)), ink, warn)
            withTransform({ scale(s, s, Offset.Zero) }) {
                if (own == null) drawCar(body, glass, edge, warn, 0f, 0f, 0f, 0f, 0f, 0f)
                sensorArcs(front, center = Offset(70f, 100f), from = 215f, reach = 1f)
                sensorArcs(rear, center = Offset(70f, 160f), from = 145f, reach = -1f)
            }
        }
    }
}

/**
 * One arc per sensor around [center], left to right: the front ones sweep
 * clockwise from [from] over the top ([reach] 1), the rear ones the other
 * way under the car (-1).
 */
private fun DrawScope.sensorArcs(sensors: List<Pair<Int?, Color>>, center: Offset, from: Float, reach: Float) {
    // Narrow enough that the outermost arcs stay inside the 140-wide drawing.
    val span = 110f
    val step = span / sensors.size
    sensors.forEachIndexed { i, (level, color) ->
        if (level == null) return@forEachIndexed
        val near = level in 1..Radar.MAX_LEVEL
        // Closest (1) hugs the bumper; far (10) and clear sit at the outside.
        val radius = if (near) 66f + (level - 1) * 2.5f else 90f
        val start = if (reach > 0) from + i * step + 1.5f else from - (i + 1) * step + 1.5f
        drawArc(
            color, startAngle = start, sweepAngle = step - 3f, useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius), size = Size(radius * 2, radius * 2),
            style = Stroke(width = if (near) 9f else 5f)
        )
    }
}

/** The launcher's own radar popup, for when neither overlay window is possible. */
@Composable
internal fun RadarHost() {
    val radar by RadarOverlay.alert.collectAsState()
    val style by RadarOverlay.style.collectAsState()
    val r = radar ?: return
    val s = style ?: return
    if (SplitAccessibilityService.isConnected || android.provider.Settings.canDrawOverlays(androidx.compose.ui.platform.LocalContext.current)) return
    AlertPopup(s, placementOf(AlertKind.RADAR).alignment) { RadarAlert(r, s) }
}
