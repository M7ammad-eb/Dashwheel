package com.openauto.dash.unit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.openauto.dash.CameraControl
import com.openauto.dash.Cap
import com.openauto.dash.CapClaim
import com.openauto.dash.DockShell
import com.openauto.dash.IgnitionEvent
import com.openauto.dash.IgnitionSource
import com.openauto.dash.KeySource
import com.openauto.dash.LauncherTakeover
import com.openauto.dash.McuReader
import com.openauto.dash.MediaBridge
import com.openauto.dash.PhoneBridge
import com.openauto.dash.PopupTakeover
import com.openauto.dash.PrivilegedShell
import com.openauto.dash.SignalCatalogue
import com.openauto.dash.SleepSurvival
import com.openauto.dash.SysProps
import com.openauto.dash.UnitAdapter
import com.openauto.dash.UnitCaps
import com.openauto.dash.UnitPlatform
import com.openauto.dash.UnitSignals
import com.openauto.dash.VehicleBusSource
import com.openauto.dash.VolumeControl
import com.openauto.dash.unitBusOutsideTemp
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Publisher
import com.openauto.unitproto.AccBroadcasts
import com.openauto.unitproto.KeyBroadcasts
import com.openauto.unitproto.UnitFamily
import com.openauto.unitproto.hcn.HcnProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * The HCN firmware (Junsun and other units with the CarInfo app), as read
 * from that app for issue #96 ([HcnProtocol]): the doors it mirrors in
 * system properties, the ignition and the reverse wire its MCU service
 * broadcasts, the outside temperature the app broadcasts. Unconfirmed on a
 * unit. The rest is plain Android and the vendor broadcasts.
 */
internal class HcnAdapter(context: Context) : UnitAdapter {
    private val hasCarInfo = UnitPlatform.hasPackage(context.applicationContext, HcnProtocol.PACKAGE_CAN)

    override val family = UnitFamily.HCN
    override val ignition: IgnitionSource = HcnIgnition
    override val bus: VehicleBusSource = HcnBus
    override val keys: KeySource = KeyBroadcastSource
    override val phone: PhoneBridge? = null
    override val media: MediaBridge? = null
    override val volume: VolumeControl? = null
    override val launcher: LauncherTakeover? = null
    override val popups: PopupTakeover? = null
    override val survival: SleepSurvival? = null
    override val camera: CameraControl? = null

    override val caps: UnitCaps = UnitCaps(
        buildList {
            add(CapClaim(Cap.IGNITION))
            add(CapClaim(Cap.VEHICLE_BUS))
            add(CapClaim(Cap.REVERSE))
            add(CapClaim(Cap.UNIT_KEYS))
            if (hasCarInfo) { add(CapClaim(Cap.DOORS)); add(CapClaim(Cap.OUTSIDE_TEMP)) }
        }
    )

    override val catalogue = SignalCatalogue(
        broadcasts = HcnProtocol.BROADCASTS + AccBroadcasts.ALL + KeyBroadcasts.ACTIONS,
        propPrefixes = setOf(HcnProtocol.PROP_CERTIFICATE),
        // The CarInfo app writes the speed and gear there on units whose firmware is "certified".
        settingsSystem = true
    )

    override val vendorPackages: Set<String> = HcnProtocol.PACKAGES
}

/** The MCU service's ACC broadcast, and the vendor ones every family gets; nothing to read in between. */
internal object HcnIgnition : IgnitionSource {
    override fun read(): Boolean? = null
    override val actions: Set<String> = AccBroadcasts.ALL + HcnProtocol.ACTION_ACC
    override fun decode(intent: Intent): IgnitionEvent? = when {
        intent.action == HcnProtocol.ACTION_ACC -> {
            if (!intent.hasExtra(HcnProtocol.EXTRA_ACC)) null
            else if (intent.getBooleanExtra(HcnProtocol.EXTRA_ACC, false)) IgnitionEvent.ON else IgnitionEvent.OFF
        }
        else -> AccBroadcastIgnition.decode(intent)
    }
}

/**
 * The doors from the properties the CarInfo app sets on every door frame,
 * read a few times a second and put each time (the bus takes an unchanged
 * value for no change); the reverse wire from the MCU's broadcast; the
 * outside temperature from the app's. The properties stay put while nothing
 * happens, so the source is never counted as gone for being quiet.
 */
internal object HcnBus : VehicleBusSource {
    private const val TAG = "HcnBus"
    private const val POLL_MS = 300L
    /** A shell round trip a poll is enough: a door is not opened three times a second. */
    private const val SHELL_POLL_MS = 1_000L
    /** The shell answering nothing: every 5 s after a minute of it, every 30 s after five. */
    private const val SHELL_BACK_OFF_AFTER_MS = 60_000L
    private const val SHELL_BACK_OFF_MS = 5_000L
    private const val SHELL_SLOW_AFTER_MS = 5 * 60_000L
    private const val SHELL_SLOW_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _listening = MutableStateFlow(false)
    override val listening: StateFlow<Boolean> = _listening
    override val silenceMs: Long = 24 * 3_600_000L

    private var out: Publisher? = null
    private var job: Job? = null
    private var app: Context? = null

    override fun start(context: Context, publisher: Publisher, raw: (String, String) -> Unit) {
        if (out != null) return
        out = publisher
        val filter = IntentFilter().apply {
            addAction(HcnProtocol.ACTION_REVERSE)
            addAction(HcnProtocol.ACTION_OUTSIDE_TEMP)
        }
        val app = context.applicationContext
        this.app = app
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        _listening.value = true
        job = scope.launch {
            var seen = false
            var path: String? = null
            // When the shell started answering nothing: with no CarInfo app it never will, and a fork a second for the life of
            // the process is not worth it, so the shell is asked less and less often until a value shows.
            var shellQuietSince = 0L
            while (isActive) {
                var doors = HcnProtocol.doors(SysProps::getOrNull)
                var viaShell = false
                var shellAsked = false
                if (doors == null && PrivilegedShell.access.value.shell) {
                    val props = shellProps(app)
                    doors = HcnProtocol.doors { props[it]?.takeIf(String::isNotBlank) }
                    viaShell = doors != null
                    shellAsked = true
                }
                val now = if (doors == null) "neither" else if (viaShell) "shell" else "direct"
                if (now != path) {
                    path = now
                    Log.i(TAG, "door properties: $now (direct read, else the privileged shell)")
                }
                if (doors != null) {
                    if (!seen) {
                        seen = true
                        Log.i(TAG, "the CarInfo app mirrors the doors: $doors")
                        UnitPlatform.markUsed(Cap.VEHICLE_BUS)
                        UnitPlatform.markUsed(Cap.DOORS)
                    }
                    publisher.put(K.DOORS, McuReader.DoorState(doors[0], doors[1], doors[2], doors[3], doors[4]))
                }
                val wait = when {
                    doors != null -> { shellQuietSince = 0L; if (viaShell) SHELL_POLL_MS else POLL_MS }
                    !shellAsked -> POLL_MS
                    else -> {
                        val now = SystemClock.elapsedRealtime()
                        if (shellQuietSince == 0L) shellQuietSince = now
                        val quiet = now - shellQuietSince
                        if (quiet < SHELL_BACK_OFF_AFTER_MS) SHELL_POLL_MS else if (quiet < SHELL_SLOW_AFTER_MS) SHELL_BACK_OFF_MS else SHELL_SLOW_MS
                    }
                }
                delay(wait)
            }
        }
    }

    /**
     * The door properties through the privileged shell, one `getprop` a poll: on Android 12 and later a vendor
     * property SELinux keeps from apps reads as unset in the app itself, while root, Shizuku and ADB see it.
     */
    private suspend fun shellProps(context: Context): Map<String, String> =
        runCatching { DockShell.shell(context, "getprop | grep -F persist.vendor.cem.cardoor") }
            .getOrDefault("").lines()
            .mapNotNull { line -> PROP_LINE.find(line)?.let { it.groupValues[1] to it.groupValues[2] } }
            .toMap()

    private val PROP_LINE = Regex("""^\[([^\]]+)]: \[(.*)]$""")

    override fun stop() {
        job?.cancel()
        job = null
        // Cleared so a later start registers and polls again rather than returning at once.
        app?.let { runCatching { it.unregisterReceiver(receiver) } }
        app = null
        out = null
        _listening.value = false
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val o = out ?: return
            when (intent.action) {
                HcnProtocol.ACTION_REVERSE -> if (intent.hasExtra(HcnProtocol.EXTRA_REVERSE)) {
                    UnitPlatform.markUsed(Cap.REVERSE)
                    o.put(K.REVERSE, intent.getBooleanExtra(HcnProtocol.EXTRA_REVERSE, false))
                }
                HcnProtocol.ACTION_OUTSIDE_TEMP -> {
                    val c = UnitSignals.parseOutsideTemp(intent.getStringExtra(HcnProtocol.EXTRA_OUTSIDE_TEMP))
                    if (c != null) UnitPlatform.markUsed(Cap.OUTSIDE_TEMP)
                    unitBusOutsideTemp(o, c)
                }
            }
        }
    }
}
