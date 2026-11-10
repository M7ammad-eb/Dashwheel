package com.openauto.dash

import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import android.view.KeyEvent

/*
 * The head unit's own keys (steering wheel and panel) on the QF firmware
 * (ROCO K706), as its key service hands them to any app that listens: the
 * firmware's own keys (NAVI, MODE, PHONE… codes 290 and up) never reach an
 * app any other way. Worked out from the firmware and spoken at the binder
 * level ("util_service", android.qf.util.IUtilEventManager): the listener is
 * a plain Binder reading the key off the parcel, so Dashwheel never touches a
 * vendor class. The unit still does its own thing with each key: listening
 * only watches. Learned buttons ([SteeringWheelStore]) come through here
 * without root and without reading the CAN stream.
 */
object HeadUnitKeys {
    private const val TAG = "HeadUnitKeys"
    private const val SERVICE = "util_service"
    private const val MANAGER = "android.qf.util.IUtilEventManager"
    private const val LISTENER = "android.qf.util.IUtilEventListener"
    private const val TX_REGISTER = 2
    private const val TX_ON_KEY = 1
    /** The firmware's service restarts: listen again after this, waiting longer each time it isn't back. */
    private const val RETRY_MS = 2_000L
    private const val RETRY_MAX_MS = 60_000L

    /** The firmware's own keys start here; the ones below are Android's and reach the launcher anyway. */
    const val FIRST_VENDOR_KEY = 290

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var service: IBinder? = null
    private var retryMs = RETRY_MS

    private val listener = object : Binder() {
        init {
            attachInterface(null, LISTENER)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != TX_ON_KEY) return super.onTransact(code, data, reply, flags)
            // Never an exception back: it would break the service's loop over its listeners.
            val key = runCatching {
                data.enforceInterface(LISTENER)
                val keyCode = data.readInt()
                val event = if (data.readInt() != 0) {
                    data.readInt()
                    @Suppress("DEPRECATION")
                    data.readParcelable<KeyEvent>(KeyEvent::class.java.classLoader)
                } else {
                    null
                }
                keyCode to event
            }.getOrNull()
            // The service waits for the reply: answer at once, act on the main thread.
            reply?.writeNoException()
            if (key != null) main.post { onKey(key.first, key.second) }
            return true
        }
    }

    private val died = IBinder.DeathRecipient {
        service = null
        _listeningFlow.value = false
        retryLater()
    }

    private val _listeningFlow = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** [listening], as a flow: the accessibility service filters keys only where the unit's own service can't deliver them. */
    val listeningFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _listeningFlow

    fun start(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        register()
    }

    /** Whether the unit's key service is being listened to. */
    val listening: Boolean get() = service != null

    private fun register() {
        val binder = serviceBinder() ?: run { retryLater(); return }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(MANAGER)
            data.writeStrongBinder(listener)
            binder.transact(TX_REGISTER, data, reply, 0)
            reply.readException()
            binder.linkToDeath(died, 0)
            service = binder
            retryMs = RETRY_MS
            _listeningFlow.value = true
            Log.i(TAG, "listening to the unit's keys")
        } catch (e: Exception) {
            Log.w(TAG, "could not listen to the unit's keys", e)
            retryLater()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** The service isn't there or refused: once more later, each wait twice the last up to [RETRY_MAX_MS]. */
    private fun retryLater() {
        main.postDelayed({ register() }, retryMs)
        retryMs = (retryMs * 2).coerceAtMost(RETRY_MAX_MS)
    }

    private fun onKey(keyCode: Int, event: KeyEvent?) {
        val context = appContext ?: return
        UnitPlatform.markUsed(Cap.UNIT_KEYS)
        // The NAVI key may open the navigation app full screen: the user's doing, left so.
        if (keyCode >= FIRST_VENDOR_KEY) EmbeddedApp.userActed()
        // Android's own keys too: the unit keeps some to itself (its volume), and
        // one that also comes to the launcher runs once (SteeringWheelStore).
        SteeringWheelStore.onUnitKey(context, keyCode, event)
    }

    /** The key service's binder, from the ServiceManager ([SysProps]). */
    private fun serviceBinder(): IBinder? = SysProps.service(SERVICE).also { if (it == null) Log.w(TAG, "no key service") }

    /** The firmware's own keys by name (QFApi.getKeyCode), for the learning screen. */
    val nameRes: Map<Int, Int> = mapOf(
        290 to R.string.wheel_unit_power, 291 to R.string.wheel_unit_mute, 292 to R.string.wheel_unit_mode,
        293 to R.string.wheel_unit_volume_up, 294 to R.string.wheel_unit_volume_down, 298 to R.string.wheel_unit_navi,
        299 to R.string.wheel_unit_previous, 300 to R.string.wheel_unit_next, 301 to R.string.wheel_unit_phone,
        302 to R.string.wheel_unit_hang_up, 303 to R.string.wheel_unit_radio, 304 to R.string.wheel_unit_voice,
        307 to R.string.wheel_unit_play_pause, 315 to R.string.wheel_unit_backlight, 316 to R.string.wheel_unit_settings,
        321 to R.string.wheel_unit_apps, 325 to R.string.wheel_unit_knob
    )

    /** The numbered custom keys KEY1..KEY15 (codes 354..368), by their number; null for any other code. */
    fun customNumber(keyCode: Int): Int? = (keyCode - 353).takeIf { it in 1..15 }
}
