package com.openauto.dash

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.session.MediaController
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import com.openauto.dash.link.NowPlaying
import com.openauto.dash.unit.TopwayRadio
import com.openauto.unitproto.topway.TopwayProtocol
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArraySet

/*
 * The head unit's own players on the QF firmware (ROCO K706), which publish
 * a media session with nothing in it: Bluetooth music (com.qf.bluetooth) and
 * the FM/AM radio (com.android.fmradio.ext). What they play comes from their
 * own broadcasts instead, and their buttons from their own control actions
 * (worked out from the firmware). [CarMediaController] shows it in the media
 * tile whenever one of them is the source.
 *
 * The radio only talks while its screen exists: after it was closed, next /
 * previous / play open it again.
 */
object HeadUnitMedia {
    private const val TAG = "HeadUnitMedia"
    const val BT_PACKAGE = "com.qf.bluetooth"
    const val RADIO_PACKAGE = "com.android.fmradio.ext"
    private const val RADIO_ACTIVITY = "com.android.fmradio.FmMainActivity"

    private const val ACTION_BT_INFO = "com.qf.action.BT.MUSIC.INFO"
    private const val ACTION_BT_CONTROL = "com.qf.action.BT.MUSIC.CONTROL"
    private const val BT_PREVIOUS = 1
    private const val BT_NEXT = 2
    private const val BT_TOGGLE = 3

    private const val ACTION_RADIO = "com.qf.radio.update_action"
    private const val RADIO_NEXT = "/customize/radio/next"
    private const val RADIO_PREVIOUS = "/customize/radio/pre"
    private const val RADIO_CLOSE = "/customize/radio/close"

    data class BtTrack(val title: String, val artist: String, val album: String)

    /** [freq] in 10 kHz on FM (8750 = 87.50 MHz), kHz on AM; [band] 0..2 = FM1..3, 3..5 = AM1..3. */
    data class RadioStation(val freq: Int, val band: Int, val name: String?)

    @Volatile var bt: BtTrack? = null
        private set
    @Volatile var radio: RadioStation? = null
        private set

    // The station's RDS as the MCU sends it ([McuFeed]), whether or not the radio's screen is open.
    @Volatile private var rdsName: String? = null
    @Volatile private var rdsText: String? = null

    /** The tuner's band and frequency from the MCU: a new station forgets the last one's RDS. */
    internal fun mcuTuner(freq: Int, band: Int) {
        if (freq <= 0) return
        val was = radio
        if (was != null && was.freq == freq && was.band == band) return
        rdsName = null
        rdsText = null
        radio = RadioStation(freq, band, was?.takeIf { it.freq == freq }?.name)
        main.post { tell() }
    }

    /** RDS from the MCU: the station's name, its radio text, or [clear] when the station has none. */
    internal fun mcuRds(name: String?, text: String?, clear: Boolean = false) {
        when {
            clear -> if (rdsName == null && rdsText == null) return else { rdsName = null; rdsText = null }
            name != null -> if (name == rdsName) return else rdsName = name
            text != null -> if (text == rdsText) return else rdsText = text
            else -> return
        }
        main.post { tell() }
    }

    /** What the linked phone says it plays ([NowPlaying]): the cover the unit's Bluetooth doesn't pass on. */
    data class PhoneTrack(val title: String, val artist: String, val album: String, val playing: Boolean, val durationMs: Long, val artKey: Int, val art: Bitmap?)

    @Volatile var phone: PhoneTrack? = null
        private set
    private val main = Handler(Looper.getMainLooper())

    /** The phone's latest [NowPlaying]; a cover sent earlier is kept while its key stays the same. */
    fun fromPhone(message: NowPlaying) {
        val before = phone
        val art = when {
            message.artJpeg != null -> runCatching {
                val bytes = android.util.Base64.decode(message.artJpeg, android.util.Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
            message.artKey != 0 && message.artKey == before?.artKey -> before.art
            else -> null
        }
        phone = message.title.takeIf { it.isNotBlank() }?.let {
            PhoneTrack(it, message.artist, message.album, message.playing, message.durationMs, message.artKey, art)
        }
        main.post { tell() }
    }

    /** The phone link is gone: so is what it said. */
    fun phoneGone() {
        if (phone == null) return
        phone = null
        main.post { tell() }
    }

    private fun tell() = listeners.forEach { runCatching { it() }.onFailure { e -> Log.w(TAG, "listener failed", e) } }

    private var started = false
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun start(context: Context) {
        if (started) return
        val app = context.applicationContext
        // A Topway unit's radio: its station from the MCU (TopwayRadio), told on like the QF radio's broadcast.
        if (TopwayRadio.present(app)) {
            started = true
            TopwayRadio.addListener { main.post { tell() } }
            TopwayRadio.start(app)
            return
        }
        if (!UnitPlatform.hasPackage(app, BT_PACKAGE) && !UnitPlatform.hasPackage(app, RADIO_PACKAGE)) return
        started = true
        val filter = IntentFilter().apply {
            addAction(ACTION_BT_INFO)
            addAction(ACTION_RADIO)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    /** Told (main thread) whenever a track or station changes. */
    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    fun isStock(pkg: String?): Boolean = pkg == BT_PACKAGE || pkg?.startsWith("com.android.fmradio") == true || pkg == TOPWAY_RADIO

    /** Topway's radio, which has no media session: shown from [TopwayRadio] while it is the unit's source. */
    const val TOPWAY_RADIO = TopwayProtocol.RADIO_PACKAGE

    /** The head unit's current audio source (a package name), as its framework records it. */
    fun source(): String? = systemProperty("persist.sys.qf.last_audio_src")?.takeIf { it.isNotBlank() }
        ?: TOPWAY_RADIO.takeIf { TopwayRadio.isSource.value }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_BT_INFO -> bt = BtTrack(
                    intent.getStringExtra("songName").orEmpty().trim(),
                    intent.getStringExtra("songSinger").orEmpty().trim(),
                    intent.getStringExtra("songAlbum").orEmpty().trim()
                )
                ACTION_RADIO -> {
                    val freq = intent.getIntExtra("${ACTION_RADIO}_freq_key", -1)
                    if (freq <= 0) return
                    if (radio?.freq != freq) { rdsName = null; rdsText = null }
                    radio = RadioStation(
                        freq = freq,
                        band = intent.getIntExtra("${ACTION_RADIO}_band_key", 0),
                        name = intent.getStringExtra("${ACTION_RADIO}_name_key")?.trim()?.takeIf { it.isNotEmpty() }
                    )
                }
                else -> return
            }
            tell()
        }
    }

    /** The unit's own track, unless its Bluetooth only said it had none ("Not Provided"). */
    private fun btTrack(): BtTrack? = bt?.takeIf { it.title.isNotEmpty() && !it.title.equals(NOT_PROVIDED, ignoreCase = true) }

    private const val NOT_PROVIDED = "Not Provided"

    /** What [pkg] (one of the head unit's players) is playing, for the media tile; null when it said nothing yet. */
    fun state(pkg: String): MediaState? = when {
        // Bluetooth music is the phone's: its title where the unit has none, and its cover always.
        pkg == BT_PACKAGE -> {
            val t = btTrack()
            val p = phone
            if (t == null && p == null) null else MediaState(
                title = t?.title ?: p!!.title,
                artist = (if (t != null) listOf(t.artist, t.album) else listOf(p!!.artist, p.album)).filter { it.isNotEmpty() }.joinToString(" · "),
                isPlaying = systemProperty("sys.qf.bt.music.state") == "1" || p?.playing == true,
                hasMedia = true,
                durationMs = btProgress()?.second?.toLong()?.takeIf { it > 0 } ?: p?.durationMs ?: 0L,
                artwork = p?.art
            )
        }
        pkg == TOPWAY_RADIO -> TopwayRadio.station.value?.let { s ->
            val r = RadioStation(s.freq, TopwayProtocol.dashBand(s.band), s.name)
            val frequency = radioLabel(r)
            MediaState(
                title = s.name ?: frequency,
                artist = s.text ?: listOfNotNull(s.name?.let { frequency }, bandLabel(r.band)).joinToString(" · "),
                isPlaying = TopwayRadio.isSource.value,
                hasMedia = true
            )
        }
        isStock(pkg) -> radio?.let { r ->
            val frequency = radioLabel(r)
            val name = r.name ?: rdsName
            MediaState(
                title = name ?: frequency,
                artist = rdsText ?: listOfNotNull(name?.let { frequency }, bandLabel(r.band)).joinToString(" · "),
                isPlaying = systemProperty("sys.qf.radio.status") == "true",
                hasMedia = true
            )
        }
        else -> null
    }

    /** Bluetooth music's position in ms, when it reports one. */
    fun positionMs(pkg: String): Long? = if (pkg == BT_PACKAGE) btProgress()?.first?.toLong() else null

    fun playPause(context: Context, pkg: String) {
        if (pkg == BT_PACKAGE) return btCommand(context, BT_TOGGLE)
        if (pkg == TOPWAY_RADIO) return TopwayRadio.playPause(context)
        // The radio has no pause: closing its screen stops it, opening it plays.
        if (radioAlive()) context.sendBroadcast(Intent(RADIO_CLOSE)) else openRadio(context)
    }

    /** [session]: the radio's own media session when one is listed, for [stepRadio]'s media key. */
    fun next(context: Context, pkg: String, session: MediaController? = null) {
        if (pkg == BT_PACKAGE) return btCommand(context, BT_NEXT)
        if (pkg == TOPWAY_RADIO) return TopwayRadio.next(context)
        if (radioAlive()) stepRadio(context, RADIO_NEXT, KeyEvent.KEYCODE_MEDIA_NEXT, session) else openRadio(context)
    }

    fun previous(context: Context, pkg: String, session: MediaController? = null) {
        if (pkg == BT_PACKAGE) return btCommand(context, BT_PREVIOUS)
        if (pkg == TOPWAY_RADIO) return TopwayRadio.previous(context)
        if (radioAlive()) stepRadio(context, RADIO_PREVIOUS, KeyEvent.KEYCODE_MEDIA_PREVIOUS, session) else openRadio(context)
    }

    // --- A radio that ignores its step action ------------------------------
    //
    // A tile stood on 99.9 MHz while the wheel's buttons changed the station:
    // that radio ignored the step action. When the tuner reports no other
    // station soon after, the step goes again as a media key to the radio
    // alone, and its steps go as keys from then on. Keys unheard as well: back
    // to the action. Only the tuner's frequency counts, never its RDS text,
    // which scrolls by itself on many stations.

    /** The radio ignored its step action and took a media key, for as long as the app runs. */
    @Volatile private var radioStepsByKey = false
    private val radioStepCheck = Any()
    /** Keys for the steps sent since the watched one started, sent again if none was heard. */
    private val unheardSteps = ArrayList<Int>()
    private var stepsFrom: RadioStation? = null

    private fun stepRadio(context: Context, action: String, keyCode: Int, session: MediaController?) {
        val byKey = radioStepsByKey
        if (byKey) radioKey(context, keyCode, session) else context.sendBroadcast(Intent(action))
        // Taps in a row are watched as one: from the station before the first, every step counted.
        if (unheardSteps.isEmpty()) stepsFrom = radio
        unheardSteps += keyCode
        main.removeCallbacksAndMessages(radioStepCheck)
        main.postDelayed({
            val steps = unheardSteps.toList()
            unheardSteps.clear()
            // Something else since (demo on, the radio closed): nothing to send again.
            if (stepHeard(stepsFrom, radio) || DemoMode.isOn || !radioAlive()) return@postDelayed
            if (byKey) {
                radioStepsByKey = false
                DebugLog.note(context, "radio step: the media key went unheard too, back to the step action")
            } else {
                radioStepsByKey = true
                DebugLog.note(context, "radio step: the step action went unheard, sent as a media key")
                steps.forEach { radioKey(context, it, session) }
            }
        }, radioStepCheck, RADIO_STEP_ANSWER_MS)
    }

    /** A media key for the radio only: to its session when listed, else to its media button receiver. */
    private fun radioKey(context: Context, keyCode: Int, session: MediaController?) {
        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
            val event = KeyEvent(action, keyCode)
            val sent = session?.takeIf { isStock(it.packageName) && it.packageName != BT_PACKAGE }
                ?.let { runCatching { it.dispatchMediaButtonEvent(event) }.getOrDefault(false) } == true
            if (!sent) runCatching {
                context.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(RADIO_PACKAGE).putExtra(Intent.EXTRA_KEY_EVENT, event))
            }
        }
    }

    /** How long the tuner has to report another station after a step; a seek takes a moment. */
    private const val RADIO_STEP_ANSWER_MS = 4_000L

    private fun btCommand(context: Context, command: Int) {
        context.sendBroadcast(Intent(ACTION_BT_CONTROL).putExtra("command", command))
    }

    private fun radioAlive(): Boolean = systemProperty("sys.qf.radio.status") == "true"

    private fun openRadio(context: Context) {
        context.launchSafely(Intent().setComponent(ComponentName(RADIO_PACKAGE, RADIO_ACTIVITY)))
    }

    /** "position,duration" as the Bluetooth app records it. */
    private fun btProgress(): Pair<Int, Int>? = systemProperty("sys.qf.bt.music.progress")
        ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
        ?.takeIf { it.size == 2 && it[1] > 0 }
        ?.let { it[0] to it[1] }

    private fun systemProperty(name: String): String? = SysProps.get(name)
}

/** "98.5 MHz" / "1008 kHz". */
internal fun radioLabel(r: HeadUnitMedia.RadioStation): String =
    if (r.band in 0..2) "${(r.freq / 100.0).toBigDecimal().stripTrailingZeros().toPlainString()} MHz" else "${r.freq} kHz"

/** "FM1".."FM3", "AM1".."AM3". */
internal fun bandLabel(band: Int): String? = when (band) {
    in 0..2 -> "FM${band + 1}"
    in 3..5 -> "AM${band - 2}"
    else -> null
}

/** Whether a step reached the tuner: another frequency or band than before it. */
internal fun stepHeard(before: HeadUnitMedia.RadioStation?, now: HeadUnitMedia.RadioStation?): Boolean =
    before?.freq != now?.freq || before?.band != now?.band
