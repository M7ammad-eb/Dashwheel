package com.openauto.dash.unit.fyt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import com.openauto.unitproto.fyt.FytProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The FYT firmware's toolkit service (com.syu.ms/app.ToolkitService), spoken
 * at the binder level: Dashwheel binds it, asks it for a module's binder
 * ([module]), and talks to the module with plain parcels ([FytModule]),
 * never touching a vendor class. Updates come back through [FytCallback],
 * a plain Binder answering the firmware's callback descriptor.
 *
 * The connection comes and goes with the firmware's process: observers are
 * told each time ([onConnection]) and register their modules again.
 */
internal class FytToolkit(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    /** Updates are read off the binder thread and handled here, in order. */
    val feed: Handler by lazy { Handler(HandlerThread("fyt-feed").apply { start() }.looper) }

    @Volatile private var toolkit: IBinder? = null
    private var binding = false

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val observers = CopyOnWriteArraySet<(Boolean) -> Unit>()

    /** Told on the feed thread whenever the service comes ([true]) or goes; at once when it is already there. */
    fun onConnection(observer: (Boolean) -> Unit) {
        observers += observer
        if (toolkit != null) feed.post { observer(true) }
    }

    /** Binds the service, once; a failed bind is tried again after [RETRY_MS]. */
    @Synchronized
    fun connect() {
        if (binding || toolkit != null) return
        binding = true
        val intent = Intent(FytProtocol.ACTION).setClassName(FytProtocol.PACKAGE, FytProtocol.SERVICE_CLASS)
        val ok = runCatching { app.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            .onFailure { Log.w(TAG, "can't bind the FYT toolkit", it) }
            .getOrDefault(false)
        if (!ok) {
            binding = false
            runCatching { app.unbindService(connection) }
            Log.w(TAG, "the FYT toolkit service is not there; trying again in ${RETRY_MS / 1000} s")
            main.postDelayed({ connect() }, RETRY_MS)
        } else {
            main.postDelayed(watchdog, BIND_WAIT_MS)
        }
    }

    /**
     * At boot the firmware's server comes up with Dashwheel, and a bind it
     * accepted may never be answered: without an answer in [BIND_WAIT_MS] the
     * bind is dropped and made again.
     */
    private val watchdog: Runnable = Runnable {
        synchronized(this) {
            if (toolkit != null || !binding) return@Runnable
            Log.w(TAG, "the FYT toolkit didn't answer in ${BIND_WAIT_MS / 1000} s; binding again")
            runCatching { app.unbindService(connection) }
            binding = false
        }
        connect()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) { Log.w(TAG, "the FYT toolkit answered with no binder; the watchdog binds again"); return }
            main.removeCallbacks(watchdog)
            toolkit = service
            runCatching { service.linkToDeath({ lost("died") }, 0) }
            _connected.value = true
            Log.i(TAG, "FYT toolkit bound")
            feed.post { observers.forEach { it(true) } }
        }

        override fun onServiceDisconnected(name: ComponentName?) = lost("disconnected")

        override fun onNullBinding(name: ComponentName?) {
            Log.w(TAG, "the FYT toolkit gave no binder; the watchdog binds again")
        }
    }

    private fun lost(why: String) {
        if (toolkit == null) return
        toolkit = null
        _connected.value = false
        Log.w(TAG, "FYT toolkit $why; the system binds it again when it is back")
        feed.post { observers.forEach { it(false) } }
    }

    /** The module's binder, or null when the toolkit is away or has no such module. */
    fun module(code: Int): FytModule? {
        val binder = toolkit ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(FytProtocol.DESCRIPTOR_TOOLKIT)
            data.writeInt(code)
            binder.transact(FytProtocol.TX_GET_MODULE, data, reply, 0)
            reply.readException()
            reply.readStrongBinder()?.let { FytModule(it, code) }
        } catch (e: Exception) {
            Log.w(TAG, "no FYT module $code", e)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** One module of the firmware: commands in, updates out through a registered [FytCallback]. */
    class FytModule(private val binder: IBinder, val code: Int) {

        fun cmd(cmdCode: Int, ints: IntArray? = null, flts: FloatArray? = null, strs: Array<String?>? = null): Boolean =
            transact(FytProtocol.TX_CMD) {
                writeInt(cmdCode)
                writeIntArray(ints)
                writeFloatArray(flts)
                writeStringArray(strs)
            }

        /** Asks for [updateCode]'s updates; [withValue] also brings the current value at once. */
        fun register(callback: FytCallback, updateCode: Int, withValue: Boolean = true): Boolean =
            transact(FytProtocol.TX_REGISTER) {
                writeStrongBinder(callback)
                writeInt(updateCode)
                writeInt(if (withValue) FytProtocol.REGISTER_WITH_VALUE else 0)
            }

        fun unregister(callback: FytCallback, updateCode: Int): Boolean =
            transact(FytProtocol.TX_UNREGISTER) {
                writeStrongBinder(callback)
                writeInt(updateCode)
            }

        private inline fun transact(tx: Int, write: Parcel.() -> Unit): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(FytProtocol.DESCRIPTOR_MODULE)
                data.write()
                binder.transact(tx, data, reply, 0)
                // Some firmwares answer with nothing at all: only a body is read.
                if (FytProtocol.moduleReplyHasBody(tx) || reply.dataAvail() > 0) reply.readException()
                true
            } catch (e: Exception) {
                Log.w(TAG, "FYT module $code: transaction $tx failed", e)
                false
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    /** The firmware's callback, answered by a plain Binder: each update is handed to [onUpdate] on the [feed] thread. */
    class FytCallback(
        private val feed: Handler,
        private val onUpdate: (code: Int, ints: IntArray?, flts: FloatArray?, strs: Array<String?>?) -> Unit
    ) : Binder() {
        init {
            attachInterface(null, FytProtocol.DESCRIPTOR_CALLBACK)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != FytProtocol.TX_UPDATE) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(FytProtocol.DESCRIPTOR_CALLBACK)
            val updateCode = data.readInt()
            val ints = data.createIntArray()
            val flts = data.createFloatArray()
            val strs = data.createStringArray()
            reply?.writeNoException()
            feed.post { onUpdate(updateCode, ints, flts, strs) }
            return true
        }
    }

    companion object {
        private const val TAG = "FytToolkit"
        private const val RETRY_MS = 2_000L
        private const val BIND_WAIT_MS = 10_000L
    }
}
