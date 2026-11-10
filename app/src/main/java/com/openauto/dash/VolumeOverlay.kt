package com.openauto.dash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Input
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.openauto.dash.vehicle.reversingNow
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

/*
 * Dashwheel's volume bar, in place of the K706 firmware's ([RomPopups]), in the
 * design the driver chose ([AlertStyle]) and dressed like the rest of the look
 * ([AlertSurface]). Shown for a few seconds when the volume is turned with the
 * knob, the steering wheel or Dashwheel's own wheel buttons, the times the
 * firmware showed its own; not when Dashwheel moves it by itself (the volume
 * that follows the speed) or from a volume tile, which shows the level already.
 * The bar can be dragged, as the firmware's could.
 *
 * The firmware's window has no switch: [VolumeRomHide] (GitHub edition) hides
 * it with a resource overlay installed as a root module, which only takes
 * effect at the next start. Dashwheel's bar shows only once that overlay is in
 * place, so the two never show together.
 */

/** What the bar shows: the level out of [max], muted or not, and for which sound. */
data class VolumeShown(val level: Int, val max: Int, val muted: Boolean, val source: VolumeSource)

enum class VolumeSource(val icon: ImageVector, val label: Int) {
    MEDIA(Icons.Filled.MusicNote, R.string.alert_volume_media),
    RADIO(Icons.Filled.Radio, R.string.alert_volume_radio),
    CALL(Icons.Filled.Call, R.string.alert_volume_call),
    AUX(Icons.Filled.Input, R.string.alert_volume_aux);

    companion object {
        /** The firmware's name for the sound its volume applies to now ([MediaVolume.unitSource]). */
        fun of(unitType: String): VolumeSource = when (unitType) {
            "radio_type" -> RADIO
            MediaVolume.UNIT_CALL_SOURCE -> CALL
            "aux_type" -> AUX
            else -> MEDIA
        }
    }
}

object VolumeOverlay {
    private const val TAG = "VolumeOverlay"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var window: AlertWindow? = null
    private var timer: Job? = null

    /** How long the bar stays after the last change, as the firmware's did. */
    private const val SHOW_MS = 3_000L

    /** A change Dashwheel made itself is heard back from the firmware within this. */
    private const val OWN_ECHO_MS = 5_000L

    /** Sent by the firmware for every volume change ("RPC_SetVolume"), with the new level. */
    private const val ACTION_CHANGED = "com.qf.action.VOLUME_CHANGED"
    private const val EXTRA_LEVEL = "EXTRA_VOLUME_VALUE"
    /** Mute on or off. */
    private const val ACTION_MUTE = "com.qf.action.MUTE_EQ"
    private const val EXTRA_MUTE = "extra_mute_status"
    /** The car's own volume keys, through the CAN box: the firmware shows its bar for these too. */
    private const val ACTION_CAN_KEYS = "com.xl.action.canset.vol"

    /** The volume since its last change, while the bar is up; null otherwise. */
    private val shown = MutableStateFlow<VolumeShown?>(null)

    val alert: StateFlow<VolumeShown?> =
        combine(shown, AlertPreview.volume) { volume, preview -> preview ?: volume }
            .stateIn(scope, SharingStarted.Eagerly, null)

    val style: StateFlow<AlertStyle?> =
        combine(alert, AlertStyleStore.styles, UnitSignals.projectionOnScreen) { volume, styles, projected ->
            // Over CarPlay / Android Auto: the pill, so the projection stays usable.
            volume?.let { if (projected) AlertStyle.PILL else styles.of(AlertKind.VOLUME) }
        }
            .arbitrated(AlertKind.VOLUME)
            .stateIn(scope, SharingStarted.Eagerly, null)

    @Volatile private var ownAt = 0L
    @Volatile private var ownShows = false

    /**
     * Dashwheel is about to change the volume ([MediaVolume]): the firmware's
     * echo of it shows the bar only when [show] (a button the driver pressed),
     * not for a change made by itself or from a tile.
     */
    internal fun ownChange(show: Boolean) {
        ownAt = SystemClock.elapsedRealtime()
        ownShows = show
    }

    fun start(context: Context) {
        if (started) return
        started = true
        val app = AppLanguage.wrap(context.applicationContext)
        // What the next broadcast is compared with: the firmware has written its level by then.
        lastHeard = runCatching { MediaVolume.unitNow() }.getOrNull()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = heard(intent)
        }
        val filter = IntentFilter(ACTION_CHANGED).apply {
            addAction(ACTION_MUTE)
            addAction(ACTION_CAN_KEYS)
        }
        // Exported: the firmware is another app. Its broadcasts only make the bar show the level.
        runCatching { ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED) }
            .onFailure { Log.w(TAG, "no volume broadcasts", it) }
        scope.launch {
            style.collect { style ->
                if (style != null) {
                    val w = window ?: AlertWindow(
                        app, "volume", AlertKind.VOLUME.cardAt.gravity,
                        // Turned while reversing: above the camera, where the firmware's showed too.
                        aboveCamera = { reversingNow() },
                        rank = AlertKind.VOLUME.rank
                    ).also { window = it }
                    _showing.value = w.canShow() && w.show(style) { VolumeAlertContent(style) }
                } else {
                    window?.hide()
                    _showing.value = false
                }
            }
        }
    }

    private val _showing = MutableStateFlow(false)
    /** True while the overlay window is up (the launcher's popup then stays away: through the accessibility host it shows above the camera too). */
    val showing: StateFlow<Boolean> = _showing

    /** The volume as last heard from the firmware, to tell a real change from a repeat. */
    @Volatile private var lastHeard: VolumeShown? = null

    private fun heard(intent: Intent) {
        if (RomPopups.Kind.VOLUME !in RomPopups.replaced.value || !VolumeRomHide.active.value) return
        val now = MediaVolume.unitNow()
        val volume = when (intent.action) {
            ACTION_CHANGED -> now.copy(level = intent.getIntExtra(EXTRA_LEVEL, now.level).coerceIn(0, now.max))
            ACTION_MUTE -> now.copy(muted = intent.getBooleanExtra(EXTRA_MUTE, now.muted))
            else -> now
        }
        val before = lastHeard ?: now
        lastHeard = volume
        val own = SystemClock.elapsedRealtime() - ownAt < OWN_ECHO_MS
        if (own && !ownShows) return
        if (!worthShowing(before, volume, carKeys = intent.action == ACTION_CAN_KEYS)) return
        show(volume)
    }


    /** Puts [volume] up, or keeps the bar up with it, for [SHOW_MS] more. */
    internal fun show(volume: VolumeShown) {
        scope.launch {
            shown.value = volume
            timer?.cancel()
            timer = launch {
                delay(SHOW_MS)
                shown.value = null
            }
        }
    }

    /** The driver dragged the bar to [level]. */
    internal fun drag(context: Context, level: Int) {
        val now = shown.value ?: return
        val to = level.coerceIn(0, now.max)
        if (to == now.level && !now.muted) return
        show(now.copy(level = to, muted = to == 0))
        MediaVolume.set(context, to, show = true)
    }
}

@Composable
private fun VolumeAlertContent(style: AlertStyle) {
    val live by VolumeOverlay.alert.collectAsState()
    val volume = rememberLast(live) ?: return
    VolumeAlert(volume, style)
}

@Composable
internal fun VolumeAlert(v: VolumeShown, style: AlertStyle) = when (style) {
    AlertStyle.PILL -> VolumePill(v)
    AlertStyle.BANNER -> VolumeBanner(v)
    AlertStyle.PANEL -> VolumePanel(v)
    else -> VolumeCard(v)
}

/** The accent, or the warning colour while muted. */
@Composable
private fun VolumeShown.tone(): Color = if (muted) DashColors.Warning else DashColors.Accent

@Composable
private fun VolumeShown.icon(): ImageVector = if (muted) Icons.AutoMirrored.Filled.VolumeOff else source.icon

private fun VolumeShown.fraction(): Float = if (muted) 0f else level.toFloat() / max.coerceAtLeast(1)

@Composable
private fun VolumeNumber(v: VolumeShown, size: Int) {
    Text(
        if (v.muted) "0" else v.level.toString(),
        color = if (v.muted) DashColors.Warning else DashColors.TextPrimary,
        fontWeight = FontWeight.Bold,
        fontSize = size.sp
    )
}

@Composable
private fun VolumeLabel(v: VolumeShown) {
    Text(
        stringResource(if (v.muted) R.string.alert_volume_muted else v.source.label).uppercase(),
        color = v.tone(), letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelMedium
    )
}

/**
 * The level as a bar the driver can tap or drag, the way the firmware's
 * slider worked. Across, or up the side panel when [vertical].
 */
@Composable
private fun VolumeBar(v: VolumeShown, thickness: Dp, modifier: Modifier = Modifier, vertical: Boolean = false) {
    val context = LocalContext.current
    val current by rememberUpdatedState(v)
    BoxWithConstraints(modifier.padding(vertical = if (vertical) 0.dp else 10.dp, horizontal = if (vertical) 10.dp else 0.dp)) {
        val length = if (vertical) constraints.maxHeight.toFloat() else constraints.maxWidth.toFloat()
        fun levelAt(px: Float): Int {
            val f = (if (vertical) 1f - px / length else px / length).coerceIn(0f, 1f)
            return Math.round(f * current.max)
        }
        val touch = Modifier
            .pointerInput(vertical) { detectTapGestures { VolumeOverlay.drag(context, levelAt(if (vertical) it.y else it.x)) } }
            .pointerInput(vertical) {
                if (vertical) detectVerticalDragGestures { change, _ -> VolumeOverlay.drag(context, levelAt(change.position.y)) }
                else detectHorizontalDragGestures { change, _ -> VolumeOverlay.drag(context, levelAt(change.position.x)) }
            }
        val shape = DashShape.Pill
        val track = if (vertical) Modifier.width(thickness).fillMaxHeight() else Modifier.height(thickness).fillMaxWidth()
        Box(
            // The whole box takes the touch, a finger's height around the thin track.
            (if (vertical) Modifier.fillMaxHeight().width(thickness + 36.dp) else Modifier.fillMaxWidth().height(thickness + 36.dp)).then(touch),
            contentAlignment = if (vertical) Alignment.BottomCenter else Alignment.CenterStart
        ) {
            Box(track.background(DashColors.CardHi, shape), contentAlignment = if (vertical) Alignment.BottomCenter else Alignment.CenterStart) {
                val f = v.fraction()
                if (f > 0f) {
                    Box(
                        (if (vertical) Modifier.width(thickness).fillMaxHeight(f) else Modifier.height(thickness).fillMaxWidth(f))
                            .background(v.tone(), shape)
                    )
                }
            }
        }
    }
}

@Composable
private fun VolumePill(v: VolumeShown) {
    AlertSurface(AlertStyle.PILL, tone = v.tone()) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(v.icon(), contentDescription = null, tint = v.tone(), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            VolumeBar(v, 6.dp, Modifier.width(180.dp))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.widthIn(min = 28.dp), contentAlignment = Alignment.CenterEnd) { VolumeNumber(v, 18) }
        }
    }
}

@Composable
private fun VolumeCard(v: VolumeShown) {
    AlertSurface(AlertStyle.CARD, tone = v.tone()) {
        Row(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp).widthIn(min = 420.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(v.icon(), contentDescription = null, tint = v.tone(), modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.width(280.dp)) {
                VolumeLabel(v)
                VolumeBar(v, 8.dp)
            }
            Spacer(Modifier.width(18.dp))
            Box(Modifier.widthIn(min = 52.dp), contentAlignment = Alignment.CenterEnd) { VolumeNumber(v, 34) }
        }
    }
}

@Composable
private fun VolumeBanner(v: VolumeShown) {
    AlertSurface(AlertStyle.BANNER, tone = v.tone()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Icon(v.icon(), contentDescription = null, tint = v.tone(), modifier = Modifier.size(30.dp))
            Box(Modifier.widthIn(min = 120.dp)) { VolumeLabel(v) }
            VolumeBar(v, 8.dp, Modifier.weight(1f))
            Box(Modifier.widthIn(min = 52.dp), contentAlignment = Alignment.CenterEnd) { VolumeNumber(v, 30) }
        }
    }
}

@Composable
private fun VolumePanel(v: VolumeShown) {
    AlertSurface(AlertStyle.PANEL, tone = v.tone()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            VolumeLabel(v)
            Spacer(Modifier.height(10.dp))
            VolumeNumber(v, 64)
            Spacer(Modifier.height(16.dp))
            VolumeBar(v, 14.dp, Modifier.weight(1f), vertical = true)
            Spacer(Modifier.height(16.dp))
            Icon(v.icon(), contentDescription = null, tint = v.tone(), modifier = Modifier.size(36.dp))
        }
    }
}

/** The launcher's own volume bar, for when the overlay window isn't allowed. */
@Composable
internal fun VolumeHost() {
    val volume by VolumeOverlay.alert.collectAsState()
    val style by VolumeOverlay.style.collectAsState()
    val overlay by VolumeOverlay.showing.collectAsState()
    val v = volume ?: return
    val s = style ?: return
    // Not when the window is up, whichever way it got there (permission or the accessibility host): one bar, not two.
    if (overlay) return
    AlertPopup(s, AlertKind.VOLUME.cardAt.alignment) { VolumeAlert(v, s) }
}

/**
 * Whether a volume broadcast is worth the bar ([VolumeOverlay]): the level,
 * the mute or the source changed, or the car's own volume keys were pressed
 * (the end of the scale shows too). The firmware also sends its "changed",
 * same level, whenever an app takes the sound: Dashwheel's voice or Gemini
 * speaking brought the bar up for nothing.
 */
internal fun worthShowing(before: VolumeShown, now: VolumeShown, carKeys: Boolean): Boolean =
    carKeys || before.level != now.level || before.muted != now.muted || before.source != now.source
