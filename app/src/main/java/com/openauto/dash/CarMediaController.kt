package com.openauto.dash

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import com.openauto.dash.unit.TopwayMedia
import com.openauto.dash.unit.TopwayRadio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Snapshot of the currently active system media session. */
data class MediaState(
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val hasMedia: Boolean = false,
    /** Track length in ms, or 0 when unknown (hides the progress bar). */
    val durationMs: Long = 0L,
    /** Album art / thumbnail from the session, or null when none is published. */
    val artwork: Bitmap? = null,
    /**
     * A radio's station name while [title] is the song it plays (DAB+, web
     * radio: live, with no length); empty otherwise. Already on the second
     * line, after the artist ([withStation]).
     */
    val station: String = ""
)

/**
 * Bridges Android's [MediaSessionManager] into a Compose-friendly [StateFlow].
 *
 * Reads the active media session from other apps (title, artist, playback
 * state) and exposes transport controls. This requires Notification access,
 * which the user grants once via system settings — see
 * [hasNotificationAccess] / [openNotificationAccessSettings]. The paired
 * [MediaNotificationListenerService] is the component that access is granted to.
 */
class CarMediaController(private val context: Context) {

    private val sessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

    private val listenerComponent =
        ComponentName(context, MediaNotificationListenerService::class.java)

    private val _mediaState = MutableStateFlow(MediaState())
    val mediaState: StateFlow<MediaState> = _mediaState.asStateFlow()

    private var activeController: MediaController? = null
    private var boundControllers: List<MediaController> = emptyList()
    private var started = false

    // A play asked for while no player was running: the app we opened for it
    // ([ANY_PLAYER] when it was the system's default), and until when its new
    // session still gets told to play.
    private var pendingPlayPackage: String? = null
    private var pendingPlayUntil = 0L

    private val controllerCallback = object : MediaController.Callback() {
        // The callback is registered on every session, so re-pick the active one
        // whenever any of them changes state (e.g. YouTube Music starts playing).
        override fun onPlaybackStateChanged(state: PlaybackState?) = selectActive()
        override fun onMetadataChanged(metadata: MediaMetadata?) = selectActive()
        override fun onSessionDestroyed() = selectActive()
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers -> bind(controllers) }

    /** Which session the tile follows ([SessionPick]), and when each last played. */
    private val pick = SessionPick { SystemClock.elapsedRealtime() }

    /**
     * Starts observing active media sessions. Safe (and idempotent) to call
     * before access is granted — the UI re-invokes it once the user grants
     * Notification access so playback appears without an app restart.
     */
    fun start() {
        if (started) return
        // The head unit's own players (Bluetooth music, radio) tell what they play by broadcast.
        HeadUnitMedia.start(context)
        HeadUnitMedia.addListener(stockChanged)
        PlayerNotifications.addListener(playerNotified)
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, listenerComponent)
            bind(sessionManager.getActiveSessions(listenerComponent))
            started = true
            main.postDelayed(rescanTick, RESCAN_MS)
        } catch (e: SecurityException) {
            // Notification access not granted yet; UI prompts the user to enable it.
            _mediaState.value = MediaState()
        }
    }

    private var users = 0

    /**
     * One more user of the [shared] controller (the dashboard, the second
     * screen): observes sessions while any is there.
     */
    @Synchronized
    fun acquire() {
        users++
        start()
    }

    @Synchronized
    fun release() {
        if (users > 0) users--
        if (users == 0) stop()
    }

    /** Stops observing and releases callbacks. */
    fun stop() {
        HeadUnitMedia.removeListener(stockChanged)
        PlayerNotifications.removeListener(playerNotified)
        main.removeCallbacks(stockTick)
        main.removeCallbacks(rescanTick)
        main.removeCallbacks(emptyRecheck)
        emptySince = 0L
        // Or the next start finds "already shown" and never ticks again: the radio's tile then stood still.
        stockShown = null
        runCatching { sessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener) }
        boundControllers.forEach { runCatching { it.unregisterCallback(controllerCallback) } }
        boundControllers = emptyList()
        activeController = null
        started = false
    }

    private fun bind(controllers: List<MediaController>?) {
        val listed = withNotifiedSessions(controllers.orEmpty())
        boundControllers.forEach { runCatching { it.unregisterCallback(controllerCallback) } }
        boundControllers = listed
        // Observe every session so we follow whichever one is actually playing.
        boundControllers.forEach { runCatching { it.registerCallback(controllerCallback) } }
        selectActive()
    }

    /**
     * [listed] plus the sessions players' notifications name that the system
     * didn't list. On some units (Topway, Android 13) YouTube Music's session
     * only showed up once another player changed: the tile missed it until then.
     */
    private fun withNotifiedSessions(listed: List<MediaController>): List<MediaController> {
        val known = listed.mapTo(HashSet()) { it.sessionToken }
        val extra = PlayerNotifications.sessionTokens().values
            .filter { it !in known }
            .mapNotNull { token -> runCatching { MediaController(context, token) }.getOrNull() }
            // A session already released still has its token in an old notification.
            .filter { runCatching { it.packageName }.getOrNull() != null }
        val extraPackages = extra.map { it.packageName }
        if (extraPackages != lastExtraLogged) {
            lastExtraLogged = extraPackages
            if (extraPackages.isNotEmpty()) DebugLog.note(context, "media: sessions only a notification named: ${extraPackages.joinToString()}")
        }
        return listed + extra
    }

    private var lastExtraLogged: List<String?> = emptyList()

    /**
     * Lists the sessions again, and binds them when the set changed: the
     * system's "sessions changed" call isn't always made on every unit.
     */
    private fun rescan() {
        if (!started) return
        val listed = runCatching { sessionManager.getActiveSessions(listenerComponent) }.getOrNull() ?: return
        val now = withNotifiedSessions(listed)
        if (now.map { it.sessionToken } != boundControllers.map { it.sessionToken }) bind(listed) else selectActive()
    }

    private val rescanTick = object : Runnable {
        override fun run() {
            rescan()
            if (started) main.postDelayed(this, RESCAN_MS)
        }
    }

    /** Picks the session to follow ([SessionPick]): playing first, then paused, never one stale. */
    private fun selectActive() {
        val views = boundControllers.map { it.view() }
        pick.observe(views)
        val chosen = pick.pick(views, activeController?.sessionToken, rememberedPackage ?: getLastMediaPackage(context))
        val next = chosen?.let { v -> boundControllers.firstOrNull { it.sessionToken == v.key } }
        if (next?.sessionToken != activeController?.sessionToken) {
            DebugLog.note(context, "media pick: ${next?.packageName ?: "none"} of " +
                views.joinToString { "${it.pkg} ${it.mood}${if (it.hasMetadata) "" else " (empty)"}" })
        }
        activeController = next
        playIfPending()
        publish(activeController)
    }

    private fun MediaController.view(): SessionView {
        val pkg = runCatching { packageName }.getOrNull()
        val metadata = runCatching { metadata }.getOrNull()
        val named = !isPlaceholderTitle(metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)) ||
            !isPlaceholderTitle(metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)) ||
            PlayerNotifications.of(pkg) != null
        return SessionView(
            key = sessionToken,
            pkg = pkg,
            mood = SessionMood.of(runCatching { playbackState?.state }.getOrNull()),
            hasMetadata = named,
            stock = HeadUnitMedia.isStock(pkg),
            track = (metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
                ?.takeUnless(::isPlaceholderTitle)
        )
    }

    /**
     * The player opened by [playPause] has published its session: tell it to
     * play, once. Most players open paused on their last track; some already
     * started from the media button, and are left alone.
     */
    private fun playIfPending() {
        val wanted = pendingPlayPackage ?: return
        if (SystemClock.elapsedRealtime() > pendingPlayUntil) {
            pendingPlayPackage = null
            return
        }
        val session = boundControllers.firstOrNull { wanted == ANY_PLAYER || it.packageName == wanted } ?: return
        pendingPlayPackage = null
        if (session.playbackState?.state != PlaybackState.STATE_PLAYING) {
            runCatching { session.transportControls.play() }
        }
        activeController = session
    }

    /**
     * Every callback of every session lands here (position ticks, buffering,
     * other players), mostly with nothing new for the tiles. The state only
     * changes when a field does, so the tiles don't redraw for nothing.
     */
    private fun publish(controller: MediaController?) {
        if (publishStock(controller)) return
        if (controller == null) {
            showMedia(MediaState())
            return
        }
        // Remember which app owns this session so the split-screen cockpit can
        // reopen the last-used media app (written only when it changes).
        controller.packageName?.let { pkg ->
            if (pkg != rememberedPackage) {
                rememberedPackage = pkg
                rememberLastMediaPackage(context, pkg)
            }
        }
        val metadata = controller.metadata
        val playback = controller.playbackState
        val playing = playback?.state == PlaybackState.STATE_PLAYING
        // What to play again at the next start, if the car is switched off now (MediaResume).
        MediaResume.note(controller.packageName, playing)
        // A new song by an app in a tile: its player comes up there.
        TilePlayer.noted(controller)
        val sessionTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        // A factory music player's session can play with no title in it: what it says elsewhere.
        val untitled = if (sessionTitle.isNotBlank()) null else untitledFromPlayer(controller.packageName, metadata, playing)
        val title = untitled?.first ?: sessionTitle
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty().ifBlank { untitled?.second.orEmpty() }
        val track = listOf(controller.packageName, title, artist, metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM))
        val durationMs = (metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        // Only a session's own words name a radio station: a player's notification or name is no station.
        val station = controller.packageName?.takeIf { untitled == null }?.let { stations.note(it, title, artist, album, live = durationMs == 0L) }.orEmpty()
        // What a live stream says, in the unit's log: radios name their station in many ways (RadioStations).
        // A radio's song with a length too (DAB-Z gives some one), with that length.
        if ((durationMs == 0L || station.isNotEmpty()) && metadata != null && untitled == null) {
            val said = listOf(controller.packageName, title, artist, album, metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE), station) +
                listOfNotNull(if (durationMs > 0L) "${durationMs / 1000} s long" else null)
            if (said != lastLiveLogged) {
                lastLiveLogged = said
                DebugLog.note(context, "media live: ${said.joinToString(" | ")}")
            }
        }
        showMedia(MediaState(
            title = MediaTitle.clean(title, artist),
            artist = artist,
            isPlaying = playing,
            hasMedia = metadata != null || title.isNotBlank(),
            durationMs = durationMs,
            // The unit's Bluetooth app names the track but has no cover: the phone's, where it sent one.
            artwork = artworkFor(track, metadata?.artwork())
                ?: HeadUnitMedia.phone?.art?.takeIf { controller.packageName == HeadUnitMedia.BT_PACKAGE },
            station = station
        ))
    }

    // --- A session that names no title ------------------------------------
    //
    // Some head units' own music players play with an empty session, and the
    // tile said "Nothing playing" over the music. Their notification or, at
    // least, their name is shown instead, and the unit log says which player
    // it was and what it gave, once each, so it can be read properly later.

    private val playerNotified: (String) -> Unit = { pkg ->
        when {
            pkg == activeController?.packageName -> selectActive()
            // A player we hold no session of put up its notification: its session may be new.
            boundControllers.none { it.packageName == pkg } && pkg in PlayerNotifications.sessionTokens() -> rescan()
        }
    }
    private val appNames = HashMap<String, String?>()
    private var lastUntitledLogged: List<String?>? = null

    private fun untitledFromPlayer(pkg: String?, metadata: MediaMetadata?, playing: Boolean): Pair<String, String>? {
        val notification = PlayerNotifications.of(pkg)
        val appName = pkg?.let { p ->
            appNames.getOrPut(p) {
                runCatching { context.packageManager.run { getApplicationLabel(getApplicationInfo(p, 0)).toString() } }.getOrNull()
            }
        }
        val displayTitle = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val track = untitledTrack(displayTitle, metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE), notification, appName, playing)
        val said = listOf(pkg, if (metadata == null) "no metadata" else "metadata ${metadata.keySet().sorted()}",
            "notification ${notification?.let { "${it.title} / ${it.text}" } ?: "none"}", track?.first)
        if (playing && said != lastUntitledLogged) {
            lastUntitledLogged = said
            DebugLog.note(context, "media untitled: ${said.joinToString(" | ")}")
        }
        return track
    }

    // --- A radio's station under its song -------------------------------
    //
    // A DAB+ or web radio app names the station first, then the song once the
    // station sends it, and the station's name was gone from the tile. It
    // stays on the second line, after the artist: the song above, both below.

    private val stations = RadioStations()
    private var lastLiveLogged: List<String?>? = null

    /**
     * Between two songs a player can drop its metadata for a moment (YouTube
     * Music does, when it skips): the tile vanished and came back. Going from
     * a song to nothing waits [EMPTY_GRACE_MS] first; anything with media
     * shows at once.
     */
    private var emptySince = 0L
    private val emptyRecheck = Runnable { selectActive() }

    private fun showMedia(state: MediaState) {
        if (!state.hasMedia && _mediaState.value.hasMedia) {
            val now = SystemClock.elapsedRealtime()
            if (emptySince == 0L) {
                emptySince = now
                main.postDelayed(emptyRecheck, EMPTY_GRACE_MS)
                return
            }
            if (now - emptySince < EMPTY_GRACE_MS) return
        }
        if (emptySince != 0L) {
            emptySince = 0L
            main.removeCallbacks(emptyRecheck)
        }
        _mediaState.value = withStation(state)
    }

    // --- The head unit's own players --------------------------------------
    //
    // Bluetooth music and the radio publish an empty session: what they play
    // comes from [HeadUnitMedia]. Shown when their session is the active one
    // (empty), or when there's no session at all and the head unit says it's
    // their audio. Their play state lives in system properties nothing
    // announces, so it's read again every second while one is shown.

    private val main = Handler(Looper.getMainLooper())
    /** The head unit player shown in the tile, or null: its buttons go to it. */
    private var stockShown: String? = null
    private val stockChanged: () -> Unit = { main.post { selectActive() } }
    private val stockTick = object : Runnable {
        override fun run() {
            selectActive()
            if (stockShown != null) main.postDelayed(this, STOCK_TICK_MS)
        }
    }

    private fun publishStock(controller: MediaController?): Boolean {
        val pkg = when {
            // A Topway unit's radio has no session: it shows while it is the unit's source and no app plays.
            // Its Bluetooth app's sessions say they play for hours: with the radio the source, they don't.
            TopwayRadio.isSource.value && (controller?.playbackState?.state != PlaybackState.STATE_PLAYING ||
                TopwayMedia.steers(controller?.packageName)) -> HeadUnitMedia.TOPWAY_RADIO
            controller != null -> controller.packageName?.takeIf {
                HeadUnitMedia.isStock(it) && controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()
            }
            else -> HeadUnitMedia.source()?.takeIf(HeadUnitMedia::isStock)
        }
        val state = pkg?.let(HeadUnitMedia::state)
        val was = stockShown
        stockShown = if (state != null) pkg else null
        if (stockShown != null && was == null) main.postDelayed(stockTick, STOCK_TICK_MS)
        if (stockShown == null) {
            if (was != null) main.removeCallbacks(stockTick)
            return false
        }
        emptySince = 0L
        main.removeCallbacks(emptyRecheck)
        _mediaState.value = state!!
        return true
    }

    /** The package whose name is saved as the last media app, so it's written only when it changes. */
    private var rememberedPackage: String? = null
    private var artTrack: List<String?>? = null
    private var art: Bitmap? = null
    private var artSignature = 0L

    /**
     * Each read of the session's metadata brings its cover as a new bitmap;
     * the one already shown is kept for as long as the same track plays, so
     * a callback that changed nothing leaves the state equal. A cover that
     * shows up late or changes (a placeholder replaced by the real one, a
     * radio show under a fixed title) is taken.
     *
     * Same-or-not is decided from a [signature] of sampled pixels, not a
     * pixel-by-pixel compare: players report a state change every few
     * seconds, and comparing a full-size cover each time ran on the main
     * thread. A cover far bigger than any tile is shrunk once.
     */
    private fun artworkFor(track: List<String?>, fresh: Bitmap?): Bitmap? {
        val kept = art
        val signature = fresh?.signature() ?: 0L
        if (fresh != null && kept != null && track == artTrack && signature == artSignature && !kept.isRecycled) return kept
        val shown = fresh?.let { if (it.width > ART_MAX_PX || it.height > ART_MAX_PX) it.shrunkTo(ART_MAX_PX) else it }
        artTrack = track
        artSignature = signature
        art = shown
        return shown
    }

    /** Size and 64 sampled pixels; enough to tell one cover from another, cheap enough for every callback. */
    private fun Bitmap.signature(): Long = runCatching {
        var h = width * 31L + height
        for (j in 0 until 8) for (i in 0 until 8) {
            h = h * 31 + getPixel(width * (2 * i + 1) / 16, height * (2 * j + 1) / 16)
        }
        h
    }.getOrElse { generationId.toLong() } // a hardware bitmap can't be read: every one counts as new

    private fun Bitmap.shrunkTo(maxPx: Int): Bitmap = runCatching {
        val scale = maxPx.toFloat() / maxOf(width, height)
        Bitmap.createScaledBitmap(this, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), true)
    }.getOrDefault(this)

    /** First available artwork bitmap from the session metadata, if any. */
    private fun MediaMetadata.artwork(): Bitmap? =
        getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

    /**
     * Current playback position in ms, extrapolated from the last reported
     * position so a progress bar advances smoothly while playing.
     */
    fun positionMs(): Long {
        if (DemoMode.isOn) return DemoMode.positionMs()
        stockShown?.let { return HeadUnitMedia.positionMs(it) ?: 0L }
        val state = activeController?.playbackState ?: return 0L
        val base = state.position
        return if (state.state == PlaybackState.STATE_PLAYING) {
            val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            (base + (elapsed * state.playbackSpeed).toLong()).coerceAtLeast(0L)
        } else {
            base.coerceAtLeast(0L)
        }
    }

    /**
     * Play / pause on the current session. With no session at all (nothing
     * has played since the unit started, or the player was closed), opens the
     * last media app seen playing, the system's default player when none was,
     * and starts it: a PLAY media button right away for players that resume
     * from it, then play() on the session it publishes ([playIfPending]).
     */
    fun playPause() {
        if (DemoMode.isOn) return DemoMode.playPause()
        stockShown?.let { return HeadUnitMedia.playPause(context, it) }
        if (topway(if (_mediaState.value.isPlaying) TopwayMedia.Command.PAUSE else TopwayMedia.Command.PLAY)) return
        val controls = activeController?.transportControls ?: return startLastPlayer()
        if (_mediaState.value.isPlaying) controls.pause() else controls.play()
    }

    /**
     * A Topway unit's Bluetooth app ignores its session's commands: they go through the
     * firmware's command service instead ([TopwayMedia]). False when that's not the player shown.
     */
    private fun topway(command: TopwayMedia.Command): Boolean =
        TopwayMedia.steers(activeController?.packageName) && TopwayMedia.send(context, command)

    /**
     * Whether there is a player to steer directly: a session, or the head
     * unit's own player shown. Without one (no notification access, nothing
     * played yet) the wheel falls back to a media key.
     */
    val steering: Boolean get() = DemoMode.isOn || stockShown != null || activeController != null

    /** Plays whatever is current, and does nothing when it already plays: a wheel's "unmute" never pauses (#148). */
    fun play() = playOrPause(play = true)

    /** Pauses whatever is current, and does nothing when it is already paused: "mute" never starts the music (#148). */
    fun pause() = playOrPause(play = false)

    private fun playOrPause(play: Boolean) {
        if (DemoMode.isOn) {
            if (_mediaState.value.isPlaying != play) DemoMode.playPause()
            return
        }
        stockShown?.let {
            if (_mediaState.value.isPlaying != play) HeadUnitMedia.playPause(context, it)
            return
        }
        if (topway(if (play) TopwayMedia.Command.PLAY else TopwayMedia.Command.PAUSE)) return
        val controls = activeController?.transportControls ?: return
        runCatching { if (play) controls.play() else controls.pause() }
    }

    /**
     * Brings the player in front: the head unit's own one while it is the one
     * shown, else the app of the session playing, else the last one seen
     * playing, else the system's music app (issue #119: a tap on the media
     * tile). False when none opened.
     */
    fun openPlayer(): Boolean {
        if (DemoMode.isOn) return false
        val pkg = (stockShown ?: activeController?.packageName ?: getLastMediaPackage(context))?.takeIf { it != context.packageName }
        if (pkg != null && AppLauncher.launch(context, pkg)) return true
        return context.launchSafely(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC))
    }

    private fun startLastPlayer() {
        val last = getLastMediaPackage(context)?.takeIf { context.packageManager.getLaunchIntentForPackage(it) != null }
        val launch = last?.let { context.packageManager.getLaunchIntentForPackage(it) }
            ?: Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
        last?.let(UnitPhoneScreen::opened)
        if (!context.launchSafely(launch)) return
        pendingPlayPackage = last ?: ANY_PLAYER
        pendingPlayUntil = SystemClock.elapsedRealtime() + PENDING_PLAY_MS
        if (last != null) sendPlayButton(last)
        // Its session may already be listed (a player kept in the background).
        playIfPending()
    }

    /** A PLAY key press sent to [packageName]'s media button receiver. */
    private fun sendPlayButton(packageName: String) {
        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setPackage(packageName)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY))
            runCatching { context.sendBroadcast(intent) }
        }
    }

    fun next() {
        if (DemoMode.isOn) return DemoMode.next()
        stockShown?.let { return HeadUnitMedia.next(context, it, activeController) }
        if (topway(TopwayMedia.Command.NEXT)) return
        activeController?.transportControls?.skipToNext()
    }

    fun previous() {
        if (DemoMode.isOn) return DemoMode.previous()
        stockShown?.let { return HeadUnitMedia.previous(context, it, activeController) }
        if (topway(TopwayMedia.Command.PREVIOUS)) return
        activeController?.transportControls?.skipToPrevious()
    }

    companion object {
        @Volatile private var sharedInstance: CarMediaController? = null

        /**
         * The controller the dashboard and the second screen share, on the
         * application context: one session listener, one idea of what plays.
         */
        fun shared(context: Context): CarMediaController =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: CarMediaController(context.applicationContext).also { sharedInstance = it }
            }

        /** How often the head unit player's play state and position are read again while it's shown. */
        private const val STOCK_TICK_MS = 1_000L

        /** How often the sessions are listed again, in case the system didn't say they changed. */
        private const val RESCAN_MS = 5_000L

        /** How long a song shown stays while its player says nothing for a moment ([showMedia]). */
        private const val EMPTY_GRACE_MS = 2_500L

        /** How long a player opened by [playPause] has to publish its session. */
        private const val PENDING_PLAY_MS = 15_000L
        /** Covers larger than this on a side are shrunk: no tile shows more, and a 1024² cover is 4 MB to upload. */
        private const val ART_MAX_PX = 512
        /** [pendingPlayPackage] when the system's default player was opened: the first session plays. */
        private const val ANY_PLAYER = "*"
        private const val PREFS = "media_prefs"
        private const val KEY_LAST_MEDIA_PACKAGE = "last_media_package"

        /** Persists the package of the most recently active media app. */
        private fun rememberLastMediaPackage(context: Context, packageName: String) {
            if (packageName == context.packageName) return
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_MEDIA_PACKAGE, packageName)
                .apply()
        }

        /** The last media app seen playing, or null if none has been observed. */
        fun getLastMediaPackage(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_MEDIA_PACKAGE, null)

        /** True once the user has granted Notification access to this app. */
        fun hasNotificationAccess(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            // Each entry is a flattened ComponentName ("pkg/cls"); match the
            // package exactly rather than as a loose substring.
            return enabled.split(":").any { entry ->
                entry.substringBefore("/") == context.packageName
            }
        }

        /**
         * Opens the system screen where the user enables Notification access.
         * The Play edition first says what the access is for ([AccessDisclosure]).
         */
        fun openNotificationAccessSettings(context: Context) {
            if (Edition.play) {
                AccessDisclosure.show(context, AccessDisclosure.Kind.NOTIFICATIONS)
                return
            }
            AccessDisclosure.openSystemPage(context, AccessDisclosure.Kind.NOTIFICATIONS)
        }
    }
}

/**
 * The artist line of a tile that gives the station its own line above the song: without the
 * station again. On a radio's own text the artist field is the station, and the line stays empty.
 */
internal fun artistBesideStation(state: MediaState): String =
    state.artist.removeSuffix(" · " + state.station).takeUnless { it == state.station }.orEmpty()

/** [state] with its radio station after the artist on the second line ("Toto · Radio Swiss Pop"), once. */
internal fun withStation(state: MediaState): MediaState {
    val station = state.station
    if (station.isEmpty() || state.artist.contains(station, ignoreCase = true)) return state
    return state.copy(artist = listOf(state.artist, station).filter { it.isNotBlank() }.joinToString(" · "))
}
