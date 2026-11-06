package com.openauto.dash.unit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.openauto.dash.Cap
import com.openauto.dash.UnitPlatform
import com.openauto.dash.UnitSignals
import com.openauto.dash.VehicleBusSource
import com.openauto.dash.unitBusVolts
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Publisher
import com.openauto.unitproto.nwd.NwdProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The NWD firmware's car data, as the open K4811 launcher reads it ([NwdProtocol]; unconfirmed on a unit): the
 * battery voltage asked for every 10 s and answered by broadcast, and from Settings.System read once a second and
 * put each time (the bus takes an unchanged value for no change): the headlights from the MCU's input bits, and the
 * outside temperature the CAN app stores as its status-bar text. A unit that answers none stays a silent source.
 */
internal object NwdBus : VehicleBusSource {
    private const val TAG = "NwdBus"
    private const val POLL_MS = 1_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _listening = MutableStateFlow(false)
    override val listening: StateFlow<Boolean> = _listening

    private var out: Publisher? = null
    private var job: Job? = null

    override fun start(context: Context, publisher: Publisher, raw: (String, String) -> Unit) {
        if (out != null) return
        out = publisher
        val app = context.applicationContext
        ContextCompat.registerReceiver(app, receiver, IntentFilter(NwdProtocol.ACTION_VOLTAGE).apply { addAction(NwdProtocol.ACTION_STATUSBAR_STRING) }, ContextCompat.RECEIVER_EXPORTED)
        _listening.value = true
        job = scope.launch {
            var seenLights = false
            var seenTemp = false
            var tick = 0L
            while (isActive) {
                if (tick++ % (NwdProtocol.QUERY_PERIOD_MS / POLL_MS) == 0L) {
                    runCatching { app.sendBroadcast(Intent(NwdProtocol.ACTION_QUERY_VOLTAGE)) }
                }
                val inputs = runCatching { Settings.System.getInt(app.contentResolver, NwdProtocol.SETTING_MCU_INPUTS) }.getOrNull()
                if (inputs != null) {
                    if (!seenLights) {
                        seenLights = true
                        Log.i(TAG, "the MCU's inputs are in Settings.System: $inputs")
                        UnitPlatform.markUsed(Cap.VEHICLE_BUS)
                        UnitPlatform.markUsed(Cap.HEADLIGHTS)
                    }
                    publisher.put(K.HEADLIGHTS, NwdProtocol.headlightsOn(inputs))
                }
                val tempText = runCatching { Settings.System.getString(app.contentResolver, NwdProtocol.SETTING_OUT_TEMP) }.getOrNull()
                val tempC = UnitSignals.parseOutsideTemp(tempText)
                if (tempC != null) {
                    if (!seenTemp) {
                        seenTemp = true
                        Log.i(TAG, "the CAN app's outside temperature is in Settings.System: $tempText")
                        UnitPlatform.markUsed(Cap.VEHICLE_BUS)
                        UnitPlatform.markUsed(Cap.OUTSIDE_TEMP)
                    }
                    publisher.put(K.OUTSIDE_TEMP, tempC)
                }
                delay(POLL_MS)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        _listening.value = false
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val o = out ?: return
            if (intent.action == NwdProtocol.ACTION_STATUSBAR_STRING) {
                // Sent on change only ("7℃"), confirmed on a unit's recording 2026-11-06; the Settings key was absent there.
                val c = UnitSignals.parseOutsideTemp(intent.getStringExtra("extra_string")) ?: return
                UnitPlatform.markUsed(Cap.VEHICLE_BUS)
                UnitPlatform.markUsed(Cap.OUTSIDE_TEMP)
                o.put(K.OUTSIDE_TEMP, c)
                return
            }
            if (intent.action != NwdProtocol.ACTION_VOLTAGE || !intent.hasExtra(NwdProtocol.EXTRA_VOLTAGE)) return
            val volts = NwdProtocol.volts(intent.getIntExtra(NwdProtocol.EXTRA_VOLTAGE, 0)) ?: return
            UnitPlatform.markUsed(Cap.VEHICLE_BUS)
            UnitPlatform.markUsed(Cap.VOLTS)
            unitBusVolts(o, volts)
        }
    }
}
