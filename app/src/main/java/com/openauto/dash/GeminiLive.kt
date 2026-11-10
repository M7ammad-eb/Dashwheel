package com.openauto.dash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.PixelCopy
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.ImageView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.openauto.dash.vehicle.reversingFlow
import com.openauto.dash.vehicle.reversingNow
import com.openauto.dash.vehicle.speedKmhFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Gemini Live, the spoken conversation with Google's AI, as the dashboard's
 * companion: opened from the bar, the ⋮ menu or a steering-wheel button, and
 * shown as one of the alerts, in the design chosen in Settings, Alerts
 * (AlertKind.GEMINI): a pill that only says it listens, or Gemini's own Live
 * screen in a card, the side panel or full screen. Gemini's screen runs on a
 * display of its own (EmbeddedApp) drawn in the alert, so its captions and
 * buttons work there. While driving the bigger designs shrink to the pill
 * after a few seconds; reversing puts the alert aside and a phone call ends
 * the conversation. Dashwheel's own Gemini ends when reverse is engaged too.
 * The car's voice waits during either ([CarVoice.holdForLive]). A second
 * press, or the alert's close button, ends it.
 *
 * Live has no public way in: Gemini's home-screen widget opens it through an
 * activity of Gemini's that only the system or root may start, asking for
 * the "liveconv" feature. Without root, or without the right to show apps
 * inside Dashwheel, Gemini opens full screen as usual.
 */
internal object GeminiLive {
    const val GEMINI_PACKAGE = "com.google.android.apps.bard"

    /** Where Gemini sends its screens: closed to end Live for sure (it can go on in the background). */
    private const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
    private const val LIVE = "am start -n $GEMINI_PACKAGE/.widget.RobinWidgetEntryPointActivity --es feature liveconv"
    private const val TAG = "GeminiLive"

    /** How long Gemini gets to show before Live is asked for. */
    private const val SHOW_WAIT_MS = 15_000L
    /** Gemini's first screen hands off to the Google app's ([EmbeddedApp]): left to settle first. */
    private const val SETTLE_MS = 2_500L
    /** Live's screen may open on the main screen: put back on the alert after this. */
    private const val PLACE_AFTER_MS = 1_500L
    /** The curtain over Gemini's start comes off once Gemini is in place, or after this at most. */
    private const val CURTAIN_MAX_MS = 4_000L
    /** And stays a moment more, while the unit draws Gemini where it now is. */
    private const val CURTAIN_AFTER_MS = 200L
    /** A tap made before the shell probe answered waits this long for it; then Gemini opens as without root. */
    private const val ROOT_WAIT_MS = 10_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var window: AlertWindow? = null
    /** The design on screen now, null when none. */
    private var shown: AlertStyle? = null
    private var starting: Job? = null
    private var ticking: Job? = null
    /** Counts the taps that open Live: the shell probe's answer to an earlier one must not open it over a later. */
    private var tap = 0

    private val _active = MutableStateFlow(false)
    /** A conversation is open: the bar's button turns into its end button. */
    val active: StateFlow<Boolean> = _active

    /** When the alert was opened, or last made big again from the pill (elapsedRealtime). */
    private var bigSince = 0L
    private var moving = false
    private var reversing = false
    /** Dashboard dialogs open now ([dialogShown]): they open under the alert's window. */
    private var dialogs = 0

    /** The alert's pictures of Gemini's screen, as its tiles ([EmbeddedApp.Host.attach]): one per design shown. */
    internal val pictures: MutableSet<Any> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
    /** A picture nobody sees, so Gemini runs while only the pill shows. */
    private val unseen = Any()
    private var unseenTexture: SurfaceTexture? = null
    private var unseenSurface: Surface? = null

    /** The conversation open is Dashwheel's own Gemini ([DashAssistant]), not Google's app. */
    private var own = false

    /** A Gemini to talk to: Dashwheel's own with a Gemini key, or Google's app. */
    fun available(context: Context): Boolean =
        isPackageInstalled(context, GEMINI_PACKAGE) || AiSettings.load(context).apiKey.isNotBlank()

    /**
     * [available] for a skin's bar: read again when the AI settings are saved
     * (a key typed in, or taken out), where `remember { available(context) }`
     * froze the answer for the life of the dashboard.
     */
    @Composable
    fun rememberAvailable(context: Context): Boolean {
        val revision by AiSettings.revision.collectAsState()
        return remember(revision) { available(context) }
    }

    /** Follows the car (speed, reverse gear, calls) and the alert's "Try it". From the dashboard's start. */
    fun start(context: Context) {
        if (started) return
        started = true
        val app = AppLanguage.wrap(context.applicationContext)
        scope.launch {
            var was = false
            speedKmhFlow().collect { kmh ->
                val now = isMoving(kmh, was)
                was = now
                if (now != moving) {
                    moving = now
                    render(app)
                }
            }
        }
        scope.launch {
            reversingFlow().collect {
                reversing = it
                // Dashwheel's own Gemini would talk through the manoeuvre, its alert out of sight: it ends.
                if (it && own) end(app) else render(app)
            }
        }
        scope.launch {
            // It could not connect: what the car says about it is not held back.
            DashAssistant.phase.collect { if (it == DashAssistant.Phase.FAILED) CarVoice.holdForLive(false) }
        }
        scope.launch {
            // The driver talks on the phone: the conversation with Gemini ends.
            PhoneCallOverlay.call.collect { if (talking(it) && _active.value) end(app) }
        }
        scope.launch { AlertStyleStore.styles.collect { render(app) } }
        scope.launch { AlertArbiter.wanted.collect { render(app) } }
        scope.launch { AlertPreview.gemini.collect { render(app) } }
    }

    /** Opens Live, or ends it when it is open: one button does both. Main thread. */
    fun toggle(context: Context) {
        if (_active.value) end(context) else open(context)
    }

    private fun open(context: Context) {
        val app = AppLanguage.wrap(context.applicationContext)
        start(app)
        val config = AiSettings.load(app)
        val googleApp = isPackageInstalled(app, GEMINI_PACKAGE)
        // Dashwheel's own Gemini knows the car: chosen, or the only one there is.
        if (config.apiKey.isNotBlank() && (config.ownLive || !googleApp)) {
            openOwn(app)
            return
        }
        if (!googleApp) {
            HandsFree.say(app, R.string.ai_gemini_live_missing)
            return
        }
        // This runs on a tap, on the main thread: the shell probe's answer, never `su` itself
        // (a "no" would spawn it and wait up to its timeout on every tap, and a Magisk prompt
        // held the launcher until it was answered). Before the probe has answered, the tap
        // waits for it off the main thread and comes back here, unless a newer tap or an open
        // conversation has overtaken it.
        val mine = ++tap
        val root = when (PrivilegedShell.access.value) {
            PrivilegedShell.Access.ROOT -> true
            PrivilegedShell.Access.UNKNOWN -> {
                PrivilegedShell.probe()
                scope.launch {
                    val known = withTimeoutOrNull(ROOT_WAIT_MS) { PrivilegedShell.access.first { it != PrivilegedShell.Access.UNKNOWN } }
                    if (mine != tap || _active.value) return@launch
                    if (known != null) open(app) else fullScreen(app, root = false)
                }
                return
            }
            else -> false
        }
        if (!root || !EmbeddedApp.allowed(app) || !window(app).canShow()) {
            fullScreen(app, root)
            return
        }
        AlertPreview.stop()
        val host = EmbeddedApp.host(app, GEMINI_PACKAGE)
        // No display yet: Gemini starts straight in Live there, one launch instead of two.
        val fresh = host.displayId == null
        if (fresh) host.startCommand = LIVE
        _active.value = true
        CarVoice.holdForLive(true)
        bigSince = SystemClock.elapsedRealtime()
        // The unit shows each new app full screen for a moment before it reaches its display:
        // a picture of the dashboard hides that, until Gemini is in place.
        val since = host.placed.value
        LaunchCurtain.cover(app) {
            if (!_active.value) {
                LaunchCurtain.lift()
                return@cover
            }
            render(app)
            attachUnseen(app, host)
            starting = scope.launch {
                withTimeoutOrNull(CURTAIN_MAX_MS) { host.placed.first { it > since } }
                delay(CURTAIN_AFTER_MS)
                LaunchCurtain.lift()
                if (!fresh) startLive(app, host)
            }
        }
        ticking = scope.launch {
            // The shrink to the pill comes with time, not with an event.
            while (isActive) {
                delay(1_000)
                render(app)
            }
        }
    }

    /** Dashwheel's own Gemini: the alert shows the conversation, no app to start. */
    private fun openOwn(app: Context) {
        if (!DashAssistant.ready(app)) {
            HandsFree.say(app, R.string.ai_ask_say_mic)
            return
        }
        // Not while reversing: the driver listens for the parking sensors.
        if (reversingNow()) return
        AlertPreview.stop()
        own = true
        _active.value = true
        bigSince = SystemClock.elapsedRealtime()
        render(app)
        // The car's voice would talk over Gemini and into its microphone: it waits for the end.
        CarVoice.holdForLive(true)
        DashAssistant.start(app)
        ticking = scope.launch {
            while (isActive) {
                delay(1_000)
                render(app)
            }
        }
    }

    /** Gemini already on its display (a dashboard tile): Live asked for there, then Live's screen kept there. */
    private suspend fun startLive(context: Context, host: EmbeddedApp.Host) {
        val id = withTimeoutOrNull(SHOW_WAIT_MS) {
            while (host.status.value != EmbeddedApp.Status.SHOWN || host.displayId == null) delay(200)
            host.displayId
        } ?: run {
            Log.w(TAG, "Gemini did not show on its display: ${host.status.value}")
            return
        }
        delay(SETTLE_MS)
        shell(context, "$LIVE --display $id")
        delay(PLACE_AFTER_MS)
        host.bringBack()
    }

    /** Closes the alert and the conversation with it. */
    fun end(context: Context) {
        val app = context.applicationContext
        if (!_active.value) return
        if (own) {
            own = false
            ticking?.cancel()
            ticking = null
            _active.value = false
            render(app)
            DashAssistant.stop(app)
            CarVoice.holdForLive(false)
            return
        }
        CarVoice.holdForLive(false)
        starting?.cancel()
        starting = null
        LaunchCurtain.lift()
        ticking?.cancel()
        ticking = null
        _active.value = false
        render(app)
        val host = EmbeddedApp.host(app, GEMINI_PACKAGE)
        host.startCommand = null
        val elsewhere = host.heldBesides(pictures + unseen)
        pictures.toList().forEach { host.forget(it) }
        host.forget(unseen)
        unseenSurface?.release()
        unseenSurface = null
        unseenTexture?.release()
        unseenTexture = null
        scope.launch {
            // Live goes on in the background once its screen is gone: the Google app is closed.
            shell(app, "am force-stop $GOOGLE_APP")
            // A Gemini tile on a dashboard opens it again; with none, its display goes.
            if (elsewhere) host.bringBack() else host.release()
        }
    }

    /** From the pill: the chosen design again, for a while when driving. */
    private fun enlarge(context: Context) {
        bigSince = SystemClock.elapsedRealtime()
        render(context)
    }

    /** Puts up, changes or takes down the alert to match the conversation, the car and the preview. */
    private fun render(context: Context) {
        val chosen = AlertStyleStore.styles.value.of(AlertKind.GEMINI)
        val preview = AlertPreview.gemini.value && !_active.value
        val wanted = when {
            preview -> chosen
            !_active.value || reversing -> null
            else -> design(chosen, moving, SystemClock.elapsedRealtime() - bigSince)
        }
        // Behind every other alert: the pill while one of them needs the room ([AlertArbiter]).
        AlertArbiter.want(AlertKind.GEMINI, wanted)
        val style = wanted?.let { arbitratedStyle(AlertKind.GEMINI, it, AlertArbiter.wanted.value) }
            ?.let { if (dialogs > 0 && it in COVERING) AlertStyle.PILL else it }
        if (style == shown) return
        shown = style
        val w = window(context)
        if (style == null) {
            w.hide()
            return
        }
        val talking = if (preview) AiSettings.load(context).let { it.ownLive && it.apiKey.isNotBlank() } else own
        val host = if (talking) null else EmbeddedApp.host(context, GEMINI_PACKAGE)
        w.show(style) {
            if (preview) GeminiAlert(style, host = null, own = talking, onEnd = { AlertPreview.stop() }, onEnlarge = {})
            else GeminiAlert(style, host, own = talking, onEnd = { end(context) }, onEnlarge = { enlarge(context) })
        }
    }

    /** The designs that would cover a dialog of the dashboard's. */
    private val COVERING = setOf(AlertStyle.CARD, AlertStyle.BANNER, AlertStyle.PANEL, AlertStyle.FULL)

    /**
     * A dialog of the dashboard opens ([open] true) or closes. The alert is an
     * overlay window, always above the dashboard's dialogs: as a panel it hid
     * the dialog's buttons and took their taps, and the dialog, modal, let
     * nothing else through. So the alert folds to its pill while one is open.
     * Main thread.
     */
    fun dialogShown(context: Context, open: Boolean) {
        dialogs = (dialogs + if (open) 1 else -1).coerceAtLeast(0)
        if (_active.value || AlertPreview.gemini.value) render(context)
    }

    private fun window(context: Context): AlertWindow =
        window ?: AlertWindow(context.applicationContext, "gemini live", AlertKind.GEMINI.cardAt.gravity, clearOfBar = true, rank = AlertKind.GEMINI.rank)
            .also { window = it }

    /**
     * Gemini needs a picture to start on its display, and to keep running
     * while only the pill shows: one nobody sees, the panel's size.
     */
    private fun attachUnseen(context: Context, host: EmbeddedApp.Host) {
        val dm = context.resources.displayMetrics
        val width = (panelWidthDp((dm.widthPixels / dm.density).toInt()).value * dm.density).toInt()
        val height = dm.heightPixels - ((DashSize.Bar.value + 10f) * dm.density).toInt()
        val texture = unseenTexture ?: SurfaceTexture(false).also { unseenTexture = it }
        texture.setDefaultBufferSize(width, height)
        val surface = unseenSurface ?: Surface(texture).also { unseenSurface = it }
        host.attach(unseen, surface, width, height, dm.densityDpi)
    }

    /** No alert possible: Live full screen with root, else Gemini as its icon opens it. */
    private fun fullScreen(context: Context, root: Boolean) {
        if (root) {
            scope.launch { shell(context, LIVE) }
            return
        }
        val intent = context.packageManager.getLaunchIntentForPackage(GEMINI_PACKAGE) ?: return
        context.launchSafely(intent)
    }

    private suspend fun shell(context: Context, cmd: String) {
        try {
            DockShell.shell(context, cmd)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$cmd failed", e)
        }
    }

    /** The designs that show Gemini's screen, shrunk to the pill while driving. */
    private val BIG = setOf(AlertStyle.CARD, AlertStyle.PANEL, AlertStyle.FULL)

    /** The design shown for [chosen]: the pill once the car has moved a while with it big ([drivingStyle]). */
    internal fun design(chosen: AlertStyle, moving: Boolean, bigForMs: Long): AlertStyle =
        drivingStyle(chosen, moving, bigForMs, BIG)
}

/**
 * A still picture of the dashboard over the whole screen while an app starts:
 * the unit shows each new app full screen for a moment, whatever display it
 * is started on, before it reaches that display, and the picture hides that
 * flash. Taken from the dashboard's own window (PixelCopy, the apps inside
 * its tiles included); nothing when the dashboard isn't in front. Touches go
 * through it, and it never stays more than a few seconds.
 */
private object LaunchCurtain {
    private var view: ImageView? = null
    private val main = Handler(Looper.getMainLooper())
    /** Counts the covers: a copy or a timer from an earlier one must not act on the next. */
    private var round = 0

    /** Covers the screen, then runs [then]; [then] runs at once when no picture can be taken. Main thread. */
    fun cover(context: Context, then: () -> Unit) {
        lift()
        val mine = ++round
        val activity = EmbeddedApp.dashboardActivity()
        val decor = activity?.window?.decorView
        if (activity == null || decor == null || !activity.hasWindowFocus() || decor.width == 0 || decor.height == 0 ||
            !android.provider.Settings.canDrawOverlays(context)
        ) {
            then()
            return
        }
        val picture = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        var done = false
        val go = {
            if (!done) {
                done = true
                then()
            }
        }
        runCatching {
            PixelCopy.request(activity.window, picture, { result ->
                // A late copy over a newer cover put up a second curtain nothing took down.
                if (result == PixelCopy.SUCCESS && mine == round) show(context, picture)
                go()
            }, main)
        }.onFailure { go() }
        // A copy that never answers must not hold Gemini up.
        main.postDelayed({ go() }, 300)
        main.postDelayed({ if (mine == round) lift() }, 6_000)
    }

    private fun show(context: Context, picture: Bitmap) {
        lift()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = ImageView(context).apply { setImageBitmap(picture) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            // Translucent, so its fade shows the dashboard under it, not black.
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "Dashwheel launch curtain"
        }
        runCatching { wm.addView(v, params) }.onSuccess { view = v }
    }

    /** Takes the picture away, with a short fade. Main thread. */
    fun lift() {
        val v = view ?: return
        view = null
        v.animate().alpha(0f).setDuration(150).withEndAction {
            runCatching { (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeViewImmediate(v) }
        }.start()
    }
}

/**
 * The alert in [style]: the pill alone, or a title row over Gemini's Live
 * screen ([host]'s picture; a stand-in for "Try it", without [host]).
 */
@Composable
private fun GeminiAlert(style: AlertStyle, host: EmbeddedApp.Host?, own: Boolean, onEnd: () -> Unit, onEnlarge: () -> Unit) {
    if (style == AlertStyle.BUBBLE || style == AlertStyle.ICON) {
        GeminiOrb(style, onEnd)
        return
    }
    if (style == AlertStyle.PILL) {
        AlertSurface(AlertStyle.PILL) {
            Row(
                modifier = Modifier
                    .clickable(onClickLabel = stringResource(R.string.ai_gemini_live_show), onClick = onEnlarge)
                    .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Listening()
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.ai_gemini_live), color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                EndButton(onEnd)
            }
        }
        return
    }
    val size = when (style) {
        AlertStyle.CARD -> Modifier.size(width = 440.dp, height = 380.dp)
        else -> Modifier.fillMaxSize()
    }
    AlertSurface(style, size) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Listening()
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.ai_gemini_live), color = DashColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                }
                EndButton(onEnd)
            }
            val screen = Modifier.fillMaxWidth().weight(1f)
            when {
                own -> Conversation(screen)
                host != null -> LiveScreen(host, screen)
                else -> LiveStandIn(screen)
            }
        }
    }
}

/** Gemini's own colours, for the ring and the halo that turn while it listens. */
private val GeminiColours = listOf(Color(0xFF4285F4), Color(0xFF9B72CB), Color(0xFFD96570), Color(0xFF4285F4))

/**
 * The round designs: the bubble (a round surface, Gemini's colours turning
 * around its sparkle) or the icon alone (the sparkle over a turning halo).
 * A tap ends the conversation.
 */
@Composable
private fun GeminiOrb(style: AlertStyle, onEnd: () -> Unit) {
    val turn by rememberInfiniteTransition(label = "orb").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3_000, easing = LinearEasing)), label = "turn"
    )
    val end = stringResource(R.string.ai_gemini_live_end)
    val tap = Modifier.clip(CircleShape).clickable(onClickLabel = end, onClick = onEnd)
    if (style == AlertStyle.ICON) {
        Box(Modifier.size(76.dp).then(tap), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                rotate(turn) { drawCircle(Brush.sweepGradient(GeminiColours), alpha = 0.55f) }
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.5f), Color.Transparent)))
            }
            Listening(Color.White, 34.dp)
        }
        return
    }
    AlertSurface(AlertStyle.BUBBLE, Modifier.size(96.dp).then(tap)) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(5.dp)) {
                rotate(turn) { drawCircle(Brush.sweepGradient(GeminiColours), style = Stroke(width = 5.dp.toPx())) }
            }
            Listening(DashColors.Accent, 36.dp)
        }
    }
}

/** Gemini's sparkle, breathing while the conversation is open. */
@Composable
private fun Listening(tint: Color = DashColors.Accent, size: Dp = 26.dp) {
    // Read while drawing, so each frame only redraws the icon instead of recomposing it.
    val pulse = rememberInfiniteTransition(label = "gemini").animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse"
    )
    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = tint, modifier = Modifier.size(size).graphicsLayer { alpha = pulse.value })
}

@Composable
private fun EndButton(onEnd: () -> Unit) {
    val end = stringResource(R.string.ai_gemini_live_end)
    Box(
        modifier = Modifier.size(DashSize.TouchPrimary).clip(CircleShape).clickable(onClickLabel = end, onClick = onEnd),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.Close, contentDescription = end, tint = DashColors.TextPrimary)
    }
}

/** Dashwheel's Gemini in the card, panel or full screen: the last lines said, and what it is doing. */
@Composable
private fun Conversation(modifier: Modifier) {
    val lines by DashAssistant.lines.collectAsState()
    val phase by DashAssistant.phase.collectAsState()
    Box(modifier) {
    VoiceGlow(Modifier.fillMaxWidth().height(160.dp).align(Alignment.BottomCenter))
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.Bottom) {
        lines.takeLast(4).forEach { line ->
            Text(
                line.text,
                color = if (line.driver) DashColors.TextSecondary else DashColors.TextPrimary,
                style = if (line.driver) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
                textAlign = if (line.driver) TextAlign.End else TextAlign.Start,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        val status = when (phase) {
            DashAssistant.Phase.CONNECTING -> R.string.ai_live_connecting
            DashAssistant.Phase.THINKING -> R.string.ai_live_thinking
            DashAssistant.Phase.SPEAKING -> R.string.ai_live_speaking
            DashAssistant.Phase.FAILED -> R.string.ai_live_failed
            else -> R.string.ai_live_listening
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Listening(DashColors.Accent, 20.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(status), color = DashColors.AccentInk, style = MaterialTheme.typography.labelLarge)
        }
    }
    }
}

/** The glow at the bottom of the conversation, rising and falling with the voice heard or played, as in Gemini's app. */
@Composable
private fun VoiceGlow(modifier: Modifier) {
    val level by DashAssistant.level.collectAsState()
    val glow = animateFloatAsState((level * 6f).coerceAtMost(1f), tween(120), label = "voice")
    Box(
        modifier
            .graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 1f)
                scaleY = 0.2f + 0.8f * glow.value
                alpha = 0.3f + 0.7f * glow.value
            }
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x665AA9FF), Color(0xCC9B72CB))))
    )
}

/** "Try it": Live's dark screen and its glow, no conversation. */
@Composable
private fun LiveStandIn(modifier: Modifier) {
    Box(
        modifier = modifier.background(
            Brush.verticalGradient(0f to Color(0xFF131313), 0.6f to Color(0xFF16233A), 1f to Color(0xFF5AA9FF))
        ),
        contentAlignment = Alignment.TopCenter
    ) {
        Text("Live", color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
    }
}

/** Gemini's own screen, as on the dashboard's tiles (EmbeddedAppCard); touches go to it. */
@Composable
private fun LiveScreen(host: EmbeddedApp.Host, modifier: Modifier) {
    val key = remember { Any() }
    DisposableEffect(host, key) {
        GeminiLive.pictures += key
        onDispose {
            GeminiLive.pictures -= key
            host.forget(key)
        }
    }
    val dpi = (LocalDensity.current.density * 160).toInt()
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                var surface: Surface? = null
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        texture.setDefaultBufferSize(width, height)
                        val s = Surface(texture).also { surface = it }
                        if (width > 0 && height > 0) {
                            host.attach(key, s, width, height, dpi)
                            host.onScreen(key, true)
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                        texture.setDefaultBufferSize(width, height)
                        val s = surface ?: return
                        if (width > 0 && height > 0) host.attach(key, s, width, height, dpi)
                    }

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        host.detach(key)
                        surface?.release()
                        surface = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
                setOnTouchListener { _, event ->
                    host.touch(event)
                    true
                }
            }
        }
    )
}
