package com.openauto.dash

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.openauto.dash.link.CallState
import com.openauto.dash.roadwatch.RoadWarning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableIntStateOf

/*
 * How Dashwheel's own alerts look, chosen per alert in Settings, Look: the
 * phone's calls ([PhoneCallOverlay]) and the open doors ([DoorAlertOverlay]).
 * The chosen design is dressed in the dashboard's look (AlertChrome.kt).
 * Each shows in an overlay window of its own ([AlertWindow]) over whatever app
 * fills the screen, or inside the launcher without "display over other apps"
 * ([AlertPopup]).
 */

/** The designs an alert can take, from the least in the way to the most. */
enum class AlertStyle(@StringRes val title: Int, @StringRes val detail: Int) {
    PILL(R.string.alert_style_pill, R.string.alert_style_pill_detail),
    CARD(R.string.alert_style_card, R.string.alert_style_card_detail),
    BANNER(R.string.alert_style_banner, R.string.alert_style_banner_detail),
    PANEL(R.string.alert_style_panel, R.string.alert_style_panel_detail),
    FULL(R.string.alert_style_full, R.string.alert_style_full_detail),
    // Gemini Live's alone so far (AlertKind.GEMINI): round, in the corner, the conversation by voice only.
    BUBBLE(R.string.alert_style_bubble, R.string.alert_style_bubble_detail),
    ICON(R.string.alert_style_icon, R.string.alert_style_icon_detail);

    companion object {
        /** The designs every alert can take, from the pill to full screen. */
        val CLASSIC = listOf(PILL, CARD, BANNER, PANEL, FULL)
    }
}

/**
 * The alerts whose design can be chosen, each with the designs that suit it
 * (the radar never covers the reversing camera) and whether it can be said
 * out loud ([AlertVoice]).
 */
enum class AlertKind(
    val key: String,
    val styles: List<AlertStyle>,
    val speakable: Boolean,
    val cardAt: CardAt,
    /** Its design until one is chosen. */
    val default: AlertStyle = AlertStyle.CARD
) {
    CALL("call", AlertStyle.CLASSIC, speakable = true, cardAt = CardAt.TOP),
    DOORS("doors", AlertStyle.CLASSIC, speakable = true, cardAt = CardAt.TOP_END),
    RADAR("radar", listOf(AlertStyle.PILL, AlertStyle.CARD, AlertStyle.PANEL), speakable = false, cardAt = CardAt.END),
    // Shown at every fan click: never a design that covers the map.
    AC("ac", listOf(AlertStyle.PILL, AlertStyle.CARD, AlertStyle.BANNER), speakable = false, cardAt = CardAt.BOTTOM),
    TYRES("tyres", AlertStyle.CLASSIC, speakable = true, cardAt = CardAt.TOP_END),
    // Shown while driving: never anything that covers the screen.
    BELT("belt", listOf(AlertStyle.PILL, AlertStyle.CARD, AlertStyle.BANNER), speakable = true, cardAt = CardAt.TOP),
    // Gemini Live's conversation (GeminiLive): its own screen in the card, the panel or full screen;
    // the icon, the bubble and the pill only say it listens.
    GEMINI("gemini", listOf(AlertStyle.ICON, AlertStyle.BUBBLE, AlertStyle.PILL, AlertStyle.CARD, AlertStyle.PANEL, AlertStyle.FULL), speakable = false, cardAt = CardAt.TOP_END, default = AlertStyle.PANEL),
    // Where the firmware's own volume bar was: top centre.
    VOLUME("volume", listOf(AlertStyle.PILL, AlertStyle.CARD, AlertStyle.BANNER, AlertStyle.PANEL), speakable = false, cardAt = CardAt.TOP),
    // Road watch, from the phone's camera (RoadAlertOverlay): shown while driving, so never over the screen; big by default.
    ROAD("road", listOf(AlertStyle.PILL, AlertStyle.CARD, AlertStyle.BANNER), speakable = true, cardAt = CardAt.TOP, default = AlertStyle.BANNER);

    /**
     * Which comes first when several are up at once, the lowest ahead: what
     * the driver must act on now (something behind the car, a call ringing,
     * the belt) before what can be looked at later. A conversation with Gemini
     * is not shrunk by a fan click. Road watch's warnings come first: the car
     * ahead coming up fast can't wait for anything.
     */
    val rank: Int
        get() = when (this) {
            ROAD -> 0
            RADAR -> 1
            CALL -> 2
            BELT -> 3
            DOORS -> 4
            TYRES -> 5
            GEMINI -> 6
            AC -> 7
            VOLUME -> 8
        }
}

/**
 * Several alerts at once. The one that matters most ([AlertKind.rank]) keeps
 * its design; one behind it shrinks to the pill when either of the two would
 * cover the other (the side panel, the full screen). The small designs that
 * are left line up instead of sitting on one another ([AlertWindow]).
 */
internal object AlertArbiter {
    private val _wanted = MutableStateFlow<Map<AlertKind, AlertStyle>>(emptyMap())
    /** The alerts up now, each in the design it asked for. */
    val wanted: StateFlow<Map<AlertKind, AlertStyle>> = _wanted

    fun want(kind: AlertKind, style: AlertStyle?) = _wanted.update { if (style == null) it - kind else it + (kind to style) }
}

/** The designs that take a side or the whole of the screen. */
private val COVERING = setOf(AlertStyle.PANEL, AlertStyle.FULL)

/** The design [kind] shows in, having asked for [wanted], given the alerts [up] now. */
internal fun arbitratedStyle(kind: AlertKind, wanted: AlertStyle, up: Map<AlertKind, AlertStyle>): AlertStyle {
    val ahead = up.filterKeys { it.rank < kind.rank }
    return if (ahead.isNotEmpty() && (wanted in COVERING || ahead.values.any { it in COVERING })) AlertStyle.PILL else wanted
}

/** [kind]'s design as the other alerts allow it ([AlertArbiter]); upstream is the design it asks for. */
internal fun Flow<AlertStyle?>.arbitrated(kind: AlertKind): Flow<AlertStyle?> =
    onEach { AlertArbiter.want(kind, it) }
        .combine(AlertArbiter.wanted) { mine, up -> mine?.let { arbitratedStyle(kind, it, up) } }
        .distinctUntilChanged()

/** On the move, a design that covers the map shrinks to the pill after this long. */
internal const val SHRINK_AFTER_MS = 8_000L

/**
 * The design shown for [chosen] while driving: one of the [big] ones (the
 * side panel, the full screen) is the pill once the car moves and it has been
 * up [bigForMs], so no alert stays over the map until it is tapped. Parked,
 * the chosen design stays.
 */
internal fun drivingStyle(
    chosen: AlertStyle,
    moving: Boolean,
    bigForMs: Long,
    big: Set<AlertStyle> = COVERING
): AlertStyle = when {
    chosen !in big -> chosen
    moving && bigForMs > SHRINK_AFTER_MS -> AlertStyle.PILL
    else -> chosen
}

/** Whether the car moves ([isMoving]), going by the speed the dashboard shows. */
internal fun carMoving(): Flow<Boolean> =
    carSpeedKmh().runningFold(false) { was, kmh -> isMoving(kmh, was) }.distinctUntilChanged()

/**
 * One alert's big design on the move ([drivingStyle]): it shrinks to the pill
 * a few seconds after it came up, and a tap on that pill ([enlarge]) brings
 * it back for as long again.
 */
internal class ShrinkOnTheMove {
    private val enlargedAt = MutableStateFlow(0L)

    private val _shrunk = MutableStateFlow(false)
    /** The pill on screen is the big design shrunk: a tap brings it back instead of hiding the alert. */
    val shrunk: StateFlow<Boolean> = _shrunk

    fun enlarge() {
        enlargedAt.value = SystemClock.elapsedRealtime()
    }

    /** [chosen] (null: no alert) as the drive allows it now. */
    fun styled(chosen: Flow<AlertStyle?>): Flow<AlertStyle?> = channelFlow {
        var up = false
        // When the alert came up, or was last made big again from the pill (elapsedRealtime).
        var bigSince = 0L
        combine(chosen, carMoving(), enlargedAt) { style, moving, enlarged -> Triple(style, moving, enlarged) }
            .collectLatest { (style, moving, enlarged) ->
                val at = SystemClock.elapsedRealtime()
                if (style != null && !up) bigSince = at
                up = style != null
                bigSince = maxOf(bigSince, enlarged)
                val now = style?.let { drivingStyle(it, moving, at - bigSince) }
                _shrunk.value = now != style
                send(now)
                // The shrink comes with time, not with an event.
                val later = style?.let { drivingStyle(it, moving, SHRINK_AFTER_MS + 1) }
                if (later != now) {
                    delay(SHRINK_AFTER_MS + 1 - (at - bigSince))
                    _shrunk.value = true
                    send(later)
                }
            }
    }
}

/**
 * Where an alert's card sits: the call at the top, the doors in the corner,
 * the radar at the side of the reversing camera's picture, the climate at the
 * bottom like the car's own bar.
 */
enum class CardAt(val gravity: Int, val alignment: Alignment) {
    TOP(Gravity.TOP or Gravity.CENTER_HORIZONTAL, Alignment.TopCenter),
    TOP_END(Gravity.TOP or Gravity.END, Alignment.TopEnd),
    START(Gravity.START or Gravity.CENTER_VERTICAL, Alignment.CenterStart),
    END(Gravity.END or Gravity.CENTER_VERTICAL, Alignment.CenterEnd),
    BOTTOM(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, Alignment.BottomCenter)
}

object AlertStyleStore {
    private const val PREFS = "alert_styles"
    /** Said out loud until the driver says otherwise. */
    private val SPOKEN_FIRST = setOf(AlertKind.DOORS, AlertKind.TYRES, AlertKind.BELT, AlertKind.ROAD)

    private val _styles = MutableStateFlow<Map<AlertKind, AlertStyle>>(emptyMap())
    val styles: StateFlow<Map<AlertKind, AlertStyle>> = _styles

    private val _radarAt = MutableStateFlow<CardAt?>(null)
    /** Where the driver put the parking radar's card; null until they do ([placementOf]). */
    val radarAt: StateFlow<CardAt?> = _radarAt

    private val _spoken = MutableStateFlow(SPOKEN_FIRST)
    /** The alerts also said out loud ([AlertVoice]); the doors until the driver says otherwise. */
    val spoken: StateFlow<Set<AlertKind>> = _spoken

    // The phone's messages (shown and said) are switched with the rest of what the car says: SpokenEvent.MESSAGES.

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _styles.value = AlertKind.entries.mapNotNull { kind ->
            p.getString(kind.key, null)?.let { name -> AlertStyle.entries.firstOrNull { it.name == name } }?.let { kind to it }
        }.toMap()
        _spoken.value = AlertKind.entries.filter { p.getBoolean("speak_${it.key}", it in SPOKEN_FIRST) }.toSet()
        _radarAt.value = p.getString("at_radar", null)?.let { name -> CardAt.entries.firstOrNull { it.name == name } }
        SpokenEvents.load(context)
    }

    fun set(context: Context, kind: AlertKind, style: AlertStyle) {
        _styles.update { it + (kind to style) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(kind.key, style.name).apply()
    }

    fun setRadarAt(context: Context, at: CardAt) {
        _radarAt.value = at
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("at_radar", at.name).apply()
    }

    fun setSpoken(context: Context, kind: AlertKind, on: Boolean) {
        _spoken.update { if (on) it + kind else it - kind }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("speak_${kind.key}", on).apply()
    }
}

/**
 * Where [kind]'s card sits: the parking radar where the driver put it ([chosen],
 * [AlertStyleStore.radarAt]), else beside a landscape camera picture and under a
 * portrait one, where the right edge would cross it (#124); the rest in their fixed place.
 */
fun placementOf(kind: AlertKind, chosen: CardAt?, portrait: Boolean): CardAt =
    if (kind != AlertKind.RADAR) kind.cardAt else chosen ?: if (portrait) CardAt.BOTTOM else CardAt.END

fun placementOf(kind: AlertKind, chosen: CardAt?, context: Context): CardAt =
    placementOf(kind, chosen, context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)

/** [placementOf] as the screen stands, following the driver's choice. */
@Composable
fun placementOf(kind: AlertKind): CardAt {
    val chosen by AlertStyleStore.radarAt.collectAsState()
    return placementOf(kind, chosen, LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT)
}

/** [kind]'s chosen design; its default until one is chosen (or when the chosen one doesn't suit it). */
fun Map<AlertKind, AlertStyle>.of(kind: AlertKind): AlertStyle = this[kind]?.takeIf { it in kind.styles } ?: kind.default

/**
 * "Try it" in the style picker: a made-up call or open doors for a few
 * seconds, in the chosen design, over whatever is on screen, and said out
 * loud when that alert is spoken.
 */
object AlertPreview {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    val call = MutableStateFlow<PhoneCall?>(null)
    val doors = MutableStateFlow<McuReader.DoorState?>(null)
    val radar = MutableStateFlow<Radar?>(null)
    val climate = MutableStateFlow<Climate?>(null)
    val tyres = MutableStateFlow<Map<TyrePos, Tyre>?>(null)
    val belt = MutableStateFlow(false)
    val gemini = MutableStateFlow(false)
    val volume = MutableStateFlow<VolumeShown?>(null)
    val road = MutableStateFlow<RoadWarning?>(null)

    private const val SHOW_MS = 8_000L

    fun show(context: Context, kind: AlertKind) {
        stop()
        when (kind) {
            AlertKind.CALL -> PhoneCall(
                CallState.Phase.RINGING, number = "06 12 34 56 78", name = "Alex Martin", photo = null,
                answeredAt = 0, canControl = true, preview = true
            ).let {
                call.value = it
                AlertVoice.sayCall(context, it, force = true)
            }
            AlertKind.DOORS -> McuReader.DoorState(frontLeft = true, tailgate = true).let {
                doors.value = it
                AlertVoice.sayDoors(context, it.openNames(), force = true)
            }
            AlertKind.RADAR -> radar.value = CarBox.sampleRadar()
            AlertKind.AC -> climate.value = CarBox.sampleClimate()
            AlertKind.TYRES -> Tyres.sample().let {
                tyres.value = it
                AlertVoice.sayTyre(context, TyrePos.FRONT_LEFT, TyreProblem.LOW, force = true)
            }
            AlertKind.BELT -> {
                belt.value = true
                AlertVoice.sayBelt(context, force = true)
            }
            AlertKind.GEMINI -> gemini.value = true
            AlertKind.VOLUME -> volume.value = VolumeShown(12, MediaVolume.UNIT_MAX, muted = false, VolumeSource.MEDIA)
            AlertKind.ROAD -> RoadWarning.COLLISION.let {
                road.value = it
                RoadAlertOverlay.sound(context, it, force = true)
            }
        }
        job = scope.launch {
            delay(SHOW_MS)
            clear()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        clear()
    }

    private fun clear() {
        call.value = null
        doors.value = null
        radar.value = null
        climate.value = null
        tyres.value = null
        belt.value = false
        gemini.value = false
        volume.value = null
        road.value = null
    }
}

/** The side panel's width: a good third of the screen, never narrower than a phone. */
internal fun panelWidthDp(screenWidthDp: Int): Dp = max(380f, screenWidthDp * 0.36f).dp

/**
 * One alert's overlay window. Its size and place follow the design; showing
 * and hiding animate, and the window goes once the alert has animated out.
 */
internal class AlertWindow(
    private val context: Context,
    private val name: String,
    /** Where the card design sits: the call card top centre, the door card in the corner. */
    private val cardGravity: Int,
    /**
     * Whether to draw it through the accessibility service when that's on:
     * the only window Dashwheel can put above the ROM's reversing camera (an
     * app overlay sits under it). Asked at each [show]: the radar always, a
     * call while reversing. Such a window needs no "display over other apps".
     */
    private val aboveCamera: () -> Boolean = { false },
    /** The panel and full-screen designs stop above the dashboard's bar, so its buttons stay in reach. */
    private val clearOfBar: Boolean = false,
    /** Where it stands among the alerts up at once ([AlertKind.rank]); the reverse view is ahead of them all. */
    val rank: Int = -1,
    /** A tap anywhere beside the alert (the screen under it still gets it); null: none watched. */
    private val onOutsideTap: (() -> Unit)? = null
) {
    /** The shown window is the accessibility service's. */
    private var above = false
    private var wm: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var style: AlertStyle? = null
    private var visible: MutableTransitionState<Boolean>? = null
    // The design's own distance from the top, before any lining up ([settleUnder]).
    private var ownY = 0

    /** Shows [content] in [style]'s window (a new one when the design changed); false when it can't be added. */
    fun show(style: AlertStyle, content: @Composable () -> Unit): Boolean {
        val host = if (aboveCamera()) SplitAccessibilityService.overlayHost() else null
        if (view != null && this.style == style && above == (host != null)) {
            visible?.targetState = true
            return true
        }
        removeNow()
        val hostContext = host ?: context
        wm = hostContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val type = if (host != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        // Set to show before the view is attached: its first composition may run inside addView.
        val state = MutableTransitionState(false).apply { targetState = true }
        val o = OverlayOwner()
        val v = ComposeView(hostContext)
        v.setViewTreeLifecycleOwner(o)
        v.setViewTreeSavedStateRegistryOwner(o)
        // The full screen and the side panel run from the very top of the screen. Their content
        // sits under the status bar while one shows and flush with the top while the bar is
        // hidden (the dashboard hides it): the
        // window's visible frame starts under the bar only then (an overlay window gets no
        // content inset for it, and no insets callback when the bar comes or goes, but a layout
        // pass). The full screen has black behind, for a firmware that hides the bar by its own
        // means and still reports it: never the app below.
        val barTop = mutableIntStateOf(0)
        if (style == AlertStyle.FULL || style == AlertStyle.PANEL) {
            v.viewTreeObserver.addOnGlobalLayoutListener {
                barTop.intValue = android.graphics.Rect().also(v::getWindowVisibleDisplayFrame).top.coerceAtLeast(0)
            }
        }
        v.setContent {
            OpenAutoDashTheme {
                // Asked back while it animated out: it stays.
                AlertMotion(style, state, onGone = { v.post { if (view === v && visible?.targetState == false) removeNow() } }) {
                    val underBar = with(LocalDensity.current) { barTop.intValue.toDp() }
                    when (style) {
                        AlertStyle.FULL -> Box(Modifier.fillMaxSize().background(Color.Black).padding(top = underBar)) { content() }
                        AlertStyle.PANEL -> Box(Modifier.fillMaxSize().padding(top = underBar)) { content() }
                        else -> content()
                    }
                }
            }
        }
        v.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob -> if (r - l != or - ol || b - t != ob - ot) AlertStack.settle() }
        onOutsideTap?.let { tap ->
            v.setOnTouchListener { _, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE) tap()
                false
            }
        }
        return runCatching { wm.addView(v, params(style, type).also { ownY = it.y }) }
            .onSuccess { view = v; owner = o; this.style = style; visible = state; above = host != null; AlertStack.add(this) }
            .onFailure { Log.w(TAG, "could not add the $name window", it); o.destroy() }
            .isSuccess
    }

    /** Animates the alert out; the window goes after. */
    fun hide() {
        if (view == null) return
        visible?.targetState = false
    }

    fun removeNow() {
        val v = view ?: return
        view = null
        style = null
        visible = null
        runCatching { wm.removeViewImmediate(v) }.onFailure { Log.w(TAG, "remove failed", it) }
        owner?.destroy()
        owner = null
        AlertStack.remove(this)
    }

    /**
     * Moves the window under those of the alerts in [taken] it would sit on,
     * as close to its own place as they allow; where it is then, in pixels.
     * Null for a window that doesn't line up: the side panel, the full
     * screen, one away from the top, one not laid out yet.
     */
    fun settleUnder(taken: List<AlertSpot>): AlertSpot? {
        val v = view ?: return null
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return null
        if (style == AlertStyle.PANEL || style == AlertStyle.FULL) return null
        if (lp.gravity and Gravity.VERTICAL_GRAVITY_MASK != Gravity.TOP || v.width == 0 || v.height == 0) return null
        val dm = context.resources.displayMetrics
        val left = when (lp.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) {
            Gravity.CENTER_HORIZONTAL -> (dm.widthPixels - v.width) / 2
            Gravity.RIGHT -> dm.widthPixels - lp.x - v.width
            else -> lp.x
        }
        val top = stackedTop(ownY, v.height, left, left + v.width, taken, gap = (8 * dm.density).toInt())
        if (lp.y != top) {
            lp.y = top
            runCatching { wm.updateViewLayout(v, lp) }.onFailure { Log.w(TAG, "could not move the $name window", it) }
        }
        return AlertSpot(left, top, left + v.width, top + v.height)
    }

    /** Whether [show] can put a window up at all: over other apps, or through the accessibility service. */
    fun canShow(): Boolean =
        aboveCamera() && SplitAccessibilityService.overlayHost() != null || android.provider.Settings.canDrawOverlays(context)

    /** The system status bar's height, from the framework's own dimension; 0 where it has none. */
    private fun statusBarHeight(): Int {
        val res = context.resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        return if (id != 0) runCatching { res.getDimensionPixelSize(id) }.getOrDefault(0) else 0
    }

    private fun params(style: AlertStyle, type: Int): WindowManager.LayoutParams {
        val dm = context.resources.displayMetrics
        val margin = (24 * dm.density).toInt()
        val wrap = WindowManager.LayoutParams.WRAP_CONTENT
        val match = WindowManager.LayoutParams.MATCH_PARENT
        val panel = (panelWidthDp((dm.widthPixels / dm.density).toInt()).value * dm.density).toInt()
        // The bar's height, its glass margin under it included (TopBar).
        val aboveBar = dm.heightPixels - ((DashSize.Bar.value + 10f) * dm.density).toInt()
        // The pill goes under the status bar: laid out in screen, it sat on the bar's icons. The
        // side panel and the full screen follow the bar itself instead (show): placed under its
        // height, the panel left a gap at the top while the dashboard hid the bar.
        val underStatus = statusBarHeight()
        val whole = if (clearOfBar) aboveBar else dm.heightPixels
        val (w, h) = when (style) {
            AlertStyle.PILL, AlertStyle.CARD, AlertStyle.BUBBLE, AlertStyle.ICON -> wrap to wrap
            AlertStyle.BANNER -> match to wrap
            AlertStyle.PANEL -> panel to whole
            AlertStyle.FULL -> match to whole
        }
        return WindowManager.LayoutParams(
            w, h,
            type,
            // Touchable (the buttons), but never takes the keyboard or the back key; told of a tap beside it when asked.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (onOutsideTap != null) WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH else 0),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = when (style) {
                AlertStyle.CARD, AlertStyle.BUBBLE, AlertStyle.ICON -> cardGravity
                AlertStyle.PILL, AlertStyle.BANNER -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                AlertStyle.PANEL -> Gravity.TOP or Gravity.END
                AlertStyle.FULL -> Gravity.TOP or Gravity.START
            }
            when (style) {
                AlertStyle.CARD, AlertStyle.BUBBLE, AlertStyle.ICON -> {
                    if (cardGravity and Gravity.HORIZONTAL_GRAVITY_MASK != Gravity.CENTER_HORIZONTAL) x = margin
                    when (cardGravity and Gravity.VERTICAL_GRAVITY_MASK) {
                        Gravity.TOP -> y = margin
                        // Clear of the dashboard's bottom bar.
                        Gravity.BOTTOM -> y = margin * 4
                    }
                }
                AlertStyle.PILL -> y = maxOf(margin / 2, underStatus)
                else -> Unit
            }
            title = "Dashwheel $name"
        }
    }

    private companion object {
        const val TAG = "AlertWindow"
    }
}

/**
 * The alert windows up at once. Those at the top of the screen line up
 * instead of sitting on one another: the one that matters most
 * ([AlertWindow.rank]) keeps its place, the others go under it.
 */
private object AlertStack {
    private val up = mutableListOf<AlertWindow>()

    fun add(window: AlertWindow) {
        if (window !in up) up += window
        settle()
    }

    fun remove(window: AlertWindow) {
        if (up.remove(window)) settle()
    }

    fun settle() {
        val taken = mutableListOf<AlertSpot>()
        up.sortedBy { it.rank }.forEach { w -> w.settleUnder(taken)?.let { taken += it } }
    }
}

/** Where an alert window is on the screen, in pixels. */
internal data class AlertSpot(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * The top of a [height]-tall alert spanning [left]..[right] whose own place
 * is [own]: there, unless one in [taken] is in the way; then under it, [gap] apart.
 */
internal fun stackedTop(own: Int, height: Int, left: Int, right: Int, taken: List<AlertSpot>, gap: Int): Int {
    var top = own
    taken.filter { it.left < right && left < it.right }.sortedBy { it.top }.forEach { r ->
        if (top < r.bottom + gap && top + height + gap > r.top) top = r.bottom + gap
    }
    return top
}

/** The design's way in and out: panels slide from the side, strips from the top, cards pop. */
@Composable
internal fun AlertMotion(style: AlertStyle, state: MutableTransitionState<Boolean>, onGone: () -> Unit, content: @Composable () -> Unit) {
    AnimatedVisibility(visibleState = state, enter = enterOf(style), exit = exitOf(style)) { content() }
    if (state.isIdle && !state.currentState && !state.targetState) LaunchedEffect(Unit) { onGone() }
}

private fun enterOf(style: AlertStyle): EnterTransition = when (style) {
    AlertStyle.CARD -> fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.9f)
    AlertStyle.BUBBLE, AlertStyle.ICON -> fadeIn(tween(200)) + scaleIn(tween(280, easing = FastOutSlowInEasing), initialScale = 0.4f)
    AlertStyle.PILL, AlertStyle.BANNER -> slideInVertically(tween(260, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(200))
    AlertStyle.PANEL -> slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it }
    AlertStyle.FULL -> fadeIn(tween(220)) + scaleIn(tween(260), initialScale = 1.04f)
}

private fun exitOf(style: AlertStyle): ExitTransition = when (style) {
    AlertStyle.CARD -> fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.9f)
    AlertStyle.BUBBLE, AlertStyle.ICON -> fadeOut(tween(180)) + scaleOut(tween(200), targetScale = 0.4f)
    AlertStyle.PILL, AlertStyle.BANNER -> slideOutVertically(tween(220)) { -it } + fadeOut(tween(180))
    AlertStyle.PANEL -> slideOutHorizontally(tween(260)) { it }
    AlertStyle.FULL -> fadeOut(tween(200))
}

/**
 * An alert inside the launcher, for when the overlay window isn't allowed:
 * the same design, in the same place, over the dashboard only.
 */
@Composable
internal fun AlertPopup(style: AlertStyle, cardAlignment: Alignment, content: @Composable () -> Unit) {
    val margin = with(LocalDensity.current) { 24.dp.roundToPx() }
    val (alignment, offset) = when (style) {
        AlertStyle.CARD, AlertStyle.BUBBLE, AlertStyle.ICON -> cardAlignment to when (cardAlignment) {
            Alignment.TopEnd -> IntOffset(-margin, margin)
            Alignment.CenterStart -> IntOffset(margin, 0)
            Alignment.CenterEnd -> IntOffset(-margin, 0)
            Alignment.BottomCenter -> IntOffset(0, -margin * 4)
            else -> IntOffset(0, margin)
        }
        AlertStyle.PILL -> Alignment.TopCenter to IntOffset(0, margin / 2)
        AlertStyle.BANNER -> Alignment.TopCenter to IntOffset.Zero
        AlertStyle.PANEL -> Alignment.TopEnd to IntOffset.Zero
        AlertStyle.FULL -> Alignment.Center to IntOffset.Zero
    }
    val panelWidth = panelWidthDp(LocalConfiguration.current.screenWidthDp)
    Popup(alignment = alignment, offset = offset, properties = PopupProperties(focusable = false)) {
        val size = when (style) {
            AlertStyle.PANEL -> Modifier.fillMaxHeight().width(panelWidth)
            AlertStyle.FULL -> Modifier.fillMaxSize()
            AlertStyle.BANNER -> Modifier.fillMaxWidth()
            else -> Modifier
        }
        Box(size) { content() }
    }
}

/**
 * The full-screen design: everything behind veiled in the look's own page
 * colour, [content] in a solid card in the middle, dressed like the other
 * designs ([AlertSurface], lit in [tone]). A tap outside the card runs
 * [onTap] (and never reaches what's behind).
 */
@Composable
internal fun FullScreenModal(onTap: () -> Unit, tone: Color = DashColors.Accent, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DashColors.Background.copy(alpha = if (DashColors.Light) 0.72f else 0.66f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        AlertSurface(AlertStyle.FULL, Modifier.fillMaxWidth(0.72f).fillMaxHeight(0.78f), tone = tone) {
            Box(contentAlignment = Alignment.Center) { content() }
        }
    }
}

/** [value], or the last one it had while the alert animates out after it went null. */
@Composable
internal fun <T : Any> rememberLast(value: T?): T? {
    val last = remember { mutableStateOf(value) }
    if (value != null && last.value != value) last.value = value
    return value ?: last.value
}
