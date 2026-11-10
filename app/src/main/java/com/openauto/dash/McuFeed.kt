package com.openauto.dash

import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * Everything the head unit's MCU tells Android on the QF firmware (ROCO K706),
 * as its MCU service hands it to any app that listens: the CAN box stream
 * (cmd 65, the same bytes the car app parses), the radio's RDS, the supply
 * voltage, the headlights and the handbrake wire. Worked out from the
 * firmware and spoken at the binder level ("mcu_service",
 * android.qf.mcu.IMcuManager), like [HeadUnitKeys]: no root, no logcat, no
 * vendor class. Each message is [cmd, data…], header and checksum taken off.
 * The service calls its listeners one after another and waits for each, so
 * the listener only queues the message for its own thread.
 */
object McuFeed {
    private const val TAG = "McuFeed"
    private const val SERVICE = "mcu_service"
    private const val MANAGER = "android.qf.mcu.IMcuManager"
    private const val LISTENER = "android.qf.mcu.IMcuListener"
    private const val TX_REGISTER = 2
    private const val TX_ON_INFO = 1
    /** RPC_SendMcuMsgData(byte cmd, byte[] data, int len): a request frame to the MCU, as is. */
    private const val TX_SEND = 29
    /** Head unit → MCU: bytes for the CAN box behind it, as the car app sends its questions. */
    internal const val CMD_TO_CAN = 0x40
    /** The firmware's service restarts: listen again after this, waiting longer each time it isn't back. */
    private const val RETRY_MS = 2_000L
    private const val RETRY_MAX_MS = 60_000L

    // Commands from the MCU (QF_Framework's McuConstant).
    internal const val CMD_HEADLIGHT = 34
    internal const val CMD_HANDBRAKE = 35
    internal const val CMD_BATTERY = 41
    internal const val CMD_CAN = 65
    internal const val CMD_TUNER_PRESETS = 177
    internal const val CMD_RDS_FLAGS = 180
    internal const val CMD_RDS_PS = 182
    internal const val CMD_RDS_TEXT = 183

    private val main = Handler(Looper.getMainLooper())
    private val worker: Handler by lazy { Handler(HandlerThread("mcu-feed").apply { start() }.looper) }
    private var appContext: Context? = null
    private var service: IBinder? = null
    private var retryMs = RETRY_MS

    private val _listening = MutableStateFlow(false)
    /** The MCU service is being listened to: [McuReader] needs no root logcat then. */
    val listening: StateFlow<Boolean> = _listening

    private val listener = object : Binder() {
        init {
            attachInterface(null, LISTENER)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != TX_ON_INFO) return super.onTransact(code, data, reply, flags)
            // Never an exception back: it would break the service's loop over its listeners.
            val message = runCatching {
                data.enforceInterface(LISTENER)
                data.createByteArray()
            }.getOrNull()
            reply?.writeNoException()
            if (message != null && message.isNotEmpty()) worker.post { onMessage(message) }
            return true
        }
    }

    private val died = IBinder.DeathRecipient {
        service = null
        _listening.value = false
        McuReader.mcuStopped()
        retryLater()
    }

    fun start(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        PsaWarnings.setContext(context)
        register()
    }

    private fun register() {
        val app = appContext ?: return
        val binder = serviceBinder() ?: run { retryLater(); return }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(MANAGER)
            data.writeStrongBinder(listener)
            data.writeString(app.packageName)
            binder.transact(TX_REGISTER, data, reply, 0)
            reply.readException()
            binder.linkToDeath(died, 0)
            service = binder
            retryMs = RETRY_MS
            _listening.value = true
            Log.i(TAG, "listening to the MCU")
        } catch (e: Exception) {
            Log.w(TAG, "could not listen to the MCU", e)
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

    /** One message from the MCU, on the feed's own thread. */
    private fun onMessage(m: ByteArray) {
        val cmd = m[0].toInt() and 0xFF
        UnitPlatform.markUsed(Cap.VEHICLE_BUS)
        McuReader.heardMcu()
        McuReader.feed(m)
        when (cmd) {
            CMD_CAN -> {
                PsaTripComputer.feed(m, 1)
                CarBoxKeys.feed(m, 1)
            }
            // 0 is the lights on: the framework's own polarity.
            CMD_HEADLIGHT -> if (m.size > 1) UnitSignals.mcuHeadlights(m[1].toInt() == 0)
            CMD_HANDBRAKE -> if (m.size > 1) UnitSignals.mcuHandbrake(m[1].toInt() == 1)
            CMD_BATTERY -> if (m.size > 1) UnitSignals.mcuVolts((m[1].toInt() and 0xFF) / 10.0)
            CMD_TUNER_PRESETS -> if (m.size > 4) {
                val band = m[1].toInt() and 0xFF
                val freq = ((m[3].toInt() and 0xFF) shl 8) or (m[4].toInt() and 0xFF)
                HeadUnitMedia.mcuTuner(freq, band)
            }
            CMD_RDS_FLAGS -> if (m.size > 1 && m[1].toInt() and 0x04 == 0) HeadUnitMedia.mcuRds(name = null, text = null, clear = true)
            CMD_RDS_PS -> if (m.size > 8) rdsName(m.copyOfRange(1, 9))?.let { HeadUnitMedia.mcuRds(name = it, text = null) }
            // The radio app leaves the last two bytes out.
            CMD_RDS_TEXT -> if (m.size > 3) rdsText(m.copyOfRange(1, m.size - 2))?.let { HeadUnitMedia.mcuRds(name = null, text = it) }
        }
    }

    /**
     * Sends [data] to the MCU under [cmd]; false when the service isn't
     * reached. On these units the CAN box sits behind the MCU: the car app's
     * own questions to it go out this way (cmd [CMD_TO_CAN]), and its CAN
     * service drops what an app asks it to write (checked on the car 2026-10-12).
     */
    fun send(cmd: Int, data: ByteArray): Boolean {
        val binder = service ?: serviceBinder() ?: return false
        val parcel = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            parcel.writeInterfaceToken(MANAGER)
            parcel.writeByte(cmd.toByte())
            parcel.writeByteArray(data)
            parcel.writeInt(data.size)
            binder.transact(TX_SEND, parcel, reply, 0)
            reply.readException()
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not send to the MCU", e)
            false
        } finally {
            parcel.recycle()
            reply.recycle()
        }
    }

    /** The station's 8-letter name, as the radio app takes it: none when blank or holding its fillers. */
    internal fun rdsName(bytes: ByteArray): String? {
        val s = String(bytes, Charsets.ISO_8859_1)
        if (',' in s || '#' in s) return null
        return s.filter { it >= ' ' }.trim().takeIf { it.isNotEmpty() }
    }

    /** Radio text, controls taken out and spaces squeezed. */
    internal fun rdsText(bytes: ByteArray): String? =
        String(bytes, Charsets.ISO_8859_1).map { if (it < ' ') ' ' else it }.joinToString("")
            .replace(Regex(" {2,}"), " ").trim().takeIf { it.length >= 2 }

    /** The MCU service's binder, from the ServiceManager ([SysProps]). */
    private fun serviceBinder(): IBinder? = SysProps.service(SERVICE).also { if (it == null) Log.w(TAG, "no MCU service") }
}
