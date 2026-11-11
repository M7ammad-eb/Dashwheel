package com.openauto.dash.unit

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.openauto.dash.DebugLog
import com.openauto.unitproto.topway.TopwayProtocol

/**
 * Play, pause and skip on a Topway unit through its firmware's command service
 * ([TopwayProtocol.COMMAND_PACKAGE]), the way the unit's own media bar does: the
 * Bluetooth app (com.tw.bt) takes no media key and no session command, and the
 * media tile's buttons did nothing to it. The service hands each command to the
 * source playing, Bluetooth music among them.
 *
 * Bound on first use and kept; a command sent before the service answers waits
 * for it. Where the service isn't there (any other unit), [send] says false.
 */
internal object TopwayMedia {
    private const val TAG = "TopwayMedia"

    enum class Command(val tx: Int) {
        PLAY(TopwayProtocol.TX_MEDIA_PLAY),
        PAUSE(TopwayProtocol.TX_MEDIA_PAUSE),
        NEXT(TopwayProtocol.TX_MEDIA_NEXT),
        PREVIOUS(TopwayProtocol.TX_MEDIA_PREVIOUS)
    }

    private var service: IBinder? = null
    private var binding = false
    private var unavailable = false
    private var pending: Command? = null
    private var app: Context? = null

    /** Whether [pkg]'s player is one only the command service steers. */
    fun steers(pkg: String?): Boolean = pkg == TopwayProtocol.BT_PACKAGE

    /** Sends [command]; false when the unit has no command service, so the caller falls back. */
    @Synchronized
    fun send(context: Context, command: Command): Boolean {
        if (unavailable) return false
        val binder = service
        if (binder != null && binder.isBinderAlive) return transact(binder, command)
        pending = command
        if (!binding) bind(context.applicationContext)
        return !unavailable
    }

    private fun bind(context: Context) {
        app = context
        val intent = Intent(TopwayProtocol.COMMAND_BIND_ACTION)
            .setClassName(TopwayProtocol.COMMAND_PACKAGE, TopwayProtocol.COMMAND_CLASS)
        binding = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            .onFailure { Log.w(TAG, "the command service can't be bound", it) }
            .getOrDefault(false)
        if (!binding) {
            unavailable = true
            pending = null
            DebugLog.note(context, "Topway media: no command service to bind")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            val next = synchronized(this@TopwayMedia) {
                service = binder
                pending.also { pending = null }
            }
            app?.let { DebugLog.note(it, "Topway media: command service bound") }
            next?.let { transact(binder, it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(this@TopwayMedia) { service = null }
        }
    }

    private fun transact(binder: IBinder, command: Command): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(TopwayProtocol.COMMAND_DESCRIPTOR)
            binder.transact(command.tx, data, reply, 0)
            reply.readException()
            true
        } catch (e: Exception) {
            Log.w(TAG, "the command service refused ${command.name}", e)
            app?.let { DebugLog.note(it, "Topway media: ${command.name} refused (${e.javaClass.simpleName})") }
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
