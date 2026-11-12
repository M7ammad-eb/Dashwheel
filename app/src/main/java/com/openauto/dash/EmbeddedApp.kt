package com.openauto.dash

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import com.openauto.unitproto.UnitFamily
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Another app, Google Maps, running inside a dashboard tile: it is launched
 * onto a virtual display of the tile's size, whose picture is the tile's
 * [TextureView], and the tile's touches are sent on to it. The same way
 * Android Auto shows Maps on a car's screen.
 *
 * Android lets an app do this only with two permissions it keeps for the
 * firmware's own apps: INTERNAL_SYSTEM_WINDOW to open another app on its
 * display, INJECT_EVENTS to touch it. On the K706 they are granted once
 * PMPatch3 runs in Magisk (it makes every signature check pass), so the tile
 * is only offered where Android has granted the first one ([allowed]).
 *
 * One display per app, kept for as long as a tile shows it: a page swipe
 * only takes the picture away, so a navigation goes on and comes back as it
 * was. The display is closed, and the app with it, once no tile is left
 * ([releaseUnless]).
 *
 * An app runs only once, so several tiles of it (Maps on two pages) share its
 * one display: the picture goes to the tile on screen ([pickTile]).
 *
 * Opening an app on a display puts that display in front, and Android sends
 * the unit's keys (the touch keys beside the screen: Home, Back...) to the
 * display in front: Home then looks for a home screen on the tile's display,
 * finds none and does nothing. So the keys are handed back to the main screen
 * after each launch there, and after each touch ([giveKeysBack]).
 */
internal object EmbeddedApp {

    private const val TAG = "EmbeddedApp"

    /** Google Maps, the one app the tile shows for now. */
    const val MAPS_PACKAGE = "com.google.android.apps.maps"

    // DisplayManager.VIRTUAL_DISPLAY_FLAG_*: the app's content only, never a
    // mirror, and the app closed with the display instead of thrown onto the
    // main screen (DESTROY_CONTENT_ON_REMOVAL is @hide).
    private const val FLAG_PUBLIC = 1 shl 0
    private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
    private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8

    // InputManager.INJECT_INPUT_EVENT_MODE_ASYNC (@hide).
    private const val INJECT_ASYNC = 0

    // WindowConfiguration.WINDOWING_MODE_FULLSCREEN (@hide).
    private const val WINDOWING_MODE_FULLSCREEN = 1

    /** How often we look where the app is while it starts or moves. */
    private const val POLL_MS = 150L

    /** How long a start may take to show before we stop looking for it. */
    private const val START_WAIT_MS = 5_000L

    /** Moves tried before giving up (a new task of the app may appear meanwhile). */
    private const val MOVE_ROUNDS = 5

    /**
     * How long a freshly started app's first screen gets to hand off to
     * another app's ([WindowListing.handedTo]) before it counts as on the tile.
     */
    private const val HAND_OFF_MS = 1_000L

    /** How long a closed window gets to go before the app is opened afresh. */
    private const val CLOSE_WAIT_MS = 500L

    /** The shortest time between two closings of an app found running twice. */
    private const val RESTART_COOLDOWN_MS = 5 * 60_000L

    enum class Status {
        /** The display is being made or the app launched. */
        STARTING,
        /** The app was launched on the display. */
        SHOWN,
        /** The app is not installed. */
        MISSING,
        /** Android refused the display or the launch. */
        BLOCKED
    }

    /** Whether Android lets Dashwheel open another app on its own display; never in the Play edition. */
    fun allowed(context: Context): Boolean = granted(context, "android.permission.INTERNAL_SYSTEM_WINDOW")

    /** Whether the tile's touches can be sent on to the app. */
    fun canTouch(context: Context): Boolean = granted(context, "android.permission.INJECT_EVENTS")

    // The Play edition does not ask for these permissions and never touches the hidden calls behind them.
    private fun granted(context: Context, permission: String) =
        Edition.full && ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private val hosts = java.util.concurrent.ConcurrentHashMap<String, Host>()

    /**
     * This unit opens a screen asked for on a tile's display on the main
     * screen first, and the placing moves it onto the tile: it flashes over
     * the dashboard every time (the K706's firmware). Learned once, from the
     * first move after a start, and kept: the openings that would only flash
     * are then not made ([TilePlayer]), while the tile's own launch keeps
     * flashing once, which it must.
     */
    @Volatile var opensOnMainScreen: Boolean = false
        private set
    @Volatile private var quirksLoaded = false
    private const val QUIRKS_PREFS = "embedded_app"
    private const val KEY_OPENS_ON_MAIN = "opens_on_main_screen"
    /** A move off the main screen this soon after a start is the start's own screen landing there. */
    private const val LEARN_MS = 6_000L

    private fun loadQuirks(context: Context) {
        if (quirksLoaded) return
        quirksLoaded = true
        opensOnMainScreen = context.getSharedPreferences(QUIRKS_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_OPENS_ON_MAIN, false)
    }

    private fun learnOpensOnMainScreen(context: Context) {
        if (opensOnMainScreen) return
        opensOnMainScreen = true
        Log.i(TAG, "this unit opens a tile's screens on the main screen first: the players stay put")
        context.getSharedPreferences(QUIRKS_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_OPENS_ON_MAIN, true).apply()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun host(context: Context, packageName: String): Host =
        hosts.getOrPut(packageName) {
            Host(context.applicationContext, packageName).also { host ->
                copies[packageName]?.forEach { host.addCopy(it) }
                _hosted.value = _hosted.value + packageName
            }
        }

    private val _hosted = MutableStateFlow<Set<String>>(emptySet())
    /** The apps inside a dashboard tile right now: those the second screen can show a copy of. */
    val hosted: StateFlow<Set<String>> = _hosted.asStateFlow()

    private val _shapes = MutableStateFlow<Map<String, Float>>(emptyMap())
    /**
     * Width over height of each app's picture in its tile: the second screen gives
     * a copy a slot of that shape, not one it is shrunk into with black bars.
     */
    val shapes: StateFlow<Map<String, Float>> = _shapes.asStateFlow()

    private fun noteShape(packageName: String, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val shape = width.toFloat() / height
        if (_shapes.value[packageName] != shape) _shapes.value = _shapes.value + (packageName to shape)
    }

    /** Copies of each app's picture asked for (the second screen's cluster), kept while its tile comes and goes. Main thread. */
    private val copies = HashMap<String, MutableList<Surface>>()

    /**
     * [surface] shows a copy of [packageName]'s picture from now on, whenever
     * it runs inside a tile, through a [PictureRelay]. Main thread.
     */
    fun showCopy(packageName: String, surface: Surface) {
        val list = copies.getOrPut(packageName) { ArrayList() }
        if (list.any { it === surface }) return
        list += surface
        hosts[packageName]?.addCopy(surface)
    }

    /**
     * Whether the second screen's cluster shows a copy of [packageName]: its
     * frames then go through a relay from the start (an app that isn't moving
     * sends no frame when its picture changes hands later, and the copy would
     * stay black).
     */
    internal fun copyWanted(packageName: String): Boolean = SecondScreenStore.config.value.let { c ->
        c.mode == SecondScreenMode.CLUSTER &&
            c.layouts.values.any { l -> l.slots.any { it.kind == BuiltinKind.MAPS_INSIDE && it.app == packageName } }
    }

    /** [surface] no longer shows the copy; it is let go of before this returns. Main thread. */
    fun hideCopy(packageName: String, surface: Surface) {
        copies[packageName]?.let { list ->
            list.removeAll { it === surface }
            if (list.isEmpty()) copies.remove(packageName)
        }
        hosts[packageName]?.removeCopy(surface)
    }

    /** Apps with a display of their own right now; read from the window tiles' threads. */
    @Volatile
    private var held: Set<String> = emptySet()

    /** The apps the other tiles show ([Host.windowPackage]): never taken for [host]'s hand-off. */
    private fun claimedBesides(host: Host): Set<String> =
        hosts.values.filter { it !== host }.flatMap { listOf(it.packageName, it.windowPackage) }.toSet()

    /** True while [packageName] runs inside a tile, on its own display. */
    fun holds(packageName: String): Boolean = packageName in held

    /** The system's display helper died ([SystemDisplays]): every tile on one of its displays starts over on a display of Dashwheel's own. */
    fun systemDisplaysLost() {
        hosts.values.toList().forEach { it.displayLost() }
    }

    /** The view each hosted app's tile draws into, while it is up: for [tileBitmap]. */
    private val tileViews = java.util.concurrent.ConcurrentHashMap<String, java.lang.ref.WeakReference<TextureView>>()

    /** How many frames each app has drawn on its tile: a reader ([TileText]) skips a tile that drew none since its last look. */
    internal val tileFrames = java.util.concurrent.ConcurrentHashMap<String, Long>()

    internal fun tileShown(packageName: String, view: TextureView) {
        tileViews[packageName] = java.lang.ref.WeakReference(view)
    }

    internal fun tileGone(packageName: String, view: TextureView) {
        if (tileViews[packageName]?.get() === view) tileViews.remove(packageName)
    }

    /**
     * What [packageName]'s tile shows now, no wider than [maxWidth] (the same
     * shape), for reading its words ([TileText]); null without a picture.
     * Main thread. Drawn into [reuse] when it has the right size (no new
     * 1.6 MB picture every read), else into a new one; the caller recycles
     * whichever it no longer needs.
     */
    fun tileBitmap(packageName: String, maxWidth: Int, reuse: android.graphics.Bitmap? = null): android.graphics.Bitmap? {
        val view = tileViews[packageName]?.get() ?: return null
        if (!view.isAvailable || view.width <= 0 || view.height <= 0) return null
        val scale = minOf(1f, maxWidth.toFloat() / view.width)
        val w = (view.width * scale).toInt().coerceAtLeast(1)
        val h = (view.height * scale).toInt().coerceAtLeast(1)
        if (reuse != null && !reuse.isRecycled && reuse.isMutable && reuse.width == w && reuse.height == h &&
            reuse.config == android.graphics.Bitmap.Config.ARGB_8888
        ) {
            // Cleared first, as a new one starts: a copy that fails leaves it blank, not the last frame.
            return runCatching { reuse.eraseColor(0); view.getBitmap(reuse) }.getOrNull()
        }
        return runCatching { view.getBitmap(w, h) }.getOrNull()
    }

    /** Each app inside a tile, and the first dashboard page with such a tile (set by the dashboard). */
    @Volatile
    var tilePages: Map<String, Int> = emptyMap()

    /** The page of [packageName]'s tile while it runs inside one; null otherwise. */
    fun pageOf(packageName: String): Int? = tilePages[packageName]?.takeIf { holds(packageName) }

    /** Closes [packageName] and opens it afresh on its tile when it runs in one ([Host.restart]); false otherwise. */
    fun restartInTile(packageName: String): Boolean =
        holds(packageName) && hosts[packageName]?.restart() == true

    /** Opens [intent] in [packageName]'s tile when it runs in one ([Host.open]); false otherwise. */
    fun openInside(packageName: String, intent: Intent): Boolean =
        holds(packageName) && hosts[packageName]?.open(intent) == true

    /** Opens the app's own [screen] (a media session's player) in [packageName]'s tile when it runs in one; false otherwise. */
    fun openInside(packageName: String, screen: PendingIntent): Boolean =
        holds(packageName) && hosts[packageName]?.open(screen) == true

    /** Whether [packageName]'s tile was touched within [ms]: the driver is using it. */
    fun touchedWithin(packageName: String, ms: Long): Boolean =
        tileInUse?.packageName == packageName && SystemClock.elapsedRealtime() - tileUsedAt <= ms

    // --- Touches ------------------------------------------------------------

    /** Where the tiles' touches are sent to their apps from, one after another. */
    private val touchHandler: Handler by lazy {
        Handler(HandlerThread("embedded-touch", Process.THREAD_PRIORITY_DISPLAY).apply { start() }.looper)
    }

    /** What the taps on the tiles cost, summed up in the log once a minute at most ([TapStats]), for the unit report. */
    private val tapStats = TapStats()

    /** Logs a raise of the keys made outside a tap ([what] took [ms]) and counts it towards the minute's summary. */
    private fun raised(what: String, ms: Long) {
        if (ms > TapCost.SLOW_RAISE_MS) Log.w(TAG, "$what in $ms ms: slow") else Log.i(TAG, "$what in $ms ms")
        tapStats.raised(ms, what, SystemClock.uptimeMillis())?.let { Log.i(TAG, it) }
    }

    // Both @hide, looked up once: looking them up on every touch took long
    // enough to make taps late and drags stutter.
    private val setDisplayId: Method? by lazy {
        runCatching { HiddenApi.getDeclaredMethod(InputEvent::class.java, "setDisplayId", Int::class.javaPrimitiveType) }
            .onFailure { Log.w(TAG, "no InputEvent.setDisplayId", it) }.getOrNull()
    }

    private val injectInputEvent: Method? by lazy {
        runCatching { HiddenApi.getDeclaredMethod(InputManager::class.java, "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType) }
            .onFailure { Log.w(TAG, "no InputManager.injectInputEvent", it) }.getOrNull()
    }

    // Android 9 has no InputEvent.setDisplayId: events reach another display through
    // the InputForwarder its ActivityView used, one per display (@hide, 9 only).
    private val createInputForwarder: Method? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return@lazy null
        runCatching { HiddenApi.getDeclaredMethod(InputManager::class.java, "createInputForwarder", Int::class.javaPrimitiveType) }
            .onFailure { Log.w(TAG, "no InputManager.createInputForwarder", it) }.getOrNull()
    }

    private val inputForwarder: Class<*>? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return@lazy null
        runCatching { Class.forName("android.app.InputForwarder") }.onFailure { Log.w(TAG, "no InputForwarder", it) }.getOrNull()
    }

    private val forwardEvent: Method? by lazy {
        val cls = inputForwarder ?: return@lazy null
        runCatching { HiddenApi.getDeclaredMethod(cls, "forwardEvent", InputEvent::class.java) }
            .onFailure { Log.w(TAG, "no InputForwarder.forwardEvent", it) }.getOrNull()
    }

    private val disposeForwarder: Method? by lazy {
        val cls = inputForwarder ?: return@lazy null
        runCatching { HiddenApi.getDeclaredMethod(cls, "dispose") }.getOrNull()
    }

    /** Whether this Android lets touches and keys be sent to a tile's display at all. */
    private val canInject: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setDisplayId != null && injectInputEvent != null
        else createInputForwarder != null && forwardEvent != null

    // --- The unit's keys ---------------------------------------------------

    /** How often a tap in a tile checks whether the keys can come back (not while typing there). */
    private const val GIVE_BACK_MS = 1_500L

    /** How long a tap in a tile keeps checking before letting the keys be. */
    private const val GIVE_BACK_TRIES = 80

    /**
     * How long after a tap in a tile the keyboard is given to come up before
     * the keys go back: at [GIVE_BACK_MS] a slow unit's keyboard was not up
     * yet, the field lost the focus with the keys, and the tap on an address
     * field seemed lost (it worked only with the app full screen).
     */
    private const val KEYBOARD_GRACE_MS = 4_000L

    /** A key's second chance, once the main screen is back in front. */
    private const val KEY_AGAIN_MS = 150L

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** The dashboard while it is on screen: its task is what brings the main screen back in front. */
    @Volatile
    private var dashboard: WeakReference<Activity>? = null

    /** A tile's display may be in front, with the keys: set on a launch or a tap there. */
    private val _keysAway = MutableStateFlow(false)

    /** An app inside a tile took the unit's keys (a tap in it): Home and Back must be caught before it gets them. */
    val keysAwayFlow: StateFlow<Boolean> = _keysAway.asStateFlow()

    private var keysAway: Boolean
        get() = _keysAway.value
        set(value) { _keysAway.value = value }

    private var tileTouched = false
    private var givingBack: Job? = null

    private var raising: Job? = null

    /** Home or Back held back by [keyWhileAway], until let go. */
    private var heldKey = 0

    /** The dashboard's activity while it shows, for a picture of it ([GeminiLive]'s curtain). */
    fun dashboardActivity(): Activity? = dashboard?.get()

    /** Whether the dashboard's window has the keys ([MainActivity.onWindowFocusChanged]). */
    @Volatile var dashboardFocused = false
        set(value) {
            if (value && !field) dashboardFocusGains++
            field = value
        }

    /**
     * How many times the dashboard's window took the keys. Main thread only
     * writes it. Changed during a tap in a tile, the app's window lost the
     * keys between its press and its lift ([Host.touch]).
     */
    @Volatile var dashboardFocusGains = 0
        private set

    /** The dashboard's task while it shows. */
    @Volatile private var dashboardTask: Int? = null

    /** How long after giving the keys back they are checked, and taken once more if still away. */
    private const val KEYS_BACK_CHECK_MS = 400L

    /**
     * Raises [task] so that the keys surely follow. Android 10 keeps two ideas of
     * what is in front: the activities' (moveTaskToFront) and the windows', which a
     * touch on a display moves on its own. After a touch in a tile the windows had
     * the tile's display in front while the activities still had the dashboard, and
     * raising the dashboard was then nothing to do for Android: the keys stayed on
     * the tile, Home went there and did nothing (the K706, 2026-10-14). [first]
     * (another display's task) is raised before, so raising [task] always moves.
     */
    private fun raiseFirmly(context: Context, task: Int, first: Int?, flags: Int) {
        val am = context.getSystemService(ActivityManager::class.java)
        if (first != null && first != task) runCatching { am.moveTaskToFront(first, flags) }
        am.moveTaskToFront(task, flags)
    }

    /** A tile's app task, on its own display: what [raiseFirmly] raises first when the keys come back. */
    private fun anyTileTask(): Int? =
        // Not Gemini Live's: raising it moved the focus to its display while a
        // dashboard dialog needed it, and the dialog stopped answering.
        hosts.entries.firstNotNullOfOrNull { (pkg, host) -> host.keysTask.takeIf { pkg != GeminiLive.GEMINI_PACKAGE } }

    /** [activity] shows (onStart), or null once it no longer does (onStop). */
    fun dashboardShown(activity: Activity?) {
        dashboard = activity?.let { WeakReference(it) }
        dashboardTask = activity?.taskId
        if (activity == null) {
            // Something else came in front of the dashboard, on the main screen: it has the keys.
            keysAway = false
            // Not the user's doing: maybe the unit opening a tile's app full screen by itself.
            if (!userJustOpened()) watchFront(COVERED_WATCH_MS)
            return
        }
        watching?.cancel()
        dashboardBack()
    }

    /**
     * The dashboard lost the front (onPause): a tile's app torn off its tile
     * pauses it at once, and stops it only a couple of seconds later, so the
     * watch starts here already.
     */
    fun dashboardPaused() {
        if (dashboard?.get() != null && !userJustOpened()) watchFront(COVERED_WATCH_MS, atOnce = true)
    }

    /**
     * A window of a tile's app came up on the main screen (the accessibility
     * service saw it, which sees that screen only): looked at this instant,
     * so the app is back on its tile as early as can be. Main thread.
     */
    fun tileAppInFront(packageName: CharSequence?) {
        if (packageName == null || packageName.toString() !in held) return
        if (dashboard?.get() == null || userJustOpened()) return
        watchFront(COVERED_WATCH_MS, atOnce = true)
    }

    /**
     * The dashboard is in front again: started, resumed (Home pressed while a
     * window over it never stopped it) or given the focus back. An app opened
     * full screen meanwhile (YouTube Music from the app list...) was taken off
     * its tile's display, leaving the tile black. Each tile's app is put back;
     * one still there is left alone.
     */
    fun dashboardBack() {
        if (dashboard?.get() == null) return
        hosts.values.forEach { it.bringBack() }
    }

    // --- Apps opened full screen by the unit itself ------------------------------
    //
    // The QF firmware reopens the navigation app full screen at power-up when
    // it was in front at switch-off (Waze inside its tile): it keeps that app
    // in Settings.System [NAVI_TO_RESTORE] at ACC off and starts it a few
    // seconds after ACC on. Coming on top of the one inside the tile, it made
    // two copies of the app: one covering the dashboard, where menus did
    // nothing, the map no longer turned with the car and Home could not get
    // rid of it, and one inside the tile. The driver wants to wake on the
    // dashboard anyway, so that record is always wiped (a tile brings its app
    // back itself) and the dashboard comes in front at switch-off; and, should
    // the unit open it anyway, for a while after the ignition comes on, and after
    // the dashboard gets covered without a touch or a key, a tile's app found
    // full screen in front is put back into its tile. What the user opens full
    // screen themselves is left there.
    //
    // Waze does it too: it opens its own screens (voice button, search, reports,
    // trip overview, a permission request) "beside" itself, and on Android 10
    // that takes its whole task off the tile's display, full screen onto the
    // main one. A tap inside a tile is never the driver asking for its app full
    // screen, so that tap does not count as the user's doing: every such screen
    // goes back into the tile, unless it keeps jumping out again.

    /** Where the firmware keeps the navigation app to reopen at power-up ("package/class"). */
    private const val NAVI_TO_RESTORE = "navi_activity_before_sleep"

    /** The value by which it reopens none. */
    private const val NO_NAVI = "not_restore_navi"

    /** How long the firmware is given to save its record at switch-off before it is looked at. */
    private const val NAVI_SAVED_MS = 2_000L

    /** How long after a touch on the dashboard, or a key, what opens full screen is the user's doing. */
    private const val USER_OPENS_MS = 3_000L

    /** How long the unit's own opening of an app is watched for, after the ignition comes on. */
    private const val POWER_UP_WATCH_MS = 60_000L

    /** How long after the ignition the dashboard is put back in front of an app the unit reopened. */
    private const val HOME_AFTER_POWER_UP_MS = 20_000L

    /** How long it is watched for once the dashboard got covered without the user. */
    private const val COVERED_WATCH_MS = 10_000L

    /** How often, meanwhile. */
    private const val WATCH_EVERY_MS = 500L

    /**
     * A screen of its own moved back onto the tile this many times within
     * [JUMPS_WINDOW_MS] is left full screen until it closes: it opens "beside"
     * itself again each time it starts over there.
     */
    private const val MAX_JUMPS = 3
    private const val JUMPS_WINDOW_MS = 20_000L

    /**
     * How long an app left full screen with such a screen is kept there after
     * it closed, for the next one it opens from it (Waze: the voice screen,
     * then the search results) to come up first.
     */
    private const val OWN_SCREEN_SETTLE_MS = 2_500L

    /**
     * The same after a voice screen: Waze then searches for the place said and
     * lists what it found, which took over 3 s on the K706 (moved back onto its
     * tile before that, the place said was lost). Long enough to pick a result
     * full screen; picking one opens its place card, which is waited for in turn.
     */
    private const val VOICE_SETTLE_MS = 15_000L

    /** How often the dashboard is looked for in front after the ignition comes on. */
    private const val HOME_EVERY_MS = 500L

    /** When the user last touched the dashboard or pressed a key of the unit (elapsedRealtime). */
    @Volatile
    private var userActedAt = 0L

    /** The user's last act was a touch on an app inside a tile, not on the dashboard. */
    @Volatile
    private var actedInsideTile = false

    private var watching: Job? = null

    /** The user touched the dashboard or pressed a key: what opens next is theirs. */
    fun userActed() {
        userActedAt = SystemClock.elapsedRealtime()
        actedInsideTile = false
    }

    /**
     * Whether what covers the dashboard now may be the user's own doing: a
     * touch on the dashboard or a key just before. A touch inside a tile does
     * not count: it goes to the tile's app, never asks for it full screen.
     */
    private fun userJustOpened(): Boolean =
        !actedInsideTile && SystemClock.elapsedRealtime() - userActedAt <= USER_OPENS_MS

    /**
     * The ignition went off: the dashboard comes in front at once, so the unit
     * sleeps, and wakes, on it (the app that was in front goes on behind it:
     * music, guidance), and the unit is not to reopen any app full screen at
     * the next start.
     */
    fun carStopped(context: Context) {
        mainScope.launch { bringHome(context, "switch-off") }
        scope.launch {
            delay(NAVI_SAVED_MS)
            forgetNaviToRestore(context)
        }
    }

    /**
     * The ignition came on: the unit reads its record a few seconds from now,
     * so it is wiped again first, then watched in case it opens the app anyway.
     */
    fun carStarted(context: Context) {
        scope.launch { forgetNaviToRestore(context) }
        mainScope.launch { watchFront(POWER_UP_WATCH_MS) }
        mainScope.launch { homeAfterPowerUp(context) }
        scope.launch {
            delay(WAKE_SETTLE_MS)
            hosts.values.toList().forEach { it.checkAfterWake() }
        }
    }

    /**
     * The unit started ([BootReceiver]): its firmware may put its own launcher
     * in front, so the dashboard is brought back as at ignition on, for the
     * first seconds and until the driver acts.
     */
    fun bootedUp(context: Context) {
        mainScope.launch { homeAfterPowerUp(context) }
    }

    /** How long a tile's app is given to draw again after the ignition comes on, before it is looked at. */
    private const val WAKE_SETTLE_MS = 6_000L

    /** How long a picture has to stay exactly the same to count as not drawn. */
    private const val STILL_PICTURE_MS = 3_000L

    /** The share of one colour that makes a picture blank ([mostlyFlat]). */
    private const val FLAT_SHARE = 0.8f

    /**
     * The unit wakes on the app that was in front at switch-off (any app, not
     * only the navigation one) when the dashboard could not come in front at
     * switch-off: from the ignition on, at once and then every
     * [HOME_EVERY_MS] for [HOME_AFTER_POWER_UP_MS], as long as the user has
     * not touched or pressed anything, the dashboard is put back in front
     * whenever it is not. Main thread.
     */
    private suspend fun homeAfterPowerUp(context: Context) {
        val since = userActedAt
        val until = SystemClock.elapsedRealtime() + HOME_AFTER_POWER_UP_MS
        while (SystemClock.elapsedRealtime() < until) {
            if (userActedAt != since) return
            bringHome(context, "power-up")
            delay(HOME_EVERY_MS)
        }
    }

    /** The dashboard in front, when it is not; [why] for the log. Main thread. */
    private fun bringHome(context: Context, why: String) {
        if (dashboard?.get() != null) return
        // Home handed back to the unit's launcher: Dashwheel stays where it is.
        if (HomeHandBack.released(context)) return
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        // From the background, Android only lets an app with "display over other apps"
        // start its screen: without it the start is dropped without a word, so the
        // unit's shell (root or ADB) starts it instead, where there is one.
        if (!android.provider.Settings.canDrawOverlays(context) && PrivilegedShell.access.value.shell) {
            val app = context.applicationContext
            scope.launch {
                runCatching { DockShell.shell(app, "am start -c android.intent.category.HOME -n ${FloatingHome.component(app).flattenToShortString()}") }
                    .onFailure { Log.w(TAG, "$why: can't bring the dashboard back through the shell", it) }
            }
            return
        }
        runCatching { context.startActivity(home) }
            .onSuccess {
                Log.i(TAG, "$why: an app was in front of the dashboard, Home brought back")
                DebugLog.note(context, "$why: an app was in front, Home brought back")
            }
            .onFailure { Log.w(TAG, "$why: can't bring the dashboard back to the front", it) }
    }

    /**
     * Wipes the firmware's app to reopen at power-up: the unit wakes on the
     * dashboard, and an app inside a tile is brought back by its tile.
     */
    private suspend fun forgetNaviToRestore(context: Context) {
        try {
            val saved = DockShell.shell(context, "settings get system $NAVI_TO_RESTORE").trim()
            if (saved.isEmpty() || saved == NO_NAVI || saved == "null") return
            DockShell.shell(context, "settings put system $NAVI_TO_RESTORE $NO_NAVI")
            Log.i(TAG, "the unit won't reopen $saved full screen at power-up: it wakes on the dashboard")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "can't stop the unit reopening the navigation app at power-up", e)
        }
    }

    /**
     * For [forMs], puts back into its tile every tile's app found full screen
     * in front of the main screen; stops once the user touches or presses a key.
     * [atOnce]: the first look is now, not [WATCH_EVERY_MS] from now (the app
     * is known to be in front already). Main thread.
     */
    private fun watchFront(forMs: Long, atOnce: Boolean = false) {
        if (hosts.isEmpty()) return
        watching?.cancel()
        val since = userActedAt
        var until = SystemClock.elapsedRealtime() + forMs
        var first = atOnce
        watching = mainScope.launch {
            while (SystemClock.elapsedRealtime() < until) {
                if (first) first = false else delay(WATCH_EVERY_MS)
                if (userActedAt != since) return@launch
                // Nothing can be full screen over a dashboard that has the focus: no listing then.
                if (dashboardFocused && dashboard?.get() != null) continue
                // One fresh listing per look, shared by every tile's app.
                DockShell.forgetListing()
                // An app full screen with a screen of its own open (Waze listening for
                // a place) is waited for, however long that takes, then put back.
                val waiting = hosts.values.toList().map { it.bringBackFromFront() }.any { it }
                if (waiting) until = SystemClock.elapsedRealtime() + forMs
            }
        }
    }

    /**
     * Puts the main screen back in front, and the keys with it, by raising the
     * task already in front there (the dashboard's, or a window floating over
     * it), so nothing moves on screen. Only while the dashboard shows. Main
     * thread; null when there was nothing to do.
     */
    fun giveKeysBack(force: Boolean = false): Job? {
        if (!keysAway && !force) return null
        val activity = dashboard?.get() ?: return null
        keysAway = false
        givingBack?.cancel()
        // A newer look wins: the older one may have seen the app still on the screen, before its move.
        raising?.cancel()
        return mainScope.launch {
            MainScreenCover.raising(true)
            try {
                val front = runCatching {
                    DockShell.forgetListing()
                    WindowListing.frontTask(DockShell.listStacks(activity))
                }.getOrNull()
                ensureActive()
                val taskId = front ?: activity.taskId
                val tile = anyTileTask()
                var began = SystemClock.uptimeMillis()
                // Off the main thread: on a slow unit a raise took a few hundred ms, and the
                // dashboard's touches, a tile's taps among them, waited for it.
                withContext(Dispatchers.IO) { runCatching { raiseFirmly(activity, taskId, tile, ActivityManager.MOVE_TASK_NO_USER_ACTION) } }
                    .onSuccess { raised("keys back to the main screen (task $taskId raised)", SystemClock.uptimeMillis() - began) }
                    .onFailure { Log.w(TAG, "can't give the keys back to the main screen", it) }
                DockShell.forgetListing()
                // Checked: still away (another raise came between), taken once more.
                delay(KEYS_BACK_CHECK_MS)
                if (!dashboardFocused && dashboard?.get() === activity && !keysAway) {
                    began = SystemClock.uptimeMillis()
                    withContext(Dispatchers.IO) { runCatching { raiseFirmly(activity, taskId, tile, ActivityManager.MOVE_TASK_NO_USER_ACTION) } }
                        .onSuccess { raised("keys still away: main screen raised again", SystemClock.uptimeMillis() - began) }
                }
            } finally {
                MainScreenCover.raising(false)
            }
        }.also { raising = it }
    }

    /** A touch on the dashboard begins ([MainActivity.dispatchTouchEvent]). */
    fun touchStarts() {
        userActed()
        tileTouched = false
        downOn = null
        givingBack?.cancel()
    }

    // --- A touch on a tile, straight from the window -------------------------
    //
    // The dashboard decides where a finger lands (so a menu drawn over a tile
    // keeps its touches), but once it lands on an app's tile the rest of that
    // touch goes to the app straight from the window: through the dashboard's
    // pages it reached the app as a press and a cancel, a whole drag as 4 touches.

    /** The tile's app the finger just landed on, and where on the tile. Main thread. */
    private var downOn: Host? = null
    private var downX = 0f
    private var downY = 0f

    /** The app the current touch goes to, and the tile's offset in the window. */
    private var routeTo: Host? = null
    private var routeDx = 0f
    private var routeDy = 0f

    /** True while a touch goes straight to a tile's app: the tile's own view leaves it alone. */
    val routing: Boolean get() = routeTo != null

    /** The finger landed on [host]'s tile at ([x], [y]) on it. */
    fun tileDown(host: Host, x: Float, y: Float) {
        downOn = host
        downX = x
        downY = y
    }

    /**
     * After the dashboard saw a finger go down at [down] (window coordinates):
     * true when it landed on an app's tile, the rest of the touch then going
     * there through [routeTouch].
     */
    fun takeTouch(down: MotionEvent): Boolean {
        val host = downOn ?: return false
        downOn = null
        routeTo = host
        tileInUse = host
        tileUsedAt = SystemClock.elapsedRealtime()
        routeDx = down.x - downX
        routeDy = down.y - downY
        routedDown = RoutedDown(down.downTime, down.eventTime, down.rawX, down.rawY)
        return true
    }

    /** The press [takeTouch] took: when it began and where on the screen. */
    internal class RoutedDown(val downTime: Long, val eventTime: Long, val x: Float, val y: Float)

    private var routedDown: RoutedDown? = null

    /** Before Android 11, how long a tap told again waits for the keys to reach the app ([Host.retap]). */
    private const val FOCUS_SETTLE_MS = 40L

    /**
     * Whether a press that ended ([dx], [dy]) from where it began, [ms] after,
     * having [wandered] (past the slop, or a second finger), was a tap: one that
     * may be told again whole. A drag, a pinch or a long press is left as it went.
     */
    internal fun isTap(dx: Float, dy: Float, ms: Long, wandered: Boolean, slop: Int, longPressMs: Int): Boolean =
        !wandered && ms < longPressMs && kotlin.math.hypot(dx, dy) <= slop

    /** A second press this soon after the routed one, this close to it, is that same press told again. */
    private const val SAME_PRESS_MS = 150L
    private const val SAME_PRESS_PX = 24f

    /**
     * Whether a press at ([x], [y]) is [routed] told again rather than a new
     * touch. On the K706 a finger's press reached the dashboard twice, a
     * millisecond apart with no lift between (adb's taps came once): taken for
     * a new touch, the first was cancelled in the app and the press started
     * again, and Waze ignored a tap begun that way. Its "Where to?" bar did
     * nothing under the finger, while every adb tap opened it.
     */
    internal fun samePress(routed: RoutedDown, downTime: Long, eventTime: Long, x: Float, y: Float): Boolean =
        downTime == routed.downTime ||
            (eventTime - routed.eventTime in 0..SAME_PRESS_MS && kotlin.math.hypot(x - routed.x, y - routed.y) <= SAME_PRESS_PX)

    /** The rest of a touch taken by [takeTouch], moved onto the tile; false when none is. */
    fun routeTouch(ev: MotionEvent): Boolean {
        val host = routeTo ?: return false
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // The press the app already has, told twice: kept from the dashboard, which would
            // cancel it in the app and start it over.
            val routed = routedDown
            if (routed != null && samePress(routed, ev.downTime, ev.eventTime, ev.rawX, ev.rawY)) return true
            // A new touch without the end of the last one: that one is over.
            routeTo = null
            return false
        }
        val copy = MotionEvent.obtain(ev)
        copy.offsetLocation(-routeDx, -routeDy)
        host.touch(copy)
        copy.recycle()
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            routeTo = null
            touchEnds()
        }
        return true
    }

    /** That touch landed on an app inside a tile. */
    fun tileTouched() {
        tileTouched = true
        actedInsideTile = true
        // A giving back of the keys still under way would raise the dashboard
        // in the middle of this tap, and the app would lose it.
        raising?.cancel()
    }

    /** The tile's app the user touched last, and when: the wheel's Back goes to it ([backInTile]). */
    @Volatile private var tileInUse: Host? = null
    @Volatile private var tileUsedAt = 0L

    /** How long after its last touch a tile's app still gets the Back key. */
    private const val TILE_BACK_MS = 120_000L

    /**
     * Back, from the steering wheel or the unit's key, while the user is in an
     * app inside a tile (touched within [TILE_BACK_MS], nothing on the
     * dashboard touched since): sent to that app on its display, where it
     * closes its search, its route choices, whatever it opened. True when
     * sent; false when there is no such app, and Back is the dashboard's.
     */
    fun backInTile(): Boolean {
        val host = tileInUse ?: return false
        if (SystemClock.elapsedRealtime() - tileUsedAt > TILE_BACK_MS || !holds(host.packageName)) return false
        if (!host.key(KeyEvent.KEYCODE_BACK)) return false
        Log.i(TAG, "Back sent to ${host.packageName} on its tile")
        return true
    }

    /**
     * The touch is over. Elsewhere on the dashboard: the keys come back now,
     * and Back is the dashboard's again. In a tile: the tap may have opened a
     * screen of the app there, taking the keys with it; they come back
     * shortly, once no keyboard is up for it.
     */
    fun touchEnds() {
        if (!tileTouched) {
            tileInUse = null
            giveKeysBack()
            return
        }
        if (held.isEmpty()) return
        keysAway = true
        givingBack?.cancel()
        givingBack = mainScope.launch {
            repeat(GIVE_BACK_TRIES) { look ->
                delay(if (look == 0) KEYBOARD_GRACE_MS else GIVE_BACK_MS)
                val activity = dashboard?.get() ?: return@launch
                if (!keyboardUp(activity)) {
                    giveKeysBack()
                    return@launch
                }
            }
        }
    }

    /** Whether the on-screen keyboard shows (for an app inside a tile, typing a search). */
    private fun keyboardUp(context: Context): Boolean = Edition.full && runCatching {
        // @hide, public on Android 10 and 11.
        val imm = context.getSystemService(InputMethodManager::class.java)
        (HiddenApi.invoke(InputMethodManager::class.java, imm, "getInputMethodWindowVisibleHeight") as Int) > 0
    }.getOrDefault(false)

    /**
     * A key seen by the accessibility service before anyone else, while a
     * tile's display may still have the keys: Home and Back are held back, the
     * main screen brought in front, and the key done again there. True when
     * taken.
     */
    fun keyWhileAway(service: AccessibilityService, event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) userActed()
        // A key held back when pressed is done again when let go, whatever happened meanwhile.
        val held = event.action == KeyEvent.ACTION_UP && event.keyCode == heldKey
        if (!held && (!keysAway || dashboard?.get() == null)) return false
        // Back while the user is in a tile's app goes to that app, once (the unit
        // would deliver the key twice if it were handed back).
        if (event.keyCode == KeyEvent.KEYCODE_BACK && tileInUse != null) {
            if (event.action == KeyEvent.ACTION_DOWN) heldKey = event.keyCode
            if (event.action == KeyEvent.ACTION_UP) {
                heldKey = 0
                if (!backInTile()) mainScope.launch {
                    giveKeysBack()?.join()
                    delay(KEY_AGAIN_MS)
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                }
            }
            return true
        }
        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            KeyEvent.KEYCODE_BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            else -> {
                // Volume and the rest go through as they are; the next ones reach the main screen.
                mainScope.launch { giveKeysBack() }
                return false
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN) heldKey = event.keyCode
        if (event.action == KeyEvent.ACTION_UP) {
            heldKey = 0
            mainScope.launch {
                giveKeysBack()?.join()
                delay(KEY_AGAIN_MS)
                service.performGlobalAction(action)
            }
        }
        return true
    }

    /** The K706's panel key for the screen off, handled by the firmware itself (QFBacklight). */
    private const val SCREEN_OFF_KEY = 315

    /**
     * A key no one here wanted, seen while the accessibility service filters the
     * unit's keys. On the QF firmware every key reaches the firmware once before the
     * service sees it, and a key the service hands back reaches it a second time: a
     * press came out as a press and a long press (the K706, 2026-10-14: the screen-off
     * key switched off then back on, Home did a long press). Kept here instead: the
     * screen-off key (the firmware switched off on its first look), and Home, done
     * here once the keys are surely on the main screen. True when kept.
     */
    fun firmwareKey(service: AccessibilityService, event: KeyEvent): Boolean {
        if (UnitPlatform.adapter.family != UnitFamily.QF) return false
        return when (event.keyCode) {
            SCREEN_OFF_KEY -> true
            KeyEvent.KEYCODE_HOME -> {
                if (event.action == KeyEvent.ACTION_UP) mainScope.launch {
                    // The keys may be on a tile's display though no tile was touched lately.
                    giveKeysBack(force = !dashboardFocused)?.join()
                    delay(KEY_AGAIN_MS)
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                }
                true
            }
            else -> false
        }
    }

    /** A launch or a move just put a tile's display in front: the keys come back at once. */
    private fun launchedOnTile() {
        if (held.isEmpty()) return
        keysAway = true
        mainScope.launch { giveKeysBack() }
    }

    // --- A tile left black ----------------------------------------------------
    //
    // Putting the app back where the listing says it is not was not enough on
    // the head unit: back from the app opened full screen, the tile stayed
    // black. So the tile is looked at again a while after each placing: where
    // the app is, and whether the tile's picture is still one flat black. What
    // is tried then grows ([remedy]): placed again, the picture handed to the
    // display again, and last the app closed and opened afresh on the tile,
    // the way it gets there when the dashboard starts.

    /** How long after a placing, then after each look, the tile is looked at: 1.5, 3, 6 and 10 seconds in. */
    private val LOOK_AFTER_MS = longArrayOf(1_500L, 1_500L, 3_000L, 4_000L)

    /** An app started that recently is not closed to be opened afresh: it may still be loading. */
    private const val FRESH_MS = 30_000L

    /** The shortest time between two closings of an app whose tile stays black. */
    private const val REOPEN_EVERY_MS = 60_000L

    /** How long the display draws on its spare picture before the tile's is handed back. */
    private const val REFRESH_MS = 300L

    /** What a look at the tile calls for. */
    enum class Remedy {
        /** The app shows on its tile. */
        DONE,
        /** Nothing yet: the app may still be drawing its first picture. */
        WAIT,
        /** Put on the tile again. */
        PLACE,
        /** The tile's picture handed to the display again. */
        REFRESH,
        /** Closed, and opened afresh on the tile. */
        REOPEN,
        /** No longer running (its process ended): what it left closed, and opened afresh on the tile. */
        START,
        /** Nothing left to try. */
        GIVE_UP
    }

    /**
     * What to do at look number [look] (from 0): the app is [onTile] or not by
     * the listing, the tile's picture is [black] or not. An app elsewhere is
     * placed again twice before it is closed; one on its tile with a black
     * picture gets time, then its picture again, and is closed last, after
     * ten seconds of black. Closing only when [mayReopen]. An app no longer
     * [running] is opened afresh at once, twice at most: there is nothing of
     * it to close, so nothing to wait for (Waze's picture stayed frozen on its
     * tile once its process ended, issue #60).
     */
    fun remedy(look: Int, onTile: Boolean, black: Boolean, mayReopen: Boolean, running: Boolean = true): Remedy = when {
        !running -> if (look < 2) Remedy.START else Remedy.GIVE_UP
        onTile && !black -> Remedy.DONE
        !onTile && look < 2 -> Remedy.PLACE
        onTile && look == 1 -> Remedy.REFRESH
        onTile && look < 3 -> Remedy.WAIT
        mayReopen -> Remedy.REOPEN
        else -> Remedy.GIVE_UP
    }

    /**
     * True when most of a picture's [pixels] are one same colour: an app
     * whose content is not drawn, only its bars and buttons (Google Maps
     * woken on its tile: the search box over a blank map).
     */
    fun mostlyFlat(pixels: IntArray, share: Float = FLAT_SHARE): Boolean {
        if (pixels.isEmpty()) return false
        val counts = HashMap<Int, Int>()
        var top = 0
        for (p in pixels) {
            // Colours a few steps apart count as one: a picture's compression and smoothing.
            val key = (p shr 4) and 0x0F0F0F
            val n = (counts[key] ?: 0) + 1
            counts[key] = n
            if (n > top) top = n
        }
        return top >= pixels.size * share
    }

    /** True when a picture's [pixels] are one flat black, or nothing at all: no app is drawn there. */
    fun allBlack(pixels: IntArray): Boolean =
        pixels.isNotEmpty() && pixels.all { it == 0 || it == 0xFF000000.toInt() }

    /**
     * Which of an app's tiles gets the picture: [current] while it is still on
     * screen, so a swipe halfway never flips it back and forth; else a tile on
     * screen; else, none showing (the pages beside, arranging), [current] as
     * long as it has a picture to draw on; else the latest tile with one.
     * [ready] are the tiles with a picture to draw on, oldest first.
     */
    fun <T> pickTile(current: T?, ready: List<T>, onScreen: Set<T>): T? =
        current?.takeIf { it in ready && it in onScreen }
            ?: ready.lastOrNull { it in onScreen }
            ?: current?.takeIf { it in ready }
            ?: ready.lastOrNull()

    /**
     * True when an app's [stacks] show it running twice, as the unit makes it
     * at power-up: once on its tile's display [tileDisplay] and once more full
     * screen elsewhere. A floating window elsewhere does not count, it is closed
     * the usual way.
     */
    fun runsTwice(stacks: List<WindowListing.AppStack>, tileDisplay: Int): Boolean =
        stacks.any { it.displayId == tileDisplay } &&
            stacks.any { it.displayId != tileDisplay && it.mode != "freeform" && it.mode != "pinned" }

    /**
     * The apps whose display stays open: those inside a tile of the
     * arrangement on screen ([here]), and those inside a tile of the other
     * one ([other]) unless the arrangement on screen shows them in a window
     * ([windows], Maps docked beside the pages). An app runs once: held inside
     * a tile nobody sees, it never reached that window, which stayed empty
     * while the app floated wherever Android put it.
     */
    fun keptInside(here: Set<String>, other: Set<String>, windows: Set<String>): Set<String> =
        here + (other - windows)

    /** Closes the displays of the apps not in [keep]; each app closes with its display. */
    fun releaseUnless(keep: Set<String>) {
        val gone = hosts.keys - keep
        gone.forEach { pkg -> hosts.remove(pkg)?.let { it.retired = true; it.release() } }
        if (gone.isNotEmpty()) _hosted.value = hosts.keys.toSet()
    }

    /**
     * One app's display: made on the first picture, then only given a new one,
     * from whichever of the app's tiles is on screen. The tiles, and the calls
     * below, all live on the main thread.
     */
    class Host(private val context: Context, val packageName: String) {
        init {
            loadQuirks(context)
        }

        /** When a screen was last asked for on the tile's display (elapsedRealtime): a start, an intent, a player. */
        @Volatile private var openedAt = 0L

        /**
         * The app whose windows show this one: [packageName] itself, or the
         * app its icon hands off to ([WindowListing.handedTo]).
         */
        @Volatile
        internal var windowPackage = packageName
            private set

        /**
         * A root shell command that starts the app instead of its icon's
         * launch, once (`--display` is added): Gemini started straight in
         * Live ([GeminiLive]). Used by the next start, then forgotten.
         */
        @Volatile
        var startCommand: String? = null

        private val _placed = MutableStateFlow(0L)
        /** When the app was last seen in place on its display (elapsedRealtime). */
        val placed: StateFlow<Long> = _placed.asStateFlow()

        private val _status = MutableStateFlow(Status.STARTING)
        val status: StateFlow<Status> = _status.asStateFlow()

        @Volatile
        private var display: TileDisplay? = null
        @Volatile
        private var touchRefused = false
        private var settling: Job? = null

        /** One tile of the app: its picture, once it has one, and whether it is on screen. */
        private class Tile {
            var surface: Surface? = null
            var width = 0
            var height = 0
            var dpi = 0
            var onScreen = false
            /** The tile's picture now, a quarter of its size each way; null when it has none. Main thread. */
            var picture: () -> IntArray? = { null }

            /** Whether the tile's picture is all black right now. */
            fun black(): Boolean = picture()?.let(::allBlack) ?: false
        }

        private val tiles = LinkedHashMap<Any, Tile>()

        /** The tile the display draws on, and the picture and size it was given. */
        private var shownOn: Any? = null
        private var shownSurface: Surface? = null
        @Volatile
        private var shownWidth = 0
        @Volatile
        private var shownHeight = 0
        private var shownDpi = 0

        /**
         * A picture nobody looks at, for the display while no tile can take
         * it (arranging, a page no longer kept): with no picture at all
         * Android 10 switches the display off and the app sleeps, and on the
         * head unit Google Maps wakes black.
         */
        private var spareTexture: SurfaceTexture? = null
        private var spare: Surface? = null

        /**
         * Draws the app's frames on its tile and on copies of it (the second
         * screen), while there are copies; null otherwise, and the display
         * then draws straight on the tile, as it always did.
         */
        private var relay: PictureRelay? = null
        private val copyList = ArrayList<Surface>()

        /** What the display draws on while there is no tile to draw on: the relay's input when copies want the frames, else the [spare]. */
        private fun idlePicture(width: Int, height: Int): Surface =
            relay?.let { r -> r.setTile(null); r.input(width, height) } ?: spare(width, height)

        /** What the display draws on to show [tile]: the relay's input, which passes the frames on to it, or the tile itself. */
        private fun feed(vd: TileDisplay, tile: Surface, width: Int, height: Int) {
            val input = relay?.input(width, height)
            if (input == null) {
                vd.surface = tile
                return
            }
            // The display lets go of the tile's picture first; the relay takes it over after.
            vd.surface = input
            relay?.setTile(tile)
        }

        /** The relay, made when the app has none yet and the display not drawing through one; null where GL can't. */
        private fun startRelay(): PictureRelay? {
            relay?.let { return it }
            val r = PictureRelay().takeIf { it.works } ?: return null
            relay = r
            val vd = display ?: return r
            // Already drawing straight on the tile: through the relay from now on.
            val tile = shownSurface
            if (tile != null) feed(vd, tile, shownWidth, shownHeight) else vd.surface = idlePicture(shownWidth, shownHeight)
            return r
        }

        /** [surface] shows a copy of the app's picture: the frames go through a relay from now on. Main thread. */
        fun addCopy(surface: Surface) {
            if (copyList.any { it === surface }) return
            copyList += surface
            startRelay()?.addCopy(surface)
        }

        /** [surface] no longer shows a copy; the last one gone, the display draws straight on the tile again. Main thread. */
        fun removeCopy(surface: Surface) {
            if (copyList.none { it === surface }) return
            copyList.removeAll { it === surface }
            val r = relay ?: return
            r.removeCopy(surface)
            // Kept while the cluster wants the copy (its page turned away for now): handing the picture back and forth blacks it out.
            if (copyList.isNotEmpty() || copyWanted(packageName)) return
            // Off the relay before it goes, so the app never has nothing to draw on.
            display?.surface = spare(shownWidth, shownHeight)
            r.release()
            relay = null
            val vd = display ?: return
            shownSurface?.let { vd.surface = it }
        }

        /** The spare picture, [width] x [height] pixels. Main thread. */
        private fun spare(width: Int, height: Int): Surface {
            val texture = spareTexture ?: SurfaceTexture(false).also { spareTexture = it }
            if (width > 0 && height > 0) texture.setDefaultBufferSize(width, height)
            return spare ?: Surface(texture).also { spare = it }
        }

        /**
         * Out of [hosts] for good ([releaseUnless]): a card still holding it
         * (its picture listener, Gemini's alert) would otherwise make it a new
         * display that nothing ever closed, the app running on it until a reboot.
         */
        internal var retired = false

        /** [tile] shows [surface], [width] x [height] pixels at [dpi]; [picture] reads what it shows. */
        fun attach(tile: Any, surface: Surface, width: Int, height: Int, dpi: Int, picture: () -> IntArray? = { null }) {
            if (retired) return
            val t = tiles.getOrPut(tile) { Tile() }
            t.picture = picture
            t.surface = surface
            t.width = width
            t.height = height
            t.dpi = dpi
            route()
        }

        /** [tile] came on screen, or went off it (a page swipe). */
        fun onScreen(tile: Any, on: Boolean) {
            if (retired) return
            val t = tiles.getOrPut(tile) { Tile() }
            if (t.onScreen == on) return
            t.onScreen = on
            route()
        }

        /**
         * [tile] lost its picture (a page no longer kept, arranging): the
         * display moves to another tile of the app if there is one, else draws
         * on its spare picture, so the app keeps running.
         */
        fun detach(tile: Any) {
            tiles[tile]?.surface = null
            route()
        }

        /** The app's own display while it has one: for a command sent to it ([GeminiLive]). */
        val displayId: Int? get() = display?.displayId

        /** Whether a tile other than [mine] shows this app too. */
        fun heldBesides(mine: Collection<Any>): Boolean = tiles.keys.any { tile -> mine.none { it === tile } }

        /** [tile] is gone for good. */
        fun forget(tile: Any) {
            tiles.remove(tile)
            route()
        }

        /**
         * Gives the display to the tile [pickTile] chooses, when it is not
         * already there. A tile on a page beside the one on screen keeps its
         * picture, and with no tile left to draw on the display gets its
         * [spare] one: it is never left without, see there.
         */
        private fun route() {
            val ready = tiles.filterValues { it.surface != null }.keys.toList()
            val onScreen = tiles.filterValues { it.onScreen }.keys
            val next = pickTile(shownOn, ready, onScreen)
            val t = next?.let { tiles[it] }
            val surface = t?.surface
            if (t == null || surface == null) {
                shownOn = null
                shownSurface = null
                display?.let { it.surface = idlePicture(shownWidth, shownHeight) }
                return
            }
            if (next == shownOn && surface === shownSurface &&
                t.width == shownWidth && t.height == shownHeight && t.dpi == shownDpi
            ) return
            shownOn = next
            shownSurface = surface
            shownWidth = t.width
            shownHeight = t.height
            shownDpi = t.dpi
            noteShape(packageName, t.width, t.height)
            show(surface, t.width, t.height, t.dpi)
        }

        @SuppressLint("WrongConstant") // DESTROY_CONTENT_ON_REMOVAL is a real flag, only @hide.
        private fun show(surface: Surface, width: Int, height: Int, dpi: Int) {
            val vd = display
            if (vd != null) {
                vd.resize(width, height, dpi)
                feed(vd, surface, width, height)
                // Brought back to the front, started again if it was closed meanwhile,
                // or opened now if it was installed since. A refusal stays refused.
                if (_status.value != Status.BLOCKED) launch(vd)
                return
            }
            if (SystemDisplays.state.value == SystemDisplays.State.STARTING) {
                // The system's display helper is still starting: the display waits
                // for it, so the app keeps every screen it opens on it (SystemDisplays).
                if (awaitingHelper?.isActive != true) awaitingHelper = mainScope.launch {
                    SystemDisplays.awaitDecided()
                    val s = shownSurface ?: return@launch
                    if (display == null && _status.value != Status.BLOCKED) show(s, shownWidth, shownHeight, shownDpi)
                }
                return
            }
            if (copyWanted(packageName)) startRelay()
            val made = runCatching {
                TileDisplay.create(
                    context, "Dashwheel:$packageName", width, height, dpi, relay?.input(width, height) ?: surface,
                    FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_DESTROY_CONTENT_ON_REMOVAL
                )
            }.onFailure { Log.w(TAG, "no display for $packageName", it) }.getOrNull()
            if (made == null) {
                _status.value = Status.BLOCKED
                return
            }
            display = made
            relay?.setTile(surface)
            // The window tiles now leave this app and this display alone.
            WindowListing.embeddedDisplays = WindowListing.embeddedDisplays + made.displayId
            held = held + packageName
            // The dashboard's own context, so it also takes the focus back that a docked window had.
            PipAnchor.takenInside(dashboard?.get() ?: context, packageName)
            Log.i(TAG, "display ${made.displayId}${if (made.system) " (the system's)" else ""} for $packageName, ${width}x$height at $dpi dpi")
            launch(made)
        }

        /** The wait for the system's display helper before the display is made ([show]). */
        private var awaitingHelper: Job? = null

        /**
         * The helper owning this app's display died, and the display and the
         * app with it: a display of Dashwheel's own is made and the app opened
         * on it, as long as a tile still shows it. Main thread.
         */
        fun displayLost() {
            val vd = display ?: return
            if (!vd.system) return
            settling?.cancel()
            WindowListing.embeddedDisplays = WindowListing.embeddedDisplays - vd.displayId
            display = null
            _status.value = Status.STARTING
            Log.w(TAG, "$packageName lost display ${vd.displayId} with the system's helper")
            val s = shownSurface ?: return
            show(s, shownWidth, shownHeight, shownDpi)
        }

        /**
         * Hands [intent] (guidance to a place...) to the app on its tile, on
         * the tile's display, rather than full screen over the dashboard;
         * then makes sure it is still on the tile (some ROMs open on the main
         * screen whatever display is asked for). False when the tile's app
         * isn't running there, or Android refused.
         */
        fun open(intent: Intent): Boolean {
            val vd = display ?: return false
            if (_status.value != Status.SHOWN) return false
            intent.setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(vd.displayId)
            runCatching { HiddenApi.invoke(ActivityOptions::class.java, options, "setLaunchWindowingMode", WINDOWING_MODE_FULLSCREEN) }
            openedAt = SystemClock.elapsedRealtime()
            val ok = runCatching { context.startActivity(intent, options.toBundle()) }
                .onFailure { Log.w(TAG, "$packageName refused the intent on display ${vd.displayId}", it) }
                .isSuccess
            if (ok) {
                launchedOnTile()
                // Placed again even while a placing runs: the intent may have opened a new task elsewhere.
                settling?.cancel()
                launch(vd)
            }
            return ok
        }

        /** [screen], one of the app's own (its player), sent onto the tile's display as [open] does an intent. */
        fun open(screen: PendingIntent): Boolean {
            val vd = display ?: return false
            if (_status.value != Status.SHOWN) return false
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(vd.displayId)
            runCatching { HiddenApi.invoke(ActivityOptions::class.java, options, "setLaunchWindowingMode", WINDOWING_MODE_FULLSCREEN) }
            // Android 14 and later start another app's screen for us only when asked to.
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 34) options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            openedAt = SystemClock.elapsedRealtime()
            val ok = runCatching { screen.send(context, 0, null, null, null, null, options.toBundle()) }
                .onFailure { Log.w(TAG, "$packageName refused its screen on display ${vd.displayId}", it) }
                .isSuccess
            if (ok) {
                launchedOnTile()
                settling?.cancel()
                launch(vd)
            }
            return ok
        }

        /** Puts the app back on the tile if it was taken elsewhere meanwhile; nothing when it is still there. */
        fun bringBack() {
            val vd = display ?: return
            if (shownOn == null || _status.value == Status.BLOCKED) return
            launch(vd)
        }

        /**
         * Full screen in front of the main screen: put back on the tile. Main
         * thread. The caller forgets the shell's listing first ([watchFront]):
         * one listing serves every app looked at together. True while it is
         * left there for a screen of its own to close.
         */
        suspend fun bringBackFromFront(): Boolean {
            val vd = display ?: return false
            if (shownOn == null || _status.value == Status.BLOCKED || settling?.isActive == true) return false
            val listing = try {
                DockShell.listStacks(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return false
            }
            val front = WindowListing.fullscreenInFront(listing, windowPackage, context.packageName) ?: return false
            // Waze opens its voice prompt, its search, a report or a permission
            // request full screen by itself. Everything belongs in the tile (its
            // search gets no keyboard anywhere else), so that screen goes back at
            // once, and starts over there. Only a screen that keeps jumping out
            // again after each move is left full screen until it closes, so the
            // two never fight for it.
            val own = front.taskId?.let { WindowListing.ownScreenOf(listing, it) }
            if (own != null && !keepsJumpingOut(front.taskId)) {
                waitingFor = null
                ownClosedAt = 0L
                voiceAsked = false
                Log.i(TAG, "$packageName full screen with ${own.substringAfterLast('.')} (task ${front.taskId}): back onto its tile")
                launch(vd)
                return false
            }
            if (own != null) {
                if (waitingFor != front.taskId) {
                    Log.i(TAG, "$packageName full screen with a screen of its own: back onto its tile once it closes")
                    voiceAsked = false
                }
                waitingFor = front.taskId
                ownClosedAt = 0L
                if (WindowListing.isVoiceScreen(own)) voiceAsked = true
                return true
            }
            // That screen just closed: the app is given a moment before it moves. Waze
            // opens its search results right after the voice screen, and moved back
            // onto its tile in between, it never opened them (the place said, then nothing).
            if (waitingFor != null && waitingFor == front.taskId) {
                val now = SystemClock.elapsedRealtime()
                if (ownClosedAt == 0L) ownClosedAt = now
                if (now - ownClosedAt < if (voiceAsked) VOICE_SETTLE_MS else OWN_SCREEN_SETTLE_MS) return true
            }
            waitingFor = null
            ownClosedAt = 0L
            voiceAsked = false
            Log.i(TAG, "$packageName came full screen by itself (task ${front.taskId}): back onto its tile")
            launch(vd)
            return false
        }

        /** The task last moved back with a screen of its own up, and when ([keepsJumpingOut]). */
        private var jumpedTask: Int? = null
        private val jumpedAt = ArrayDeque<Long>()

        /**
         * Whether task [taskId] came back full screen [MAX_JUMPS] times within
         * [JUMPS_WINDOW_MS] with a screen of its own up: moved onto the tile, that
         * screen opened "beside" itself again. Counts this time as a move.
         */
        private fun keepsJumpingOut(taskId: Int?): Boolean {
            val now = SystemClock.elapsedRealtime()
            if (taskId != jumpedTask) { jumpedTask = taskId; jumpedAt.clear() }
            while (jumpedAt.isNotEmpty() && now - jumpedAt.first() > JUMPS_WINDOW_MS) jumpedAt.removeFirst()
            if (jumpedAt.size >= MAX_JUMPS) return true
            jumpedAt.addLast(now)
            return false
        }

        /** The task last left full screen for a screen of its own, so that is said once. */
        private var waitingFor: Int? = null

        /** When that screen was first seen closed (elapsedRealtime), 0 while it is up. */
        private var ownClosedAt = 0L

        /** One of those screens listened for speech: the app gets [VOICE_SETTLE_MS] to show what it heard. */
        private var voiceAsked = false

        fun release() {
            settling?.cancel()
            tiles.clear()
            shownOn = null
            shownSurface = null
            display?.let { vd ->
                WindowListing.embeddedDisplays = WindowListing.embeddedDisplays - vd.displayId
                vd.release()
            }
            relay?.release()
            relay = null
            copyList.clear()
            spare?.release()
            spare = null
            spareTexture?.release()
            spareTexture = null
            held = held - packageName
            scope.launch { FreeformBar.insideFloats(context, packageName, false) }
            display = null
            if (forwarder != null) touchHandler.post { dropForwarder() }
            _status.value = Status.STARTING
        }

        /**
         * Puts the app on the tile, full size, in the background. Already there:
         * nothing is done, so coming back to the page never pulls it out again.
         * Running full screen elsewhere: moved over. In a floating window (the
         * app's window tile, picture-in-picture): that window would keep its
         * size and spot once moved, a corner of the tile, so it is closed and
         * the app opened afresh. Not running: started, then moved over.
         */
        private fun launch(vd: TileDisplay) {
            if (settling?.isActive == true) return
            settling = scope.launch {
                place(vd)
                watch(vd)
            }
        }

        /** When the app was last started, and last closed for a tile left black (elapsedRealtime, 0 if never). */
        @Volatile
        private var startedAt = 0L
        private var reopenedAt = 0L

        /**
         * Looks at the tile again after a placing, and acts while the app does
         * not show there, see [remedy]. Only while the dashboard is in front
         * with this tile's picture on the display: an app the user opens full
         * screen meanwhile is left there. An app opened afresh is looked at
         * once more: it is not closed a second time ([REOPEN_EVERY_MS]), only
         * placed again and given its picture again.
         */
        private suspend fun watch(vd: TileDisplay) {
            if (looks(vd)) looks(vd)
        }

        /** One round of looks at the tile; true when it ended with the app opened afresh. */
        private suspend fun looks(vd: TileDisplay): Boolean {
            val id = vd.displayId
            for ((look, wait) in LOOK_AFTER_MS.withIndex()) {
                delay(wait)
                if (_status.value != Status.SHOWN || display !== vd || dashboard?.get() == null) return false
                val black = withContext(Dispatchers.Main) { if (shownSurface == null) null else pictureBlack() } ?: return false
                val found = allStacks() ?: return false
                val live = found.filterNot { it.empty }
                val onTile = live.isNotEmpty() && live.all { it.displayId == id && it.visible }
                val now = SystemClock.elapsedRealtime()
                val mayReopen = now - startedAt > FRESH_MS && (reopenedAt == 0L || now - reopenedAt > REOPEN_EVERY_MS)
                // Its stacks listed, its process gone: not running either. Not asked
                // of an app just started, so one pidof wrong about it can't close it again and again.
                val gone = live.isNotEmpty() && !(onTile && !black) && now - startedAt > FRESH_MS && processGone()
                val remedy = remedy(look, onTile, black, mayReopen, running = live.isNotEmpty() && !gone)
                if (remedy == Remedy.DONE) {
                    if (look > 0) Log.i(TAG, "$packageName shows on its tile")
                    return false
                }
                val where = if (found.isEmpty()) "not running" else found.joinToString {
                    "stack ${it.stackId} ${it.mode} on display ${it.displayId}" + (if (it.visible) "" else " hidden") +
                        if (it.empty) " empty" else ""
                } + if (gone) ", no process" else ""
                Log.w(TAG, "$packageName does not show on its tile (display $id), look ${look + 1}: $where" +
                    (if (black) ", black picture" else "") + ": $remedy")
                when (remedy) {
                    Remedy.PLACE -> place(vd)
                    Remedy.START -> {
                        // Screens kept without a process: closed, or the start would only bring them back as they were.
                        if (live.isNotEmpty()) {
                            shell("am force-stop $packageName", "$packageName has no process left: closed, to open it afresh on its tile")
                            delay(CLOSE_WAIT_MS)
                        }
                        // The stacks it left empty go in the placing, then it is started.
                        place(vd)
                    }
                    Remedy.REFRESH -> withContext(Dispatchers.Main) { refresh(vd) }
                    Remedy.REOPEN -> {
                        reopen(vd)
                        return true
                    }
                    Remedy.GIVE_UP -> return false
                    else -> Unit
                }
            }
            return false
        }

        /**
         * After the unit slept (the ignition off and on), Google Maps on its
         * tile came back with its search box over a white map, and stayed so
         * until closed. So once the ignition is back, a tile whose picture is
         * mostly one colour and does not move at all gets its picture again
         * with the display's size nudged (drawn afresh), and if that is not
         * enough the app is closed and opened afresh on its tile. A live map
         * has detail, and its location dot moves.
         */
        suspend fun checkAfterWake() {
            val vd = display ?: return
            if (_status.value != Status.SHOWN || shownOn == null || dashboard?.get() == null) return
            if (blankAndStill() != true) return
            Log.w(TAG, "$packageName woke blank on its tile: drawn afresh")
            withContext(Dispatchers.Main) { refresh(vd, nudge = true) }
            delay(STILL_PICTURE_MS)
            if (display !== vd || blankAndStill() != true) return
            val now = SystemClock.elapsedRealtime()
            if (reopenedAt != 0L && now - reopenedAt < REOPEN_EVERY_MS) return
            if (settling?.isActive == true) return
            Log.w(TAG, "$packageName still blank on its tile: opened afresh")
            settling = scope.launch {
                reopen(vd)
                watch(vd)
            }
        }

        /** Whether the tile on screen stays mostly one colour and unchanged for a few seconds; null when it can't be seen. */
        private suspend fun blankAndStill(): Boolean? {
            val first = withContext(Dispatchers.Main) { shownPicture() } ?: return null
            if (!mostlyFlat(first)) return false
            delay(STILL_PICTURE_MS)
            val second = withContext(Dispatchers.Main) { shownPicture() } ?: return null
            return second.contentEquals(first)
        }

        /** The picture of the tile on screen. Main thread. */
        private fun shownPicture(): IntArray? {
            val tile = shownOn?.let { tiles[it] } ?: return null
            return if (tile.onScreen) tile.picture() else null
        }

        /** Whether the tile on screen shows one flat black. Main thread. */
        private fun pictureBlack(): Boolean {
            val tile = shownOn?.let { tiles[it] } ?: return false
            return tile.onScreen && tile.black()
        }

        /**
         * Hands the display its [spare] picture for a moment, then the tile's
         * again: the system draws the whole display afresh for it. Never no
         * picture at all, which switches the display off. Main thread.
         */
        private suspend fun refresh(vd: TileDisplay, nudge: Boolean = false) {
            if (display !== vd || shownSurface == null) return
            vd.surface = spare(shownWidth, shownHeight)
            // A size a pixel off makes the app lay itself out and draw everything again.
            if (nudge && shownWidth > 1) vd.resize(shownWidth - 1, shownHeight, shownDpi)
            delay(REFRESH_MS)
            // The tile may have changed meanwhile, or gone: the display was seen to then.
            val surface = shownSurface ?: return
            if (display !== vd) return
            vd.resize(shownWidth, shownHeight, shownDpi)
            feed(vd, surface, shownWidth, shownHeight)
        }

        /** [reopen] now, asked from outside ([WazeWake]); false while there is no display to open it on. */
        fun restart(): Boolean {
            val vd = display ?: return false
            // No shell: the force-stop does nothing and the frozen app stays as it is.
            if (!PrivilegedShell.access.value.shell) return false
            if (_status.value == Status.MISSING || _status.value == Status.BLOCKED) return false
            settling?.cancel()
            settling = scope.launch {
                reopen(vd)
                watch(vd)
            }
            return true
        }

        /** Closes the app and opens it afresh on the tile, the way it gets there when the dashboard starts. */
        private suspend fun reopen(vd: TileDisplay) {
            reopenedAt = SystemClock.elapsedRealtime()
            _status.value = Status.STARTING
            shell("am force-stop $packageName", "$packageName closed, to open it afresh on its tile")
            delay(CLOSE_WAIT_MS)
            place(vd)
        }

        private suspend fun place(vd: TileDisplay) {
            try {
                placeOnTile(vd)
            } finally {
                MainScreenCover.uncover(packageName)
            }
        }

        private suspend fun placeOnTile(vd: TileDisplay) {
            val id = vd.displayId
            val all = allStacks() ?: run {
                // No shell to look with: the launch alone, which lands on the tile on most ROMs.
                start(vd)
                return
            }
            // A stack left empty when the app's process ended is not the app: taken for
            // it on the tile, the tile kept its last picture and the app was never started again.
            // Removed only while the app has no window left anywhere: removing a task is what a
            // swipe out of Recents does, and an app still running on its tile takes it as being
            // closed. YouTube stopped its video each time the dashboard came back to its page.
            var found: List<WindowListing.AppStack> = all.filterNot { it.empty }
            if (found.isEmpty()) for (stack in all) if (stack.empty) {
                shell("am stack remove ${stack.stackId}", "$packageName stack ${stack.stackId} on display ${stack.displayId} left empty, closed")
            }
            if (runningTwice(found, id)) {
                // Moved onto the tile beside the first, the second copy would only hide
                // it, both still broken: the app is closed, then opened once, on the tile.
                restartedAt = SystemClock.elapsedRealtime()
                shell("am force-stop $packageName", "$packageName runs twice, on its tile and on display " +
                    found.first { it.displayId != id }.displayId + ": closed, to open it once")
                delay(CLOSE_WAIT_MS)
                found = listStacks() ?: return
            }
            var fresh = false
            // The listing before the start, to see what it handed off to.
            var before: String? = null
            var settled = false
            val filled = HashSet<Int>()
            repeat(MOVE_ROUNDS) {
                // Its first screen showed and went: what it opened instead belongs to another app.
                if (found.isEmpty() && before != null) found = awaitHandOff(before!!)
                if (found.isEmpty()) {
                    if (fresh) return
                    before = rawListing()
                    // Where it opens on the main screen first, the dashboard's picture hides it meanwhile.
                    if (opensOnMainScreen) MainScreenCover.cover(packageName)
                    start(vd)
                    fresh = true
                    if (_status.value != Status.SHOWN) return
                    // This ROM opens it full screen on the main screen whatever display is
                    // asked for: caught as soon as it shows, so it only flashes there.
                    found = awaitStacks()
                    if (found.isEmpty()) found = before?.let { awaitHandOff(it) } ?: emptyList()
                    if (found.isEmpty()) return
                }
                val floating = found.filter { floating(it) && it.stackId !in filled }
                if (found.all { it.displayId == id } && floating.isEmpty()) {
                    val handOff = before
                    if (fresh && !settled && handOff != null) {
                        // Gemini's first screen reaches the tile and stays there hidden, while
                        // the Google app's opens full screen: looked at once more a moment later.
                        settled = true
                        delay(HAND_OFF_MS)
                        val now = rawListing() ?: return
                        // Its own screen in view is no hand-off, whatever else came up meanwhile.
                        val own = WindowListing.appStacks(now, windowPackage).filterNot { it.empty }
                        val other = if (own.any { it.visible }) null
                            else WindowListing.handedTo(handOff, now, packageName, context.packageName, claimedBesides(this))
                        found = if (other != null) follow(other, now) else own
                        if (found.isEmpty() || found.any { it.displayId != id }) return@repeat
                    }
                    _status.value = Status.SHOWN
                    tileTask = found.firstOrNull { it.displayId == id }?.taskId
                    Log.i(TAG, "$packageName is on the tile")
                    _placed.value = SystemClock.elapsedRealtime()
                    FreeformBar.insideFloats(context, packageName, found.any { floating(it) })
                    return
                }
                val closing = !fresh && floating.isNotEmpty()
                for (stack in found) {
                    if (closing && stack in floating) {
                        shell("am stack remove ${stack.stackId}", "$packageName ${stack.mode} stack ${stack.stackId} on display ${stack.displayId} closed")
                        continue
                    }
                    if (stack.displayId != id) {
                        // Its screen landed on the main screen right after being asked for on the tile: this unit's way.
                        if (stack.displayId == 0 && SystemClock.elapsedRealtime() - openedAt < LEARN_MS) learnOpensOnMainScreen(context)
                        shell("am display move-stack ${stack.stackId} $id", "$packageName stack ${stack.stackId} (${stack.mode}) from display ${stack.displayId} to $id")
                        launchedOnTile()
                    }
                    if (stack in floating) {
                        // Even started afresh it floats (this ROM's choice): stretched to fill the tile.
                        // Floating, the firmware's switch for the status bar would draw it lower down.
                        FreeformBar.insideFloats(context, packageName, true)
                        // Android 12 took `am stack resize` away: the task is resized there instead.
                        val resize = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && stack.taskId != null) "am task resize ${stack.taskId} 0 0 $shownWidth $shownHeight"
                            else "am stack resize ${stack.stackId} 0 0 $shownWidth $shownHeight"
                        shell(resize, "$packageName ${stack.mode} stack ${stack.stackId} fills the tile")
                        filled += stack.stackId
                    }
                }
                delay(if (closing) CLOSE_WAIT_MS else POLL_MS)
                found = listStacks() ?: return
            }
            Log.w(TAG, "$packageName still off the tile: " + found.joinToString { "stack ${it.stackId} ${it.mode} on display ${it.displayId}" })
        }

        private fun floating(stack: WindowListing.AppStack) = stack.mode == "freeform" || stack.mode == "pinned"

        /** When the app was last closed for running twice (elapsedRealtime), 0 if never. */
        private var restartedAt = 0L

        /**
         * [runsTwice], once in [RESTART_COOLDOWN_MS] at most, so an app that
         * opens a second screen of its own on purpose is not closed over and over.
         */
        private fun runningTwice(found: List<WindowListing.AppStack>, id: Int): Boolean =
            runsTwice(found, id) && (restartedAt == 0L || SystemClock.elapsedRealtime() - restartedAt > RESTART_COOLDOWN_MS)

        private suspend fun shell(cmd: String, what: String) {
            val out = runGuarded { DockShell.shell(context, cmd) }.getOrElse { "failed: ${it.message}" }
            Log.i(TAG, "$what: ${out.trim()}")
        }

        private suspend fun start(vd: TileDisplay) {
            openedAt = SystemClock.elapsedRealtime()
            startCommand?.let { cmd ->
                startCommand = null
                val ok = runGuarded { DockShell.shell(context, "$cmd --display ${vd.displayId}") }
                    .onFailure { Log.w(TAG, "$packageName refused its start command", it) }
                    .isSuccess
                _status.value = if (ok) Status.SHOWN else Status.BLOCKED
                if (ok) {
                    startedAt = SystemClock.elapsedRealtime()
                    launchedOnTile()
                }
                return
            }
            val intent = AppLauncher.launchIntent(context, packageName)
            if (intent == null) {
                _status.value = Status.MISSING
                return
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(vd.displayId)
            // Full size on the tile's display, not the floating window it may have had before.
            runCatching { HiddenApi.invoke(ActivityOptions::class.java, options, "setLaunchWindowingMode", WINDOWING_MODE_FULLSCREEN) }
                .onFailure { Log.w(TAG, "no windowing mode for $packageName", it) }
            _status.value = runCatching { context.startActivity(intent, options.toBundle()) }
                .onFailure { Log.w(TAG, "$packageName refused on display ${vd.displayId}", it) }
                .fold({ Status.SHOWN }, { Status.BLOCKED })
            if (_status.value == Status.SHOWN) {
                startedAt = SystemClock.elapsedRealtime()
                launchedOnTile()
            }
        }

        /** The app's stacks once it has shown up somewhere, looking every [POLL_MS] for a few seconds. */
        private suspend fun awaitStacks(): List<WindowListing.AppStack> {
            repeat((START_WAIT_MS / POLL_MS).toInt()) {
                delay(POLL_MS)
                val now = listStacks() ?: return emptyList()
                if (now.isNotEmpty()) return now
            }
            return emptyList()
        }

        /**
         * The stacks of the app [packageName] handed its screen to since
         * [before] ([WindowListing.handedTo]), followed from then on; empty
         * when nothing else came up within [START_WAIT_MS].
         */
        private suspend fun awaitHandOff(before: String): List<WindowListing.AppStack> {
            repeat((START_WAIT_MS / POLL_MS).toInt()) {
                val now = rawListing() ?: return emptyList()
                val other = WindowListing.handedTo(before, now, packageName, context.packageName, claimedBesides(this))
                if (other != null) return follow(other, now)
                delay(POLL_MS)
            }
            return emptyList()
        }

        /** From now on the tile shows [other]'s windows: its stacks in [listing]. */
        private fun follow(other: String, listing: String): List<WindowListing.AppStack> {
            if (other != windowPackage) Log.i(TAG, "$packageName opens its screen in $other: the tile follows $other")
            windowPackage = other
            return WindowListing.appStacks(listing, other).filterNot { it.empty }
        }

        /** A fresh `am stack list`, or null (logged) when the shell cannot list. */
        private suspend fun rawListing(): String? {
            DockShell.forgetListing()
            return runGuarded { DockShell.listStacks(context) }
                .onFailure { Log.w(TAG, "can't list the windows", it) }
                .getOrNull()
        }

        /** The app's stacks right now, those left empty aside ([allStacks]), or null when the shell cannot list them. */
        private suspend fun listStacks(): List<WindowListing.AppStack>? = allStacks()?.filterNot { it.empty }

        /** The app's stacks right now, empty ones included, or null (logged) when the shell cannot list them. */
        private suspend fun allStacks(): List<WindowListing.AppStack>? {
            DockShell.forgetListing()
            return runGuarded { WindowListing.appStacks(DockShell.listStacks(context), windowPackage) }
                .onFailure { Log.w(TAG, "can't see where $packageName is", it) }
                .getOrNull()
        }

        /**
         * Whether the app has no process left, by `pidof`. A shell that can't
         * answer counts as running. "|| true": pidof finding nothing ends with
         * an error code, which the root shell takes for a failed command.
         */
        private suspend fun processGone(): Boolean = try {
            PipAnchor.processGone(DockShell.shell(context, "pidof $windowPackage || true"), windowPackage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }

        /**
         * Sends a touch on the tile to the same spot on the app's display, from
         * [touchHandler]: in order, and never holding up the dashboard's own
         * thread (a drag sends dozens a second).
         */
        fun touch(event: MotionEvent) {
            val id = display?.displayId ?: return
            if (touchRefused) return
            val copy = MotionEvent.obtain(event)
            // Read here, on the main thread, before the dashboard hears of the keys this touch moves.
            val gains = dashboardFocusGains
            touchHandler.post {
                val action = copy.actionMasked
                val tap = if (action == MotionEvent.ACTION_DOWN) TapCost(packageName).also { tap = it } else tap
                var raiseMs: Long? = null
                var injected: Boolean? = null
                var injectMs = 0L
                val late = SystemClock.uptimeMillis() - copy.eventTime
                try {
                    if (!canInject) {
                        refuseTouches(null)
                        return@post
                    }
                    val press = notePress(copy, gains)
                    if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) raiseMs = focusTile()
                    val began = SystemClock.uptimeMillis()
                    // The dashboard took the keys back during the tap, or the raise just now did:
                    // the app dropped the press with them, and its lift would do nothing.
                    val again = action == MotionEvent.ACTION_UP && press != null &&
                        (raiseMs != null || dashboardFocusGains != press.gains) &&
                        stillATap(copy.x - press.x, copy.y - press.y, copy.eventTime - press.downTime, press.wandered)
                    injected = if (again) retap(copy, press!!, id, raised = raiseMs != null) else inject(copy, id)
                    if (again) tap?.retapped()
                    injectMs = SystemClock.uptimeMillis() - began
                } catch (t: InvocationTargetException) {
                    // Without INJECT_EVENTS every touch is refused: say it once, then leave the app as a picture.
                    // Anything else (the display resized meanwhile...) only loses this one touch.
                    if (t.targetException is SecurityException) refuseTouches(t.targetException)
                    else Log.w(TAG, "a touch missed $packageName", t.targetException)
                } catch (t: Exception) {
                    Log.w(TAG, "a touch missed $packageName", t)
                } finally {
                    copy.recycle()
                    if (tap != null && tap.add(action, raiseMs, injected, injectMs, late)) {
                        this.tap = null
                        tapDone(tap)
                    }
                }
            }
        }

        /** The tap under way on the tile, from its down to its lift, for the unit report. On [touchHandler]. */
        private var tap: TapCost? = null

        /** Where and when the finger went down on the tile, and [dashboardFocusGains] then. On [touchHandler]. */
        private class Press(val x: Float, val y: Float, val downTime: Long, val gains: Int) {
            /** Moved past the slop, or a second finger: no longer a tap. */
            var wandered = false
        }

        private var press: Press? = null

        /** Android's touch slop, 8 dp (ViewConfiguration's needs a window's context, and this one is the app's). */
        private val touchSlop: Int by lazy { (8 * context.resources.displayMetrics.density).toInt() }

        /** Keeps [press] up to date with [event]; the press it belongs to, taken off once over. On [touchHandler]. */
        private fun notePress(event: MotionEvent, gains: Int): Press? {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> press = Press(event.x, event.y, event.downTime, gains)
                MotionEvent.ACTION_POINTER_DOWN -> press?.wandered = true
                MotionEvent.ACTION_MOVE -> press?.let { if (kotlin.math.hypot(event.x - it.x, event.y - it.y) > touchSlop) it.wandered = true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return press.also { press = null }
            }
            return press
        }

        /** Whether a press that ended ([dx], [dy]) from where it began, [ms] after, was a tap: what [retap] may tell again. */
        private fun stillATap(dx: Float, dy: Float, ms: Long, wandered: Boolean): Boolean =
            isTap(dx, dy, ms, wandered, touchSlop, ViewConfiguration.getLongPressTimeout())

        /**
         * Tells the app the tap [up] ends once more, whole, now that its window
         * has the keys: the press it holds is cancelled, then a new press and
         * lift go to where the finger went down. A view drops its press when
         * its window loses the keys ([android.view.View.onWindowFocusChanged]),
         * and the lift then clicks nothing: the finger lands on the dashboard's
         * window first, which takes the keys to the main screen, and on a busy
         * unit that came after the app had the press. Buttons in a tile (a
         * track skip) and fields (Maps' search) then missed every so often,
         * mostly on a second tap. Whether Android took all three. On [touchHandler].
         */
        private fun retap(up: MotionEvent, press: Press, id: Int, raised: Boolean): Boolean {
            val cancel = MotionEvent.obtain(up).apply { action = MotionEvent.ACTION_CANCEL }
            val dropped = try { inject(cancel, id) } finally { cancel.recycle() }
            // Before Android 11 the keys reach the app apart from its touches: the raise's are let arrive first.
            if (raised && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) SystemClock.sleep(FOCUS_SETTLE_MS)
            val now = SystemClock.uptimeMillis()
            var taken = dropped
            for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, press.x, press.y, 1f, 1f, 0, 1f, 1f, up.deviceId, 0)
                event.source = up.source
                try { taken = inject(event, id) && taken } finally { event.recycle() }
            }
            return taken
        }

        /** [tap] is over: one line says what it cost (a warning when a touch was lost or a raise slow), the minute's summary when due. */
        private fun tapDone(tap: TapCost) {
            if (tap.worrying) Log.w(TAG, tap.line()) else Log.i(TAG, tap.line())
            tapStats.add(tap, SystemClock.uptimeMillis())?.let { Log.i(TAG, it) }
        }

        /** The app's task on the tile's display, as last found there; null until it is. */
        @Volatile private var tileTask: Int? = null

        /** [tileTask] while the app shows on its tile. */
        val keysTask: Int? get() = tileTask.takeIf { _status.value == Status.SHOWN }

        /**
         * Puts the tile's display in front, with the keys, before a touch reaches the
         * app. The finger lands on the dashboard's window first, which takes the keys
         * to the main screen; the touch sent on to the app then usually took them to
         * the tile's display, but on a busy unit the main screen sometimes won: a tap
         * on Waze's "Where to?" field then did nothing (a text field needs the keys
         * for its keyboard; buttons worked). Raising the app's task moves the keys
         * to its display, and it stays on the tile. Done on the down and again before
         * the lift, by when the dashboard's own move has long been made. On
         * [touchHandler]. How long the raise took, or null when none was made.
         */
        private fun focusTile(): Long? {
            val task = tileTask ?: return null
            if (_status.value != Status.SHOWN) return null
            // Only while the dashboard has the keys: when the app already has them (its keyboard
            // up), they stay where they are.
            if (!dashboardFocused) return null
            val began = SystemClock.uptimeMillis()
            runCatching { raiseFirmly(context, task, dashboardTask, 0) }.onFailure {
                // The task is gone (the app reopened): found again when it is placed.
                tileTask = null
                Log.w(TAG, "can't give the keys to $packageName on its tile", it)
            }
            return SystemClock.uptimeMillis() - began
        }

        /** Android 9's forwarder to the tile's display and that display's id, made on the first event. On [touchHandler]. */
        @Volatile private var forwarder: Pair<Int, Any>? = null

        /**
         * Sends [event] to display [id]. On [touchHandler]; throws what the call
         * threw. Whether Android took it: injectInputEvent answers false when it
         * refused the event (Android 9's forwarder says nothing, taken as yes).
         */
        private fun inject(event: InputEvent, id: Int): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val to = forwarder?.takeIf { it.first == id }?.second ?: run {
                    dropForwarder()
                    val made = createInputForwarder?.invoke(context.getSystemService(InputManager::class.java), id) ?: error("no forwarder")
                    forwarder = id to made
                    made
                }
                forwardEvent?.invoke(to, event)
                return true
            }
            setDisplayId?.invoke(event, id)
            return injectInputEvent?.invoke(context.getSystemService(InputManager::class.java), event, INJECT_ASYNC) == true
        }

        /** Lets go of Android 9's forwarder, when there is one. On [touchHandler]. */
        private fun dropForwarder() {
            val f = forwarder ?: return
            forwarder = null
            runCatching { disposeForwarder?.invoke(f.second) }
        }

        private fun refuseTouches(why: Throwable?) {
            touchRefused = true
            Log.w(TAG, "touches can't reach $packageName", why)
        }

        /**
         * [keyCode] pressed and released on the app's display, so its window
         * there gets it (Back closes what the app opened). False without a
         * display. Any thread; injected on [touchHandler] like the touches.
         */
        fun key(keyCode: Int): Boolean {
            val id = display?.displayId ?: return false
            if (!canInject) return false
            touchHandler.post {
                val now = SystemClock.uptimeMillis()
                for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    val event = KeyEvent(now, now, action, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
                    try {
                        if (!inject(event, id)) Log.w(TAG, "a key was refused for $packageName's display")
                    } catch (t: Exception) {
                        Log.w(TAG, "a key missed $packageName", (t as? InvocationTargetException)?.targetException ?: t)
                    }
                }
            }
            return true
        }
    }
}

/** Google Maps itself inside the tile, see [EmbeddedApp]. */
@Composable
internal fun EmbeddedMapsCard(modifier: Modifier = Modifier) =
    EmbeddedAppCard(EmbeddedApp.MAPS_PACKAGE, "Google Maps", modifier)

/**
 * Any app ([packageName], called [label]) running inside the tile, see
 * [EmbeddedApp]. Made afresh for another package: the picture's view hands
 * its surface to the host it was made with, so a new app in the same card
 * (another navigation app chosen under the Canvas dashboard) kept showing
 * the old one.
 */
@Composable
internal fun EmbeddedAppCard(packageName: String, label: String, modifier: Modifier = Modifier) = key(packageName) {
    val context = LocalContext.current
    val host = EmbeddedApp.host(context, packageName)
    val status by host.status.collectAsState()
    // This tile, among the app's others (Maps on another page too).
    val tile = remember { Any() }
    DisposableEffect(host, tile) {
        onDispose { host.forget(tile) }
    }
    // The app's first picture has arrived: from then on the picture covers the
    // tile, and the card colour under it would only be painted for nothing.
    var painted by remember { mutableStateOf(false) }
    // The tile's own zoom (TileZoom) sizes the app's text and buttons too.
    val dpi = (LocalDensity.current.density * 160).toInt()
    Box(
        modifier = modifier
            .then(if (painted && status == EmbeddedApp.Status.SHOWN) Modifier else Modifier.background(DashColors.Card))
            // On screen once half of it shows: the pages beside the current one
            // are kept, off screen, and the app's picture belongs on the one seen.
            .onGloballyPositioned { coords ->
                val full = coords.size.width.toFloat() * coords.size.height
                val seen = coords.boundsInWindow()
                host.onScreen(tile, full > 0f && seen.width * seen.height * 2 >= full)
            }
    ) {
        AndroidView(
            // A layer of its own: each frame the app draws re-records only this view, not the tile around it.
            modifier = Modifier.fillMaxSize().graphicsLayer(),
            factory = { ctx ->
                // A TextureView, not a SurfaceView: a SurfaceView punches a hole
                // through the dashboard's window to show its picture, and on the
                // head unit that hole took the page's background and the other
                // tiles with it. A TextureView is drawn like any other view.
                TextureView(ctx).apply {
                    var surface: Surface? = null
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                            texture.setDefaultBufferSize(width, height)
                            val s = Surface(texture).also { surface = it }
                            EmbeddedApp.tileShown(host.packageName, this@apply)
                            if (width > 0 && height > 0) host.attach(tile, s, width, height, dpi) { picturePixels(this@apply) }
                        }

                        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                            texture.setDefaultBufferSize(width, height)
                            val s = surface ?: return
                            if (width > 0 && height > 0) host.attach(tile, s, width, height, dpi) { picturePixels(this@apply) }
                        }

                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            // The display lets go of the picture before it is freed.
                            EmbeddedApp.tileGone(host.packageName, this@apply)
                            host.detach(tile)
                            surface?.release()
                            surface = null
                            painted = false
                            return true
                        }

                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                            if (!painted) painted = true
                            EmbeddedApp.tileFrames.merge(host.packageName, 1L, Long::plus)
                        }
                    }
                    setOnTouchListener { v, event ->
                        when {
                            event.actionMasked == MotionEvent.ACTION_DOWN -> {
                                // A drag on the map pans the map: the dashboard's pages must not take it.
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                                EmbeddedApp.tileTouched()
                                // The rest of the touch comes straight from the window (EmbeddedApp.routeTouch).
                                EmbeddedApp.tileDown(host, event.x, event.y)
                                host.touch(event)
                            }
                            // Sent from the window already; the cancel that lets the dashboard go is not the app's.
                            EmbeddedApp.routing -> Unit
                            else -> host.touch(event)
                        }
                        true
                    }
                }
            }
        )
        // Over the picture, only while there is none to show.
        val message = when (status) {
            EmbeddedApp.Status.STARTING -> R.string.apps_embed_app_starting
            EmbeddedApp.Status.MISSING -> R.string.apps_embed_app_missing
            EmbeddedApp.Status.BLOCKED -> R.string.apps_embed_app_blocked
            EmbeddedApp.Status.SHOWN -> null
        }
        if (message != null) {
            Text(
                stringResource(message, label),
                color = DashColors.TextSecondary,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center).padding(16.dp)
            )
        }
    }
}

/** What [view] shows, a quarter of its size each way; null when it shows nothing yet. Main thread. */
private fun picturePixels(view: TextureView): IntArray? {
    if (!view.isAvailable || view.width <= 0 || view.height <= 0) return null
    // A quarter of the size each way is plenty to tell a picture from none,
    // and a sixteenth of the memory: full size, a map filling the screen cost
    // some 7 MB on the main thread at every look (each closed dialog asks for one).
    val copy = runCatching { view.getBitmap((view.width / 4).coerceAtLeast(1), (view.height / 4).coerceAtLeast(1)) }.getOrNull() ?: return null
    val pixels = IntArray(copy.width * copy.height)
    copy.getPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
    copy.recycle()
    return pixels
}

/**
 * [runCatching] that lets a cancellation through. The shell calls suspend, so a
 * placing cancelled by the next one (a new song, the display lost) surfaced its
 * cancellation inside them; caught, the host went on to start the app on a
 * display already let go of, or marked the tile blocked for good.
 */
private inline fun <T> runGuarded(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
