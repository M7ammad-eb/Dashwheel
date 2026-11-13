package com.openauto.dash.unit

import android.content.Context
import android.content.Intent
import android.os.Message
import com.openauto.dash.DebugLog
import com.openauto.dash.UnitPlatform
import com.openauto.unitproto.topway.TopwayProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A Topway unit's radio (com.tw.radio), which publishes no media session: the
 * station comes from the MCU through the framework's TWUtil, on the channels the
 * unit's own media bar reads ([TopwayProtocol.CH_TUNER], [TopwayProtocol.CH_PRESET]),
 * and whether it is the unit's audio source from [TopwayProtocol.CH_SOURCE]. Its
 * buttons go through the firmware's command service ([TopwayMedia]).
 *
 * Read-only towards the MCU but for two writes the radio app itself makes: asking
 * for the tuner's state again when Dashwheel starts listening, and its own
 * play/pause key. Started once, kept for as long as Dashwheel runs.
 */
internal object TopwayRadio {
    /** A preset: its [slot] (0-based), [freq] in the tuner's units, and RDS [name] when it sent one. */
    data class Preset(val slot: Int, val freq: Int, val name: String)

    /** What the tuner plays: [freq] in 10 kHz on FM, kHz on AM; [band] the tuner's (0 FM1, 1 FM2, 2 AM). */
    data class Station(val freq: Int, val band: Int, val name: String?, val text: String?, val preset: Int)

    private val _station = MutableStateFlow<Station?>(null)
    val station: StateFlow<Station?> = _station
    private val _presets = MutableStateFlow<List<Preset>>(emptyList())
    val presets: StateFlow<List<Preset>> = _presets
    private val _isSource = MutableStateFlow(false)
    /** The radio is the unit's audio source: it plays. */
    val isSource: StateFlow<Boolean> = _isSource

    private var started = false
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    /** Whether this unit has Topway's radio. */
    fun present(context: Context): Boolean = UnitPlatform.hasPackage(context, TopwayProtocol.RADIO_PACKAGE)

    /** Told on its own thread whenever the station, presets or source change. */
    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    /** The radio's messages, on the MCU link it shares with [TopwayBus]. */
    private val radioUser: (Message) -> Unit = { onMessage(it) }

    @Synchronized
    fun start(context: Context) {
        if (started || !present(context)) return
        started = true
        val app = context.applicationContext
        this.app = app
        // One MCU link per app ([TopwayLink]): a second one of the radio's own heard nothing.
        if (!TopwayLink.acquire(app, radioUser)) return
        // The MCU speaks only when something changes: asked for the source and the tuner's state,
        // as the radio app does on opening, so a radio already playing shows at once.
        TopwayLink.write(TopwayProtocol.CH_SOURCE, TopwayProtocol.ASK_AGAIN)
        TopwayLink.write(TopwayProtocol.CH_TUNER, TopwayProtocol.ASK_AGAIN)
        DebugLog.note(app, "Topway radio: listening on the MCU link")
    }

    private var app: Context? = null

    private fun onMessage(msg: Message) {
        when (msg.what) {
            TopwayProtocol.CH_SOURCE -> {
                val radio = msg.arg1 == TopwayProtocol.SOURCE_RADIO
                if (radio != _isSource.value) app?.let { DebugLog.note(it, "Topway radio: source ${msg.arg1}, the radio ${if (radio) "plays" else "stopped"}") }
                set(_isSource, radio)
            }
            TopwayProtocol.CH_TUNER -> {
                val was = _station.value ?: Station(0, 0, null, null, -1)
                val now = when (msg.arg1) {
                    // Each band has its own presets: the new band's come after it.
                    TopwayProtocol.TUNER_BAND -> was.copy(band = msg.arg2).also { if (msg.arg2 != was.band) set(_presets, emptyList()) }
                    // A new frequency forgets the last station's RDS; its name may come with it.
                    TopwayProtocol.TUNER_FREQ -> if (msg.arg2 == was.freq) was.copy(name = rds(msg.obj) ?: was.name)
                        else was.copy(freq = msg.arg2, name = rds(msg.obj), text = null)
                    TopwayProtocol.TUNER_PRESET -> was.copy(preset = msg.arg2)
                    else -> return
                }
                if (now.freq > 0 && now.freq != _station.value?.freq) app?.let { DebugLog.note(it, "Topway radio: station ${now.freq}, band ${now.band}") }
                if (now.freq > 0) set(_station, now)
            }
            TopwayProtocol.CH_PRESET -> {
                val slot = msg.arg1 and 0xFF
                if (slot !in 0 until MAX_PRESETS) return
                val list = _presets.value.filterNot { it.slot == slot }.toMutableList()
                if (msg.arg2 > 0) list += Preset(slot, msg.arg2, rds(msg.obj).orEmpty())
                set(_presets, list.sortedBy { it.slot })
            }
            TopwayProtocol.CH_RDS_TEXT -> _station.value?.let { set(_station, it.copy(text = rds(msg.obj))) }
        }
    }

    private fun rds(obj: Any?): String? = (obj as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private fun <T> set(flow: MutableStateFlow<T>, value: T) {
        if (flow.value == value) return
        flow.value = value
        listeners.forEach { runCatching { it() } }
    }

    // --- Its buttons -----------------------------------------------------------------------

    /** Next / previous station (the radio steps through its presets). */
    fun next(context: Context) = command(context, TopwayMedia.Command.RADIO_NEXT)
    fun previous(context: Context) = command(context, TopwayMedia.Command.RADIO_PREVIOUS)
    fun seekUp(context: Context) = command(context, TopwayMedia.Command.RADIO_SEEK_UP)
    fun seekDown(context: Context) = command(context, TopwayMedia.Command.RADIO_SEEK_DOWN)
    fun tune(context: Context, freq: Int) = command(context, TopwayMedia.Command.RADIO_TUNE, freq)

    /** The radio's own play/pause key (it mutes and unmutes the tuner); opens the radio when it isn't the source. */
    fun playPause(context: Context) {
        if (!_isSource.value) return open(context)
        TopwayLink.write(TopwayProtocol.CH_KEY, 1, TopwayProtocol.KEY_PLAY_PAUSE)
    }

    /** The radio app takes the service's commands only while it is the source: opened first otherwise. */
    private fun command(context: Context, command: TopwayMedia.Command, value: Int? = null) {
        if (!_isSource.value) open(context)
        TopwayMedia.send(context, command, value)
    }

    private fun open(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(TopwayProtocol.RADIO_PACKAGE) ?: return
        runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** The radio app keeps 6 presets per band page; a few more slots are kept should a firmware send them. */
    private const val MAX_PRESETS = 30
}
