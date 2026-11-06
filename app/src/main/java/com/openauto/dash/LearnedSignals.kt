package com.openauto.dash

import android.content.Context
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Publisher
import com.openauto.dash.vehicle.Sample
import com.openauto.dash.vehicle.Source
import com.openauto.dash.vehicle.SourceKind
import com.openauto.dash.vehicle.Vehicle
import com.openauto.dash.vehicle.VehicleBus

/** What the Signal Finder can teach Dashwheel: each with the action that turns it on and off. */
enum class CarSignal(@StringRes val title: Int, @StringRes val onPrompt: Int, @StringRes val offPrompt: Int) {
    FRONT_LEFT(R.string.signals_front_left, R.string.signals_on_front_left, R.string.signals_off_front_left),
    FRONT_RIGHT(R.string.signals_front_right, R.string.signals_on_front_right, R.string.signals_off_front_right),
    REAR_LEFT(R.string.signals_rear_left, R.string.signals_on_rear_left, R.string.signals_off_rear_left),
    REAR_RIGHT(R.string.signals_rear_right, R.string.signals_on_rear_right, R.string.signals_off_rear_right),
    TAILGATE(R.string.signals_tailgate, R.string.signals_on_tailgate, R.string.signals_off_tailgate),
    BONNET(R.string.signals_bonnet, R.string.signals_on_bonnet, R.string.signals_off_bonnet),
    REVERSE(R.string.signals_reverse, R.string.signals_on_reverse, R.string.signals_off_reverse),
    HEADLIGHTS(R.string.signals_headlights, R.string.signals_on_headlights, R.string.signals_off_headlights),
    DRIVER_BELT(R.string.signals_belt, R.string.signals_on_belt, R.string.signals_off_belt);

    val isDoor: Boolean get() = this in DOORS

    companion object {
        val DOORS = setOf(FRONT_LEFT, FRONT_RIGHT, REAR_LEFT, REAR_RIGHT, TAILGATE, BONNET)
    }
}

/** [learned]'s doors laid over the car's own [builtIn] ones: a learned door wins. */
internal fun mergeDoors(builtIn: McuReader.DoorState?, learned: Map<CarSignal, Boolean>): McuReader.DoorState? {
    if (learned.keys.none { it.isDoor }) return builtIn
    val base = builtIn ?: McuReader.DoorState()
    return base.copy(
        frontLeft = learned[CarSignal.FRONT_LEFT] ?: base.frontLeft,
        frontRight = learned[CarSignal.FRONT_RIGHT] ?: base.frontRight,
        rearLeft = learned[CarSignal.REAR_LEFT] ?: base.rearLeft,
        rearRight = learned[CarSignal.REAR_RIGHT] ?: base.rearRight,
        tailgate = learned[CarSignal.TAILGATE] ?: base.tailgate,
        bonnet = learned[CarSignal.BONNET] ?: base.bonnet
    )
}

/**
 * The learned doors held steady: a door's new reading counts once it has lasted [reads] reads in a row, so a
 * piece that also flickers with the engine or the speed for a moment does not open the door on screen. The
 * first reading, and every other signal, passes at once.
 */
internal class DoorSettle(private val reads: Int) {
    private var held: Map<CarSignal, Boolean> = emptyMap()
    private val streak = HashMap<CarSignal, Int>()

    fun settle(raw: Map<CarSignal, Boolean>): Map<CarSignal, Boolean> {
        val out = HashMap<CarSignal, Boolean>(raw.size)
        for ((signal, on) in raw) {
            val was = held[signal]
            out[signal] = when {
                !signal.isDoor || was == null || was == on -> { streak.remove(signal); on }
                (streak[signal] ?: 0) + 1 >= reads -> { streak.remove(signal); on }
                else -> { streak[signal] = (streak[signal] ?: 0) + 1; was }
            }
        }
        streak.keys.retainAll(raw.keys)
        held = out
        return out
    }
}

/** The car's own doors among the bus's candidates: the best of the built-in sources, never a learned or demo one. */
internal fun builtInDoors(candidates: List<Sample<McuReader.DoorState>>): McuReader.DoorState? =
    candidates.firstOrNull { it.source.kind.rank >= 0 }?.value

/**
 * The learned doors on the bus. Doors are per door: a learned door overrides the car's own for that door
 * only, so one candidate for the whole state would wrongly hide the doors the driver did not teach. The
 * provider therefore publishes its merged view ([mergeDoors]): the learned doors over the car's own state,
 * which it follows from the bus's candidates ([follow]). It is a LEARNED source, so that view wins over every
 * built-in one; with no learned door it publishes nothing and the car's own state stands.
 */
internal class LearnedDoors(private val out: Publisher) {
    private var learned: Map<CarSignal, Boolean> = emptyMap()
    private var builtIn: McuReader.DoorState? = null

    /** The learned doors' states now (any other signals in [state] are ignored). */
    @Synchronized
    fun learned(state: Map<CarSignal, Boolean>) {
        learned = state.filterKeys { it.isDoor }
        publish()
    }

    /** The car's own doors changed. */
    @Synchronized
    fun builtIn(state: McuReader.DoorState?) {
        if (state == builtIn) return
        builtIn = state
        publish()
    }

    /** Follows the car's own doors on [bus] for as long as it runs. */
    suspend fun follow(bus: VehicleBus) {
        bus.candidates(K.DOORS).map { builtInDoors(it) }.distinctUntilChanged().collect { builtIn(it) }
    }

    private fun publish() {
        out.put(K.DOORS, if (learned.isEmpty()) null else mergeDoors(builtIn, learned))
    }
}

/**
 * The signals the driver taught Dashwheel with the Signal Finder: which
 * piece of which value stands for each ([SignalProbe]), read a few times a
 * second and handed to what shows them: put on the vehicle bus as the learned
 * source (doors ([LearnedDoors]), reverse, headlights, the driver's belt),
 * which outranks the built-in decoding, and still into [McuReader], [CarBox]
 * and [UnitSignals] for what reads those. Also the camera the reverse view opens.
 */
object LearnedSignals {
    private const val PREFS = "car_signals"
    private const val KEY_CAMERA = "camera_id"
    private const val READ_MS = 300L
    /** About a second: a door that really opens shows a moment later, a flicker never. */
    private const val DOOR_READS = 4

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var appContext: Context? = null
    private var job: Job? = null
    private var holdsCan = false

    private val busPublisher = Vehicle.bus.register(Source("learned", SourceKind.LEARNED, "Signal Finder"))
    private val doors = LearnedDoors(busPublisher)

    private val _mapped = MutableStateFlow<Map<CarSignal, SignalProbe>>(emptyMap())
    /** What stands for each learned signal. */
    val mapped: StateFlow<Map<CarSignal, SignalProbe>> = _mapped

    private val _state = MutableStateFlow<Map<CarSignal, Boolean>>(emptyMap())
    /** Each learned signal's state now, once its value has been read. */
    val state: StateFlow<Map<CarSignal, Boolean>> = _state

    private val _cameraId = MutableStateFlow<String?>(null)
    /** The camera the reverse view opens, when the driver picked one; else the car app's. */
    val cameraId: StateFlow<String?> = _cameraId

    @Synchronized
    fun start(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _mapped.value = CarSignal.entries.mapNotNull { s -> SignalProbe.decode(p.getString(s.name, null))?.let { s to it } }.toMap()
        _cameraId.value = p.getString(KEY_CAMERA, null)
        scope.launch { doors.follow(Vehicle.bus) }
        follow()
    }

    fun assign(context: Context, signal: CarSignal, probe: SignalProbe) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(signal.name, probe.encode()).apply()
        _mapped.value = _mapped.value + (signal to probe)
        follow()
    }

    fun forget(context: Context, signal: CarSignal) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(signal.name).apply()
        _mapped.value = _mapped.value - signal
        _state.value = _state.value - signal
        publish(_state.value, signal)
        follow()
    }

    fun setCamera(context: Context, id: String?) {
        val e = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (id == null) e.remove(KEY_CAMERA) else e.putString(KEY_CAMERA, id)
        e.apply()
        _cameraId.value = id
    }

    /** Reads the learned signals while there are any; holds the car box's log while one comes from it. */
    @Synchronized
    private fun follow() {
        val app = appContext ?: return
        val probes = _mapped.value
        val wantsCan = probes.values.any { SignalSource.of(it.key) == SignalSource.CAN }
        if (wantsCan && !holdsCan) { McuReader.start(); holdsCan = true }
        if (!wantsCan && holdsCan) { McuReader.stop(); holdsCan = false }
        if (probes.values.any { SignalSource.of(it.key) == SignalSource.BROADCAST }) SignalSources.listen(app)
        // A bus that has to ask for each value (FYT) is told which ones the learned signals need.
        UnitPlatform.adapter.bus?.watch(probes.values.map { it.key }.toSet())
        if (probes.isEmpty()) {
            job?.cancel()
            job = null
            // Nothing is read any more: nothing is said, and the source is down until the driver teaches one again.
            busPublisher.clear()
            busPublisher.up(false)
            return
        }
        busPublisher.up(true)
        if (job?.isActive == true) return
        job = scope.launch {
            val settle = DoorSettle(DOOR_READS)
            while (isActive) {
                val raw = HashMap<CarSignal, Boolean>()
                for ((signal, probe) in _mapped.value) {
                    probe.isOn(SignalSources.current(app, probe.key))?.let { raw[signal] = it }
                }
                val now = settle.settle(raw)
                val before = _state.value
                if (now != before) {
                    _state.value = now
                    for (s in CarSignal.entries) if (now[s] != before[s]) publish(now, s)
                }
                delay(READ_MS)
            }
        }
    }

    /**
     * The reverse failsafe ([CarBox]) found no evidence of a reverse the taught signal still says: withdrawn, until the
     * signal changes (a new reading of "on" after an "off").
     */
    internal fun reverseEnded() {
        busPublisher.put(K.REVERSE, null)
    }

    /** Hands [signal]'s new state in [now] to what shows it. */
    private fun publish(now: Map<CarSignal, Boolean>, signal: CarSignal) {
        when {
            signal.isDoor -> doors.learned(now)
            signal == CarSignal.REVERSE -> busPublisher.put(K.REVERSE, now[signal])
            signal == CarSignal.HEADLIGHTS -> busPublisher.put(K.HEADLIGHTS, now[signal])
            // The belt reminder reads the key, with the car's speed.
            signal == CarSignal.DRIVER_BELT -> busPublisher.put(K.DRIVER_BELT, now[signal])
            else -> Unit
        }
    }
}
