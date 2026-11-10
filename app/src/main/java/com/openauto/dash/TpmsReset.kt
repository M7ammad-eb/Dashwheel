package com.openauto.dash

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.SourceKind
import com.openauto.dash.vehicle.Vehicle
import com.openauto.dash.vehicle.ignitionFlow
import com.openauto.dash.vehicle.speedKmhNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The TPMS box (the USB receiver the head unit's TPMS app reads, a serial
 * chip like an OBD cable's) sometimes stops: the TPMS app says "device
 * error" and the tyres go quiet until the box is unplugged and plugged in
 * again. Dashwheel does that itself: while driving (tyre sensors send when
 * the wheels turn), when no tyre has been heard for [QUIET_MS], it switches
 * the box's USB port off and on again, as a hand on the plug would, once a
 * drive. Only with root (the port is switched in the system's files), only
 * on a unit whose tyres were heard once (a TPMS app without a box has none
 * to reset), and only when the box is the one USB serial device besides the
 * saved OBD cable: with several, it can't tell which, and touches none.
 */
internal object TpmsReset {
    private const val TAG = "TpmsReset"

    /** How long driving without a tyre heard before the box counts as stuck. */
    const val QUIET_MS = 3 * 60_000L

    /** Faster than this, tyre sensors send. */
    const val DRIVING_KMH = 20

    private const val CHECK_MS = 15_000L

    private const val PREFS = "tpms_reset"
    private const val KEY_HEARD = "tyres_heard"

    /** At most one reset in this long, when the unit doesn't say when the car is switched on. */
    private const val SPACING_MS = 30 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false

    /** Since when the car has been driving (elapsedRealtime), 0 when not. */
    private var drivingSince = 0L
    private var resetAt = 0L
    private var resetThisDrive = false

    /**
     * When a tyre last said anything real (a pressure, its sensor heard), [SystemClock.elapsedRealtime]; 0: not yet.
     * Looked up in the bus's tyres at each check and kept here, since the bus drops them after a minute of silence
     * ([TYRE_STALE_MS]) and this is what notices that silence.
     */
    private var heardAt = 0L

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        // A new drive may reset again.
        scope.launch { ignitionFlow().collect { if (it == true) resetThisDrive = false } }
        scope.launch {
            // The GPS speed is read by snapshot in [check]: it stays wanted for as long as the watch runs.
            Vehicle.bus.hold(K.GPS_SPEED)
            while (true) {
                delay(CHECK_MS)
                check(app)
            }
        }
    }

    /** Whether the GPS speed is held for this ([LocationFeed]): taken once there is a box to watch. */
    private var gps = false

    private suspend fun check(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        lookAtTyres()
        if (heardAt > 0 && !prefs.getBoolean(KEY_HEARD, false)) prefs.edit().putBoolean(KEY_HEARD, true).apply()
        // A TPMS app with no box behind it: nothing to plug in again.
        if (!prefs.getBoolean(KEY_HEARD, false)) return
        // The speed says when the wheels turn; kept up while the unit runs, as the drive log keeps it.
        if (!gps) gps = LocationFeed.acquire(context)
        val now = SystemClock.elapsedRealtime()
        val speed = speedKmhNow(K.GPS_SPEED)
        // A crawl in a queue keeps the count; only standing still (or no speed at all) starts it over,
        // or three minutes of driving in town never came together.
        drivingSince = when {
            speed == null || speed <= 0 -> 0L
            speed >= DRIVING_KMH -> drivingSince.takeIf { it > 0 } ?: now
            else -> drivingSince
        }
        if (!stuck(now, drivingSince, heardAt, resetThisDrive, resetAt, SPACING_MS) || DemoMode.isOn) return
        resetThisDrive = true
        resetAt = now
        val done = reset(context)
        DebugLog.note(context, "TPMS: no tyre heard for ${QUIET_MS / 1000} s while driving; box reset: $done")
        if (done) Toast.makeText(context, R.string.tpms_box_reset, Toast.LENGTH_LONG).show()
    }

    /** What the tyres on the bus say about when one was last heard; the demo's, made up, do not count. */
    private fun lookAtTyres() {
        // Only the TPMS box's own tyres ("tpms"): a USB receiver the driver chose says nothing about the box.
        val sample = Vehicle.bus.sample(K.TYRES, SourceKind.VEHICLE_APP).value ?: return
        if (sample.source.id != "tpms") return
        sample.value.values.filter { !it.noSignal && it.kPa > 0 }.maxOfOrNull { it.at }?.let { heardAt = maxOf(heardAt, it) }
    }

    /**
     * Whether the box looks stuck: driving for [QUIET_MS] or more, and no tyre
     * heard in that time (since [heardAt]), not reset already this drive nor
     * within [spacingMs]. Pure, so it is unit-tested.
     */
    fun stuck(now: Long, drivingSince: Long, heardAt: Long, resetThisDrive: Boolean, resetAt: Long, spacingMs: Long): Boolean =
        drivingSince > 0 && now - drivingSince >= QUIET_MS && now - heardAt >= QUIET_MS &&
            !resetThisDrive && (resetAt == 0L || now - resetAt >= spacingMs)

    /** The box's USB port off, then on: true when it was found and switched. */
    private suspend fun reset(context: Context): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        val obd = UsbObd.savedKey(context)
        val prober = UsbSerialProber.getDefaultProber()
        val serial = runCatching { manager.deviceList.values.toList() }.getOrDefault(emptyList())
            .filter { prober.probeDevice(it) != null && UsbObd.keyOf(it) != obd }
        val box = serial.singleOrNull()
        if (box == null) {
            Log.i(TAG, "${serial.size} USB serial devices besides the OBD cable: not sure which is the TPMS box")
            return false
        }
        val (bus, dev) = busAndDevice(box.deviceName) ?: return false
        return runCatching { DockShell.shell(context, portCycleCommand(bus, dev)) }
            .onFailure { Log.w(TAG, "could not switch the TPMS box's port", it) }
            .getOrNull()?.contains("reset ") == true
    }

    /** "/dev/bus/usb/001/004" → bus 1, device 4. */
    fun busAndDevice(deviceName: String): Pair<Int, Int>? {
        val parts = deviceName.trimEnd('/').split('/')
        val bus = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: return null
        val dev = parts.lastOrNull()?.toIntOrNull() ?: return null
        return bus to dev
    }

    /** The shell command that switches the port of USB device [dev] on bus [bus] off for two seconds, then on. */
    fun portCycleCommand(bus: Int, dev: Int): String =
        "for d in /sys/bus/usb/devices/*; do " +
            "[ \"$(cat \$d/busnum 2>/dev/null)\" = \"$bus\" ] && [ \"$(cat \$d/devnum 2>/dev/null)\" = \"$dev\" ] && " +
            "{ echo 0 > \$d/authorized && sleep 2 && echo 1 > \$d/authorized && echo \"reset \$d\"; }; done"
}
