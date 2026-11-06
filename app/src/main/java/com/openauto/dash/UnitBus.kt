package com.openauto.dash

import android.content.Context
import com.openauto.dash.vehicle.CarValueProof
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Key
import com.openauto.dash.vehicle.Publisher
import com.openauto.dash.vehicle.Quality
import com.openauto.dash.vehicle.Source
import com.openauto.dash.vehicle.SourceKind
import com.openauto.dash.vehicle.SourceLiveness
import com.openauto.dash.vehicle.Vehicle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * The vehicle bus of a family other than the QF firmware's (FYT's CANBUS module, MTC's sync frame), on the
 * vehicle bus as the source `unit.bus` (CANBOX). The family's adapter decodes and puts the keys itself
 * ([VehicleBusSource.start]); this object only registers the source, hands the adapter a publisher that
 * counts each put as word from the bus, and drops the source when the bus goes quiet or its feed is lost.
 *
 * On the QF firmware nothing is put here: its car app, MCU and broadcasts are sources of their own
 * (CarBox, McuFeed / McuReader, UnitSignals).
 */
object UnitBus {
    /** What a family's bus may be silent for before it counts as gone, unless it says otherwise. */
    const val DEFAULT_SILENCE_MS = 30_000L

    /**
     * A bus says its numbers on a change (or now and then), so a speed or revs value lives this long instead of the
     * key's 3 s, and the volts [VOLTS_HOLD_MS] instead of 60 s; the temperature keeps the key's 2 h.
     */
    const val SAY_HOLD_MS = 10_000L
    const val VOLTS_HOLD_MS = 10 * 60_000L
    /** Like the car app's: a steering angle is said when it changes, and the wheel can stay put. */
    const val STEERING_HOLD_MS = 60 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val publisher = Vehicle.bus.register(Source("unit.bus", SourceKind.CANBOX, "Unit bus"))
    /** The bus's speed, believed once it has moved ([unitBusSpeed]). */
    internal val speedProof = CarValueProof()
    private var started = false

    // Guarded by itself: the bus calls raw from its own thread.
    private val rawLatest = java.util.TreeMap<String, McuReader.Entry>()
    private val _rawEntries = MutableStateFlow<List<McuReader.Entry>>(emptyList())
    /** The bus's raw values for the CAN monitor, as [McuReader.entries] rows: the key, the value as text, when it last changed. */
    val rawEntries: StateFlow<List<McuReader.Entry>> = _rawEntries

    private var sniffers = 0

    /**
     * Someone looks at every value ([on]) or stops (the Signal Finder, the CAN monitor): a bus that has to ask for
     * each value (FYT) asks for them all while at least one looks. Each true wants its false.
     */
    @Synchronized
    fun sniff(on: Boolean) {
        if (on) {
            if (sniffers++ == 0) UnitPlatform.adapter.bus?.sniff(true)
        } else if (sniffers > 0 && --sniffers == 0) {
            UnitPlatform.adapter.bus?.sniff(false)
        }
    }

    private fun rawValue(key: String, value: String) {
        SignalSources.busValue(key, value)
        val entry = synchronized(rawLatest) {
            if (rawLatest[key]?.hex == value) return
            McuReader.Entry(key, -1, wordOf(value), value, System.currentTimeMillis()).also {
                rawLatest[key] = it
                _rawEntries.value = rawLatest.values.toList()
            }
        }
        McuReader.busEntry(entry)
    }

    /**
     * A whole-number value as the two bytes of a big-endian word, so the range
     * and fuel finders, made for the MCU's frames, can search and read it too.
     */
    internal fun wordOf(value: String): List<Int> =
        value.toIntOrNull()?.takeIf { it in 0..0xFFFF }?.let { listOf(it shr 8, it and 0xFF) } ?: emptyList()

    /** Starts the family's bus, if the unit has one. */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        val source = UnitPlatform.adapter.bus ?: return
        started = true
        val live = SourceLiveness(
            source.silenceMs, scope, publisher.clock,
            onBack = { publisher.up(true) },
            // What the bus last said is not the car any more: not for the keys, nor for a taught signal read from its raw values.
            onSilent = { publisher.clear(); publisher.up(false); SignalSources.clearBus() }
        )
        // The range and fuel words learned from this bus: a bus that asks for each value follows them for good.
        source.watch(McuReader.busKeys())
        source.start(context, WatchedPublisher(publisher, live)) { key, value ->
            live.heard()
            rawValue(key, value)
        }
        // A feed that is lost says nothing more: its values go at once, not after the silence.
        scope.launch { source.listening.collect { if (!it) live.stopped() } }
    }

    /** The adapter's handle: everything it puts is word from the bus (and brings the source back up after a silence). */
    private class WatchedPublisher(private val inner: Publisher, private val live: SourceLiveness) : Publisher by inner {
        override fun <T : Any> put(key: Key<T>, value: T?, quality: Quality, atMs: Long, ttlMs: Long?) {
            live.heard()
            inner.put(key, value, quality, atMs, ttlMs)
        }
    }
}

/**
 * The bus's speed in km/h, with the driver's correction ([SpeedCorrection]) as every car-speed provider puts it; null withdraws.
 * Not said until it has once moved ([CarValueProof]): a bus without the car's speed says 0 for ever.
 */
internal fun unitBusSpeed(out: Publisher, kmh: Float?) {
    out.put(K.VEHICLE_SPEED, UnitBus.speedProof.believe(kmh?.roundToInt())?.let(SpeedCorrection::corrected)?.toDouble(), ttlMs = UnitBus.SAY_HOLD_MS)
}

internal fun unitBusRpm(out: Publisher, rpm: Int?) {
    out.put(K.RPM, rpm?.toDouble(), ttlMs = UnitBus.SAY_HOLD_MS)
}

/** The same plausible range as the MCU's figure: a bus that sends garbage says nothing. */
internal fun unitBusVolts(out: Publisher, volts: Double) {
    out.put(K.BATTERY_VOLTS, volts.takeIf { it in 9.0..16.0 }, ttlMs = UnitBus.VOLTS_HOLD_MS)
}

internal fun unitBusOutsideTemp(out: Publisher, celsius: Double?) {
    out.put(K.OUTSIDE_TEMP, celsius)
}
