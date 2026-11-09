package com.openauto.dash

import com.openauto.dash.vehicle.Vehicle
import com.openauto.dash.vehicle.K
import android.content.Context
import android.media.AudioManager
import com.openauto.dash.vehicle.speedKmhNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * How the music gets louder with speed: the first volume step comes on at
 * [startKmh], the last of [maxSteps] at [fullKmh], the others evenly between.
 * A step is one step of the volume itself, as one click of the knob.
 */
data class SpeedVolumeCurve(val startKmh: Int, val fullKmh: Int, val maxSteps: Int) {

    /** The same curve within what the settings offer, the top speed always above the start. */
    fun fixed(): SpeedVolumeCurve {
        val start = startKmh.coerceIn(START_MIN_KMH, START_MAX_KMH)
        return SpeedVolumeCurve(
            startKmh = start,
            fullKmh = fullKmh.coerceIn(start + SPEED_STEP_KMH, FULL_MAX_KMH),
            maxSteps = maxSteps.coerceIn(1, STEPS_MAX)
        )
    }

    companion object {
        const val START_MIN_KMH = 20
        const val START_MAX_KMH = 130
        const val FULL_MAX_KMH = 160
        /** What one press of − or + changes a speed by. */
        const val SPEED_STEP_KMH = 10
        const val STEPS_MAX = 12

        /** Barely heard: one step on the main road, a second one on the motorway. */
        val DEFAULT = SpeedVolumeCurve(startKmh = 60, fullKmh = 120, maxSteps = 2)
    }
}

/**
 * Speed-dependent volume: turns the music up as road noise grows and back
 * down as the car slows. It only ever adds and removes its own steps, one
 * a second, through [MediaVolume], so the driver's own volume changes are
 * kept and head units whose volume follows only the keys work too. Speed
 * comes from OBD when connected, GPS otherwise; without one it holds.
 */
object SpeedVolume {
    private const val PREFS = "speed_volume"
    private const val KEY_ON = "on"
    private const val KEY_START = "start_kmh"
    private const val KEY_FULL = "full_kmh"
    private const val KEY_STEPS = "max_steps"
    /** Before the curve could be set: OFF, LOW, MEDIUM or HIGH. */
    private const val KEY_OLD_LEVEL = "level"

    /** A step comes off only once the speed is this far under where it came on, so cruising at a threshold doesn't pump. */
    const val HYSTERESIS_KMH = 8
    private const val TICK_MS = 1_000L

    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on.asStateFlow()

    private val _curve = MutableStateFlow(SpeedVolumeCurve.DEFAULT)
    val curve: StateFlow<SpeedVolumeCurve> = _curve.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false

    /** Steps wanted for the current speed. */
    private var boost = 0
    /**
     * Volume steps (or key presses) actually added and not yet given back, for
     * the sound source playing now. The unit keeps a level for each (radio,
     * media, aux): one count for all took from the media what it had added to
     * the radio, which then stayed louder for good.
     */
    private var applied: Int
        get() = appliedBy[source] ?: 0
        set(value) {
            if (value == 0) appliedBy.remove(source) else appliedBy[source] = value
        }
    private val appliedBy = HashMap<String, Int>()
    private var source = ""

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        load(app)
        scope.launch {
            _on.collectLatest { on ->
                if (on) follow(app)
            }
        }
    }

    fun save(context: Context, on: Boolean) {
        _on.value = on
        prefs(context).edit().putBoolean(KEY_ON, on).apply()
    }

    /** Takes effect at once: while driving, the volume moves to the new curve a step a second. */
    fun save(context: Context, curve: SpeedVolumeCurve) {
        val fixed = curve.fixed()
        _curve.value = fixed
        prefs(context).edit()
            .putInt(KEY_START, fixed.startKmh)
            .putInt(KEY_FULL, fixed.fullKmh)
            .putInt(KEY_STEPS, fixed.maxSteps)
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(app: Context) {
        val prefs = prefs(app)
        val old = prefs.getString(KEY_OLD_LEVEL, null)
        if (old != null && !prefs.contains(KEY_ON)) {
            val notch = if (MediaVolume.byKeys.value) 1 else unitFor(MediaVolume.max(MediaVolume.audio(app)))
            save(app, fromOldLevel(old, notch) ?: SpeedVolumeCurve.DEFAULT)
            save(app, old != "OFF")
            prefs.edit().remove(KEY_OLD_LEVEL).apply()
            return
        }
        _on.value = prefs.getBoolean(KEY_ON, false)
        val default = SpeedVolumeCurve.DEFAULT
        _curve.value = SpeedVolumeCurve(
            startKmh = prefs.getInt(KEY_START, default.startKmh),
            fullKmh = prefs.getInt(KEY_FULL, default.fullKmh),
            maxSteps = prefs.getInt(KEY_STEPS, default.maxSteps)
        ).fixed()
    }

    /**
     * The curve of a level chosen before it could be set. Those counted in
     * notches of [notchSteps] volume steps each (see [unitFor]): Medium and
     * High stay as loud as they were, Low, which was too loud on units with
     * fine steps, now counts single steps. Null for Off or an unknown name.
     */
    internal fun fromOldLevel(name: String, notchSteps: Int): SpeedVolumeCurve? = when (name) {
        "LOW" -> SpeedVolumeCurve(startKmh = 40, fullKmh = 80, maxSteps = 2)
        "MEDIUM" -> SpeedVolumeCurve(startKmh = 40, fullKmh = 120, maxSteps = 4 * notchSteps)
        "HIGH" -> SpeedVolumeCurve(startKmh = 40, fullKmh = 120, maxSteps = 6 * notchSteps)
        else -> null
    }?.fixed()

    /** Runs while the feature is on; switched off, it gives back what it added. */
    private suspend fun follow(app: Context) {
        var gps = false
        // The speed is read by snapshot in [tick]: it stays wanted while the feature runs.
        val hold = Vehicle.bus.hold(K.SPEED)
        try {
            while (true) {
                if (!gps) gps = LocationFeed.acquire(app)
                tick(app)
                delay(TICK_MS)
            }
        } finally {
            hold.release()
            if (gps) LocationFeed.release()
            giveBack(app)
        }
    }

    private fun tick(app: Context) {
        if (DemoMode.isOn || MediaVolume.unavailable.value) return
        // Standing still with nothing added: nothing to do, and the unit's state (a few binder calls) is not read.
        if (boost == 0 && applied == 0 && target(speedKmh(), 0, _curve.value) == 0) return
        val audio = MediaVolume.audio(app)
        // In a call the keys would change the call's volume; muted, a raise would unmute.
        if (inCall(audio)) return
        if (!MediaVolume.byKeys.value && MediaVolume.isMuted(audio)) return
        val playing = MediaVolume.unitSource()
        // The unit's own call volume is the driver's: never moved by the speed.
        if (playing == MediaVolume.UNIT_CALL_SOURCE) return
        source = playing
        boost = target(speedKmh(), boost, _curve.value)
        when {
            applied < boost -> applied += nudge(app, up = true)
            applied > boost -> applied -= nudge(app, up = false)
        }
    }

    /** One step up or down; returns how many steps it really moved. */
    private fun nudge(app: Context, up: Boolean): Int {
        val audio = MediaVolume.audio(app)
        val before = MediaVolume.level(audio)
        if (up) MediaVolume.raise(app) else MediaVolume.lower(app)
        // Pressed as a key (all along, or just found to be needed): the level can't be read, count the press.
        if (MediaVolume.byKeys.value) return 1
        val moved = MediaVolume.level(audio) - before
        return if (up) moved.coerceAtLeast(0) else (-moved).coerceAtLeast(0)
    }

    private fun giveBack(app: Context) {
        // Muted meanwhile: a step down on the unit would set its level and unmute it ([tick] holds off the same way).
        if (!MediaVolume.byKeys.value && MediaVolume.isMuted(MediaVolume.audio(app))) {
            appliedBy.clear()
            boost = 0
            return
        }
        var tries = applied
        while (applied > 0 && tries-- > 0) {
            val moved = nudge(app, up = false)
            if (moved == 0) break
            applied -= moved
        }
        // The other sources keep what they got: their level can't be reached while they don't play.
        appliedBy.clear()
        boost = 0
    }

    private fun speedKmh(): Int? = speedKmhNow()

    private fun inCall(audio: AudioManager): Boolean =
        audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION

    /** Steps for [speedKmh] on [curve], ignoring hysteresis. */
    internal fun boostFor(speedKmh: Int, curve: SpeedVolumeCurve): Int {
        if (speedKmh < curve.startKmh) return 0
        if (curve.maxSteps <= 1 || curve.fullKmh <= curve.startKmh) return curve.maxSteps.coerceAtLeast(0)
        val more = (speedKmh - curve.startKmh) * (curve.maxSteps - 1) / (curve.fullKmh - curve.startKmh)
        return (1 + more).coerceAtMost(curve.maxSteps)
    }

    /**
     * The steps to aim for from [current]: up as soon as the speed calls for
     * more, down only once it is [HYSTERESIS_KMH] under; an unknown speed holds.
     */
    internal fun target(speedKmh: Int?, current: Int, curve: SpeedVolumeCurve): Int {
        val held = current.coerceIn(0, curve.maxSteps.coerceAtLeast(0))
        if (speedKmh == null) return held
        val raw = boostFor(speedKmh, curve)
        if (raw > held) return raw
        val down = boostFor(speedKmh + HYSTERESIS_KMH, curve)
        return if (down < held) down else held
    }

    /** Volume steps in a notch of the levels there were: about 1/15 of the range, however fine the ROM's steps are. */
    internal fun unitFor(maxVolume: Int): Int = (maxVolume / 15f).roundToInt().coerceAtLeast(1)
}
