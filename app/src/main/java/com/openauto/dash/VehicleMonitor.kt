package com.openauto.dash

import android.os.SystemClock
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Key
import com.openauto.dash.vehicle.Vehicle
import com.openauto.dash.vehicle.gaugesNow
import com.openauto.dash.vehicle.holdAll
import com.openauto.dash.vehicle.ignitionFlow
import com.openauto.dash.vehicle.ignitionNow
import kotlin.math.roundToInt

/**
 * Keeps the car link working for the whole process rather than for one
 * dashboard screen: the OBD poll with the AI mechanic and car-care watchers,
 * and the adapter reconnect. Tied to the screen, they all stopped whenever it
 * was rebuilt (a language change, or Android reclaiming the launcher behind a
 * full-screen app) until the next resume.
 */
internal object VehicleMonitor {
    /**
     * The pause between two OBD polls: room for a fault scan to get the adapter.
     * At 500 ms, with a poll's own round trips, the revs moved once in 1.3 s on
     * the second screen while the car's own counter climbed.
     */
    private const val POLL_MS = 100L
    private const val BATTERY_LOOK_MS = 500L

    /**
     * What the poll keeps reading whenever it runs, besides the speed (always asked): what the watchers
     * that run off it need, whether or not a tile shows it. The AI mechanic's live watch (revs, coolant,
     * volts) and its scan's readings (intake too), car care's eco, filter and clutch rules (revs, load,
     * coolant) and its fuel range. The alerts' coolant and the battery judge hold their own keys.
     */
    internal val POLL_FLOOR: List<Key<Double>> = listOf(
        K.SPEED, K.RPM, K.COOLANT_TEMP, K.INTAKE_TEMP, K.ENGINE_LOAD, K.FUEL_LEVEL, K.BATTERY_VOLTS
    )
    private const val FIRST_RETRY_MS = 5_000L
    private const val MAX_RETRY_MS = 60_000L
    /** The paired devices load just after Bluetooth says it's on. */
    private const val BLUETOOTH_SETTLE_MS = 2_000L
    /** The unit's Bluetooth takes a moment to hand the call audio back. */
    private const val CALL_SETTLE_MS = 2_000L
    private const val USB_SETTLE_MS = 1_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private var appContext: Context? = null

    /** The launcher is in front; see [redials]. */
    private val foreground = MutableStateFlow(false)

    /** When CarPlay or Android Auto last connected (elapsed realtime); null while it isn't. */
    @Volatile private var projectionSince: Long? = null

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        ContextCompat.registerReceiver(
            context.applicationContext, bluetoothState,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            context.applicationContext, usbState,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        DeepObdSource.init(context)
        val listener = context.applicationContext
        scope.launch { DeepObdSource.run(listener) }
        scope.launch { pollWhileConnected() }
        scope.launch { reconnectWhileWanted() }
        scope.launch { redialAfterCalls() }
        scope.launch { followPhoneOffer() }
        scope.launch { UnitSignals.projection.collect { on -> projectionSince = if (on) SystemClock.elapsedRealtime() else null } }
        // The bar's alerts, judged at every reading whatever is on screen (the demo's too).
        // The application context, not [context]: this runs for good and must not keep the first screen alive.
        val app = context.applicationContext
        // Collecting the coolant is what keeps the poll reading it; with no reading there is no alert.
        scope.launch {
            combine(Vehicle.bus.value(K.COOLANT_TEMP).map { it?.roundToInt() }.distinctUntilChanged(), BatteryWatch.state) { coolantC, battery ->
                coolantC to battery
            }.collect { (coolantC, battery) -> AlertCenter.evaluate(strings(app), coolantC, battery) }
        }
        scope.launch(Dispatchers.Default) { feedBatteryWatch() }
    }

    /**
     * The battery's judge looks at the car's revs and volts on the bus twice a second. It holds both keys
     * for as long as the monitor lives, so the OBD poll keeps reading them (the unit's own volts come
     * without asking). Volts that are an adapter's own pin ([Quality.DEGRADED]) are not trusted.
     */
    private suspend fun feedBatteryWatch() {
        val hold = Vehicle.bus.holdAll(listOf(K.RPM, K.BATTERY_VOLTS))
        try {
            // Looked at only while the bus has revs or volts to judge: parked
            // with no adapter, the judge was woken twice a second for nothing.
            combine(Vehicle.bus.value(K.RPM), Vehicle.bus.value(K.BATTERY_VOLTS)) { rpm, volts -> rpm != null || volts != null }
                .distinctUntilChanged()
                .collectLatest { any ->
                    if (!any) {
                        // Told once that the readings are gone: the window empties, as at a gap.
                        BatteryWatch.feed(null, null, false, SystemClock.elapsedRealtime())
                        return@collectLatest
                    }
                    while (true) {
                        delay(BATTERY_LOOK_MS)
                        val g = gaugesNow(listOf(K.RPM, K.BATTERY_VOLTS))
                        // A clock GPS cannot set mid-drive: the judge measures its 30 s window as differences.
                        BatteryWatch.feed(g.rpm, g.voltage, g.voltsTrusted, SystemClock.elapsedRealtime())
                    }
                }
        } finally {
            hold.release()
        }
    }

    // The chips' text in the launcher's language, wrapped again only when that changes.
    private var stringsFor: Pair<AppLanguage, Context>? = null

    private fun strings(app: Context): Context {
        val language = AppLanguage.current(app)
        stringsFor?.takeIf { it.first == language }?.let { return it.second }
        return AppLanguage.wrap(app).also { stringsFor = language to it }
    }

    /**
     * Bluetooth back on (the unit switches it off to sleep): the adapter is
     * dialled then, not whenever the retries, slowed down by the tries made
     * while it was off, next come round.
     */
    private val bluetoothState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON) return
            scope.launch {
                delay(BLUETOOTH_SETTLE_MS)
                if (redials(foreground.value || secondScreen.value, ignitionNow())) connectSaved()
            }
        }
    }

    /** A USB device plugged in or pulled out: maybe the adapter's cable. */
    private val usbState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            usbChanged()
        }
    }

    /**
     * The USB adapter plugged in or pulled out (Automatic): a link the other
     * way is closed and the adapter dialled the way that now comes first.
     * Plugged in, it is dialled at once rather than at the next retry.
     */
    fun usbChanged() {
        if (!started) return
        scope.launch(Dispatchers.IO) {
            // The device is listed a moment after the broadcast, and the attach dialog saves it.
            delay(USB_SETTLE_MS)
            ObdBluetoothManager.refreshAdapter()
            if (ObdBluetoothManager.route() == ObdRoute.AUTO) {
                val state = ObdBluetoothManager.connectionState.value
                val wrongSide = state == ObdConnectionState.CONNECTED && ObdBluetoothManager.viaUsb.value != ObdBluetoothManager.usesUsb()
                if (wrongSide) ObdBluetoothManager.disconnect()
            }
            if (redials(foreground.value || secondScreen.value, ignitionNow())) connectSaved()
        }
    }

    /** The second screen shows the car's readings: they are wanted even with an app in front. */
    private val secondScreen = MutableStateFlow(false)

    fun setForeground(inFront: Boolean) {
        foreground.value = inFront
    }

    fun setSecondScreenShowing(showing: Boolean) {
        secondScreen.value = showing
    }

    /**
     * Dials the saved adapter when the link is down and the Bluetooth
     * permission is held; nothing happens without a saved adapter, or while
     * the car is switched off. The driver's own tap on OBD still dials then.
     */
    fun connectSaved() {
        val context = appContext ?: return
        // Deep OBD holds the adapter and reports by itself: nothing to dial.
        if (DeepObdSource.owns()) return
        // The driver turned connecting by itself off: only their tap on OBD dials.
        if (!ObdBluetoothManager.autoConnect.value) return
        // CarPlay or Android Auto starting: it needs the unit's Bluetooth to itself for a moment.
        if (projectionStarting(UnitSignals.projectionOnScreen.value, UnitSignals.projection.value, projectionSince, SystemClock.elapsedRealtime())) return
        if (ignitionNow() == false) return
        if (!ObdBluetoothManager.connectionState.value.isIdle) return
        // Which way to dial asks the USB service (and the phone's offer): off the main thread, like the dial itself.
        scope.launch(Dispatchers.IO) { dialSaved(context) }
    }

    private suspend fun dialSaved(context: Context) {
        // On a cable: nothing to pair, and Android's USB access was given when it was plugged in or on Connect.
        // Through the phone: its companion holds the adapter, nothing to pair or allow here.
        if (ObdBluetoothManager.usesUsb() || ObdBluetoothManager.usesPhone()) {
            ObdBluetoothManager.connect(ObdBluetoothManager.savedDeviceAddress().orEmpty())
            return
        }
        val saved = ObdBluetoothManager.savedDeviceAddress() ?: return
        val missingPerms = requiredBluetoothPermissions().any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (!missingPerms) ObdBluetoothManager.connect(saved)
    }

    /**
     * A call through the unit keeps its Bluetooth from reaching the adapter;
     * once it ends the adapter is dialled straight away, not when the retries,
     * slowed down during a long call, next come round.
     */
    private suspend fun redialAfterCalls() {
        var calling = false
        combine(UnitPhone.call, PhoneLink.call) { _, _ -> ObdBluetoothManager.inCall() }
            .distinctUntilChanged()
            .collect { now ->
                val ended = calling && !now
                calling = now
                if (!ended) return@collect
                delay(CALL_SETTLE_MS)
                if (redials(foreground.value || secondScreen.value, ignitionNow())) connectSaved()
            }
    }

    /**
     * The phone starts or stops offering its adapter (Automatic): a link on
     * the wrong side is closed and the adapter dialled the right way, since
     * the adapter takes one connection at a time.
     */
    private suspend fun followPhoneOffer() {
        ObdBluetoothManager.setContext(appContext ?: return)
        // Also looked at when a link comes up: an attempt on the unit's side that was under way
        // when the phone offered the adapter would otherwise end on the wrong side and stay there,
        // both radios on one adapter, until the next drop.
        var offered = PhoneObd.offer.value != null
        combine(
            PhoneObd.offer.map { it != null }.distinctUntilChanged(),
            ObdBluetoothManager.connectionState.map { it == ObdConnectionState.CONNECTED }.distinctUntilChanged()
        ) { o, c -> o to c }
            .distinctUntilChanged()
            .collect { (nowOffered, connected) ->
                val offerChanged = nowOffered != offered
                offered = nowOffered
                if (ObdBluetoothManager.route() != ObdRoute.AUTO) return@collect
                val wrongSide = connected && withContext(Dispatchers.IO) { ObdBluetoothManager.viaPhone.value != ObdBluetoothManager.usesPhone() }
                if (!offerChanged && !wrongSide) return@collect
                if (wrongSide) ObdBluetoothManager.disconnect()
                if (redials(foreground.value || secondScreen.value, ignitionNow())) connectSaved()
            }
    }

    private suspend fun pollWhileConnected() {
        combine(ObdBluetoothManager.connectionState, DemoMode.active) { connection, demo ->
            // The demo feeds the readings itself and must not scan, speak or save anything.
            connection == ObdConnectionState.CONNECTED && !demo
        }
            .distinctUntilChanged()
            .collectLatest { live ->
                if (!live) return@collectLatest
                // The watchers below read the bus by snapshot: their keys stay wanted while they run.
                val floor = Vehicle.bus.holdAll(POLL_FLOOR)
                try {
                    coroutineScope {
                        // The AI mechanic checks for fault codes by itself once the first
                        // readings are in (it only speaks about codes it hasn't heard before).
                        launch {
                            delay(3000)
                            if (!DeepObdSource.owns()) AiMechanic.autoScan()
                        }
                        // Ten times a second: the rules and the saves run off the main thread.
                        withContext(Dispatchers.Default) {
                            while (true) {
                                ObdBluetoothManager.poll()
                                val readings = gaugesNow(POLL_FLOOR).toObdData()
                                AiMechanic.watch(readings)
                                CarCare.watch(readings)
                                SensorWatch.watch(readings)
                                delay(POLL_MS)
                            }
                        }
                    }
                } finally {
                    floor.release()
                }
            }
    }

    /**
     * Redials a missing adapter while it is wanted ([redials], the second
     * screen showing the car's readings counting as in front): after 5 s,
     * then less and less often up to once a minute, so an adapter that is
     * unplugged or asleep does not keep the Bluetooth radio calling it.
     * Wanted again, or a link that was up and dropped, starts over at 5 s.
     */
    private suspend fun reconnectWhileWanted() {
        combine(foreground, secondScreen, ignitionFlow()) { inFront, cluster, ignition -> redials(inFront || cluster, ignition) }
            .distinctUntilChanged()
            .collectLatest { wanted ->
                if (!wanted) {
                    // Off as far as the broadcasts said: the unit itself is asked now and then.
                    while (true) {
                        delay(MAX_RETRY_MS)
                        CarPower.refresh()
                    }
                }
                var wait = FIRST_RETRY_MS
                while (true) {
                    if (ObdBluetoothManager.connectionState.value == ObdConnectionState.CONNECTED) {
                        ObdBluetoothManager.connectionState.first { it != ObdConnectionState.CONNECTED }
                        wait = FIRST_RETRY_MS
                    }
                    CarPower.refresh()
                    connectSaved()
                    delay(wait)
                    wait = (wait * 2).coerceAtMost(MAX_RETRY_MS)
                }
            }
    }
}

/**
 * Whether a missing adapter is redialled by itself. With the ignition on it
 * is, whichever app is in front: the link feeds the alerts, the drive log and
 * the volume all drive long, and one lost behind CarPlay or a call screen
 * used to stay lost until the launcher came back. With the ignition off it
 * isn't: the link was closed on purpose, and one opened then goes stale as the
 * unit sleeps while the adapter still holds it, refusing the next start's.
 * On a unit that doesn't say ([ignition] null), only with the launcher [inFront].
 */
internal fun redials(inFront: Boolean, ignition: Boolean?): Boolean = ignition ?: inFront

/** How long after CarPlay or Android Auto connects the adapter is left alone: the phone's link settles. */
internal const val PROJECTION_SETTLE_MS = 45_000L

/**
 * Whether CarPlay or Android Auto is starting: its app is on screen waiting
 * for the phone, or connected less than [PROJECTION_SETTLE_MS] ago ([since]).
 * The unit's one Bluetooth chip carries the phone's handshake then, and an
 * adapter dialled meanwhile could make it fail.
 */
internal fun projectionStarting(onScreen: Boolean, connected: Boolean, since: Long?, now: Long): Boolean =
    (onScreen && !connected) || (connected && since != null && now - since < PROJECTION_SETTLE_MS)
