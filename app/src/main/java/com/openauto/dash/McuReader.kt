package com.openauto.dash

import android.content.Context
import com.openauto.dash.unit.QfBus
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.openauto.dash.vehicle.FUEL_HOLD_MS
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Source
import com.openauto.dash.vehicle.SourceLiveness
import com.openauto.dash.vehicle.SourceKind
import com.openauto.dash.vehicle.Vehicle
import kotlin.math.roundToInt

/**
 * Reads the head unit's **CANbox/MCU** stream: straight from the MCU service
 * where it lets Dashwheel listen ([McuFeed], no root), else by tailing the
 * `mcu_services` logcat (needs root). The MCU logs every car value as
 * `dispatchToClients - cmdId: X - data : [ .. ]`, so we parse those into a live
 * map of `key -> latest bytes`, tracking when each last changed (for the CAN
 * monitor's "flip" highlight and, later, to read door/fuel/light state).
 *
 * cmdId 65 (control 0x41) is a multiplexed status channel `41 fd <sub> ..`, so
 * we split those into per-`sub` keys ("65.0C", "65.05", …) to keep each value
 * stable instead of overwriting each other.
 */
object McuReader {

    data class Entry(val key: String, val cmdId: Int, val bytes: List<Int>, val hex: String, val changedAt: Long)

    /** The MCU on the vehicle bus: the doors of its status frame (65 .. 0C 38), as the car says them. */
    private val busPublisher = Vehicle.bus.register(Source("qf.mcu", SourceKind.MCU, "Unit MCU"))

    /**
     * The fuel byte and the range word the driver taught ([saveFuelMapping], [saveRangeMapping]) on the vehicle bus.
     * They are the driver's own reading, so they outrank the car app's litres and the adapter's PID, as they always did.
     */
    private val learnedPublisher = Vehicle.bus.register(Source("learned.can", SourceKind.LEARNED, "Learned fuel and range"))
    @Volatile private var learnedFuel: Int? = null
    @Volatile private var learnedRange: Int? = null

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** One frame just took a new value; [previousHex] is null the first time its key is seen. */
    data class Change(val entry: Entry, val previousHex: String?)

    // Every change, in order: [entries] only keeps the latest state, so a quick
    // press-and-release (a steering wheel button, SteeringWheelStore) would be lost.
    private val _changes = MutableSharedFlow<Change>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes: SharedFlow<Change> = _changes.asSharedFlow()

    /**
     * Decoded door open/closed state (C4 Picasso bit map, confirmed 2026-09-20), from the door bitfield byte of the MCU
     * status frame `41 FD 0C 38 <bits> …` (cmdId 65, sub 0x0C, subtype 0x38; bit 0x80 = front left).
     */
    data class DoorState(
        val frontLeft: Boolean = false,
        val frontRight: Boolean = false,
        val rearLeft: Boolean = false,
        val rearRight: Boolean = false,
        val tailgate: Boolean = false,
        val bonnet: Boolean = false
    ) {
        val anyOpen: Boolean get() = frontLeft || frontRight || rearLeft || rearRight || tailgate || bonnet
    }

    // --- Fuel (CANbox) ------------------------------------------------------
    // The C4 Picasso's OBD does not report fuel level, but the CANbox does —
    // somewhere in the MCU stream as a raw byte. We can't know which byte a
    // priori (the firmware is stripped), so it's *learned*: the user picks the
    // byte matching their dash gauge in the Range widget's finder, with a
    // one-point calibration. [fullRaw] is the raw value that equals a full tank.
    data class FuelMapping(val key: String, val byteIndex: Int, val fullRaw: Int)

    @Volatile
    private var fuelMapping: FuelMapping? = null
    val fuelConfigured: Boolean get() = fuelMapping != null

    private val _fuelPercent = MutableStateFlow<Int?>(null)
    /** Live fuel level 0..100 decoded from the learned CANbox byte, or null. */
    val fuelPercent: StateFlow<Int?> = _fuelPercent.asStateFlow()

    // --- Range (CANbox) -----------------------------------------------------
    // The head unit's own trip computer shows the car's distance to empty, so
    // it's in the MCU stream too. Learned the same way: the user types the km
    // the trip computer shows and picks the word that holds it.

    @Volatile
    private var rangeMapping: RangeMapping? = null
    val rangeConfigured: Boolean get() = rangeMapping != null

    private val _rangeKm = MutableStateFlow<Int?>(null)
    /** The car's own distance to empty in km, from the learned CANbox word, or null. */
    val rangeKm: StateFlow<Int?> = _rangeKm.asStateFlow()

    private var appContext: Context? = null
    private const val PREFS = "mcu_prefs"

    /** Give McuReader an app context so the learned fuel mapping can persist. */
    fun setContext(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            loadFuelMapping()
            loadRangeMapping()
            UnitPlatform.adapter.bus?.watch(busKeys())
        }
    }

    /** The keys of the learned range and fuel words, for a bus that only sends what it is asked for ([UnitBus]). */
    internal fun busKeys(): Set<String> = setOfNotNull(rangeMapping?.key, fuelMapping?.key)

    /** A value of another family's bus ([UnitBus]), as a frame: the learned range and fuel words are read from it too. */
    internal fun busEntry(e: Entry) {
        if (e.bytes.isEmpty()) return
        applyMappings(e.key, e.bytes)
    }

    private fun loadRangeMapping() {
        val p = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        val key = p.getString("range_key", null) ?: return
        val idx = p.getInt("range_byte", -1)
        val scale = p.getInt("range_scale", 1)
        if (idx >= 0 && scale > 0) rangeMapping = RangeMapping(key, idx, p.getBoolean("range_be", true), scale)
    }

    /** Persist the learned range word; decodes from the frame already seen, else the next one. */
    internal fun saveRangeMapping(m: RangeMapping) {
        rangeMapping = m
        UnitPlatform.adapter.bus?.watch(setOf(m.key))
        setLearnedRange((_entries.value + UnitBus.rawEntries.value).firstOrNull { it.key == m.key }?.let { m.decode(it.bytes) })
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString("range_key", m.key)
            ?.putInt("range_byte", m.index)
            ?.putBoolean("range_be", m.bigEndian)
            ?.putInt("range_scale", m.scale)
            ?.apply()
    }

    /** Forget the learned range word (e.g. to pick another one). */
    fun clearRangeMapping() {
        rangeMapping = null
        setLearnedRange(null)
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.remove("range_key")?.remove("range_byte")?.remove("range_be")?.remove("range_scale")?.apply()
    }

    private fun loadFuelMapping() {
        val p = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        val key = p.getString("fuel_key", null) ?: return
        val idx = p.getInt("fuel_byte", -1)
        val full = p.getInt("fuel_fullraw", -1)
        if (idx >= 0 && full > 0) fuelMapping = FuelMapping(key, idx, full)
    }

    /** Persist the learned fuel byte + calibration; takes effect on the next frame. */
    fun saveFuelMapping(key: String, byteIndex: Int, fullRaw: Int) {
        val fm = FuelMapping(key, byteIndex, fullRaw.coerceAtLeast(1))
        fuelMapping = fm
        UnitPlatform.adapter.bus?.watch(setOf(key))
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString("fuel_key", fm.key)
            ?.putInt("fuel_byte", fm.byteIndex)
            ?.putInt("fuel_fullraw", fm.fullRaw)
            ?.apply()
    }

    // --- Fuel Finder: persisted Capture A ------------------------------------
    // The two capture points are taken minutes to days apart (fuel only drops by
    // actually driving), so Capture A is saved to prefs and survives closing the
    // dialog and app restarts. Capture B is then taken later against this A.

    /** Persist Capture A (fuel % + a snapshot of every frame's bytes). */
    fun saveFuelCaptureA(pct: Int, snapshot: Map<String, List<Int>>) {
        val json = JSONObject()
        snapshot.forEach { (k, bytes) -> json.put(k, JSONArray(bytes)) }
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putInt("fuelcapA_pct", pct)
            ?.putString("fuelcapA_data", json.toString())
            ?.apply()
    }

    /** The saved Capture A (fuel % to frame→bytes), or null if none is stored. */
    fun loadFuelCaptureA(): Pair<Int, Map<String, List<Int>>>? {
        val p = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return null
        val pct = p.getInt("fuelcapA_pct", 0)
        val data = p.getString("fuelcapA_data", null) ?: return null
        if (pct <= 0) return null
        return runCatching {
            val obj = JSONObject(data)
            val map = LinkedHashMap<String, List<Int>>()
            obj.keys().forEach { k ->
                val arr = obj.getJSONArray(k)
                map[k] = (0 until arr.length()).map { arr.getInt(it) }
            }
            pct to (map as Map<String, List<Int>>)
        }.getOrNull()
    }

    /** Discard the saved Capture A (on Reset or once a mapping is chosen). */
    fun clearFuelCaptureA() {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.remove("fuelcapA_pct")?.remove("fuelcapA_data")?.apply()
    }

    /** Forget the learned fuel byte (e.g. to re-run the finder). */
    fun clearFuelMapping() {
        fuelMapping = null
        setLearnedFuel(null)
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.remove("fuel_key")?.remove("fuel_byte")?.remove("fuel_fullraw")?.apply()
    }

    private fun setFuel(v: Int?) {
        _fuelPercent.value = v
    }

    private fun setRange(v: Int?) {
        _rangeKm.value = v
    }

    /** The learned fuel byte's reading: the store, and the bus as the learned source. */
    private fun setLearnedFuel(v: Int?) {
        setFuel(v)
        if (learnedFuel == v) return
        learnedFuel = v
        publishLearned()
    }

    private fun setLearnedRange(v: Int?) {
        setRange(v)
        if (learnedRange == v) return
        learnedRange = v
        publishLearned()
    }

    /**
     * Fuel and range together, as an empty tank is judged against the range ([fuelPercentToPublish]): the range
     * the learned word gives, else the one on the bus. Said on a change, and a parked car says nothing, so they are kept long.
     */
    private fun publishLearned() {
        val range = learnedRange ?: Vehicle.bus.sample(K.RANGE).value?.takeUnless { it.source.kind == SourceKind.COMPUTED }?.value?.roundToInt()
        learnedPublisher.put(K.FUEL_LEVEL, fuelPercentToPublish(learnedFuel, range)?.toDouble(), ttlMs = FUEL_HOLD_MS)
        learnedPublisher.put(K.RANGE, learnedRange?.toDouble(), ttlMs = FUEL_HOLD_MS)
    }

    /**
     * Fuel and range from the car app's shared data ([CarBox]), for whichever
     * of the two was never learned from the MCU stream: a learned byte wins.
     */
    internal fun carBoxWrite(fuelPercent: Int?, rangeKm: Int?) {
        if (fuelMapping == null && fuelPercent != null) setFuel(fuelPercent)
        if (rangeMapping == null && rangeKm != null) setRange(rangeKm)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * The MCU's liveness, for what it puts as `qf.mcu` here and in [UnitSignals] (doors, volts, headlights, handbrake). The
     * MCU says most of those on a change, and a car without a CAN box says little else, so only a long silence means the
     * MCU is gone: [MCU_SILENCE_MS], as long as the volts are believed. With a CAN box its frames repeat several times a
     * second, and the stream stops at once with the service ([mcuStopped]). Silent: everything it put is withdrawn.
     */
    private val mcuLive = SourceLiveness.of(busPublisher, MCU_SILENCE_MS, scope)

    /** Any message from the MCU, by its service ([McuFeed]) or by the root logcat. */
    internal fun heardMcu() = mcuLive.heard()

    /** The MCU service went away ([McuFeed]): what it said no longer holds. */
    internal fun mcuStopped() = mcuLive.stopped()

    internal const val MCU_SILENCE_MS = 10 * 60_000L
    // All guarded by this object's lock.
    private var job: Job? = null
    private var process: Process? = null
    private var refCount = 0
    private var shutdown: Job? = null
    // Sorted by key so publishing is a plain copy, no per-line sort. Only the reader thread touches it.
    private val latest = java.util.TreeMap<String, Entry>()

    private val regex = Regex("""dispatchToClients - cmdId:\s*(\d+)\s*-\s*data\s*:\s*\[([0-9a-fA-F ]*)]""")
    private val whitespace = Regex("\\s+")
    private const val HEX_DIGITS = "0123456789ABCDEF"

    /** How long the root logcat outlives its last user: a page swipe lets go and takes it again at once. */
    private const val LINGER_MS = 30_000L
    private const val RETRY_MIN_MS = 2_000L
    private const val RETRY_MAX_MS = 5 * 60_000L
    /** A logcat that ran this long was working: the next failure starts the backoff over. */
    private const val HEALTHY_MS = 60_000L

    /** What the CAN monitor lists: the MCU stream's frames, then the values of another family's bus ([UnitBus]). */
    val monitorEntries: StateFlow<List<Entry>> = combine(_entries, UnitBus.rawEntries) { mcu, bus -> mcu + bus }
        // Only while the monitor shows: the list was joined again at every changed frame for nobody.
        .stateIn(scope, SharingStarted.WhileSubscribed(), emptyList())

    /**
     * What a car box tile says while it has nothing yet: that root is needed
     * only where the data really comes through the root logcat (the QF MCU
     * stream without its service, or a unit with no bus of its own). FYT and
     * the like hand their bus over to any app.
     */
    val waitingRes: Int
        get() {
            val bus = UnitPlatform.adapter.bus
            return if ((bus == null || bus === QfBus) && !McuFeed.listening.value) R.string.vehicle_waiting_mcu_root
            else R.string.vehicle_waiting_mcu
        }

    @Synchronized
    private fun applyMappings(key: String, bytes: List<Int>) {
        // Fuel: the learned CANbox byte → percent, calibrated against a full tank.
        fuelMapping?.let { fm ->
            if (key == fm.key && bytes.size > fm.byteIndex && fm.fullRaw > 0) {
                setLearnedFuel((bytes[fm.byteIndex] * 100 / fm.fullRaw).coerceIn(0, 100))
            }
        }

        // Range: the learned 16-bit word → km, straight from the car's trip computer.
        rangeMapping?.let { rm ->
            if (key == rm.key) rm.decode(bytes)?.let { setLearnedRange(it) }
        }
    }

    /** Whether learning from the car box takes root here: not where another family's bus hands it over. */
    val learningNeedsRoot: Boolean get() = !otherBus()

    /** Another family's bus feeds the car box data: its MCU log (QF's) is not there to tail. */
    private fun otherBus(): Boolean = UnitPlatform.adapter.bus.let { it != null && it !== QfBus }

    @Synchronized
    fun start() {
        // Without the MCU service, the stream is read through root: the Play edition has none.
        if (Edition.play && !McuFeed.listening.value) return
        refCount++
        shutdown?.cancel()
        shutdown = null
        if (job?.isActive == true) return
        job = scope.launch { tail() }
    }

    @Synchronized
    fun stop() {
        if (refCount == 0) return
        if (--refCount > 0) return
        shutdown?.cancel()
        shutdown = scope.launch {
            delay(LINGER_MS)
            synchronized(this@McuReader) {
                if (refCount > 0) return@launch
                job?.cancel()
                job = null
                process?.let(::kill)
                process = null
            }
        }
    }

    /**
     * Runs `su logcat` and reads it for as long as the reader is wanted. When
     * it ends by itself (su refused at boot, logcat killed), it starts again,
     * waiting longer each time it fails quickly.
     */
    private suspend fun tail() {
        val ctx = currentCoroutineContext()
        var backoff = RETRY_MIN_MS
        while (ctx.isActive) {
            val startedAt = System.currentTimeMillis()
            // The MCU service hands every message over by itself: no root logcat beside it.
            if (McuFeed.listening.value) {
                McuFeed.listening.first { !it }
                continue
            }
            // logcat first prints what it kept from before it was started. Those
            // lines still say where things stand (a door, the fuel), but a wheel
            // button pressed minutes ago is not pressed again: no [changes] for them.
            val liveFrom = logStamp(startedAt)
            var caughtUp = false
            // Started under the lock: a stop() can't slip in between and miss it.
            val p = synchronized(this) {
                if (!ctx.isActive || Edition.play || otherBus()) return
                runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", "exec logcat -s mcu_services:D")) }
                    .getOrNull()?.also { process = it }
            }
            if (p != null) {
                try {
                    val reader = p.inputStream.bufferedReader()
                    while (ctx.isActive && !McuFeed.listening.value) {
                        val line = reader.readLine() ?: break
                        if (!caughtUp) caughtUp = !loggedBefore(line, liveFrom)
                        parse(line, live = caughtUp)
                    }
                } catch (e: IOException) {
                    // Killed by stop(), or logcat went away: same as its end.
                } finally {
                    synchronized(this) { if (process === p) process = null }
                    kill(p)
                }
            }
            if (System.currentTimeMillis() - startedAt >= HEALTHY_MS) backoff = RETRY_MIN_MS
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(RETRY_MAX_MS)
        }
    }

    /**
     * Ends [p]. Closing its output also ends the logcat that su started: a su
     * may not pass the kill on, but logcat dies on its next line to a closed pipe.
     */
    private fun kill(p: Process) {
        runCatching { p.inputStream.close() }
        runCatching { p.destroy() }
        runCatching { p.destroyForcibly() }
    }

    /** logcat's stamp at the head of each line ("MM-dd HH:mm:ss.SSS") for the time [at]: text that sorts by time. */
    internal fun logStamp(at: Long): String =
        java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT).format(java.util.Date(at))

    /** Whether [line] was logged before [stamp] (see [logStamp]); a line without a stamp of that shape counts as new. */
    internal fun loggedBefore(line: String, stamp: String): Boolean {
        val n = stamp.length
        if (line.length <= n || line[2] != '-' || line[5] != ' ' || line[8] != ':' || line[14] != '.') return false
        return line.regionMatches(0, stamp, 0, n).not() && line.substring(0, n) < stamp
    }

    /** [live] false for a line from before the reader started: it sets the values, without counting as a change. */
    private fun parse(line: String, live: Boolean = true) {
        val m = regex.find(line) ?: return
        val cmdId = m.groupValues[1].toIntOrNull() ?: return
        if (live) heardMcu()
        val bytes = m.groupValues[2].trim().split(whitespace)
            .filter { it.isNotEmpty() }
            .mapNotNull { it.toIntOrNull(16) }
        if (bytes.isEmpty()) return
        ingest(cmdId, bytes, live)
        // The CAN box stream, for the trip computer: what the MCU service would hand over without root.
        if (cmdId == McuFeed.CMD_CAN && live) {
            val raw = ByteArray(bytes.size) { bytes[it].toByte() }
            PsaTripComputer.feed(raw, 1)
            CarBoxKeys.feed(raw, 1)
        }
    }

    /**
     * One message from the MCU service ([McuFeed]): [cmd, data…], the very
     * bytes its log shows. Read only while a reader is wanted, as the log is.
     */
    internal fun feed(message: ByteArray) {
        if (refCount == 0) return
        ingest(message[0].toInt() and 0xFF, message.map { it.toInt() and 0xFF }, live = true)
    }

    // From the log reader or the MCU service's thread, one at a time.
    @Synchronized
    private fun ingest(cmdId: Int, bytes: List<Int>, live: Boolean) {

        val key = keyOf(cmdId, bytes)
        // Runs for every line the CANbox logs (dozens a second): no String.format per byte.
        val hex = buildString(bytes.size * 3) {
            bytes.forEachIndexed { i, b ->
                if (i > 0) append(' ')
                append(HEX_DIGITS[(b shr 4) and 0x0F]).append(HEX_DIGITS[b and 0x0F])
            }
        }
        val prev = latest[key]
        // The CANbox repeats most frames several times a second; only a new
        // value is worth a new entry and waking every collector for.
        val changed = prev == null || prev.hex != hex
        if (changed) {
            val entry = Entry(key, cmdId, bytes, hex, System.currentTimeMillis())
            latest[key] = entry
            _entries.value = latest.values.toList()
            if (live) _changes.tryEmit(Change(entry, prev?.hex))
        }

        applyMappings(key, bytes)

        // Door bitfield: cmdId 65, [.. 0C 38 <bits> ..] → byte index 4. Only on a new
        // frame: the box repeats it several times a second, and each put woke the bus.
        if (changed && cmdId == 65 && bytes.size > 4 && bytes[2] == 0x0C && bytes[3] == 0x38) {
            val b = bytes[4]
            busPublisher.put(K.DOORS, doorsOf(b))
        }
    }

    /**
     * The key one message is kept under: the MCU's command, and for the CAN box's channel the frame's own type,
     * so frames of different types don't overwrite each other. An RZC box sends `41 FD <type> …`; a Hiworld box
     * `41 5A A5 <len> <type> …` (the Hiworld Peugeot 508 of issue #122), its frames otherwise all one key the
     * radar frame took over several times a second, and the raw view could show none of them changing.
     */
    internal fun keyOf(cmdId: Int, bytes: List<Int>): String {
        val type = when {
            bytes.size >= 3 && bytes[1] == 0xfd -> bytes[2]
            bytes.size >= 5 && bytes[1] == 0x5a && bytes[2] == 0xa5 -> bytes[4]
            else -> return cmdId.toString()
        }
        return cmdId.toString() + "." + hex2(type)
    }

    internal fun doorsOf(b: Int) = DoorState(
        frontLeft = b and 0x80 != 0,
        frontRight = b and 0x40 != 0,
        rearLeft = b and 0x20 != 0,
        rearRight = b and 0x10 != 0,
        tailgate = b and 0x08 != 0,
        bonnet = b and 0x04 != 0
    )

    private fun hex2(b: Int): String = charArrayOf(HEX_DIGITS[(b shr 4) and 0x0F], HEX_DIGITS[b and 0x0F]).concatToString()
}
