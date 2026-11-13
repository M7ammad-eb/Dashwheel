package com.openauto.dash.unit

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.util.Log
import com.openauto.dash.DebugLog
import com.openauto.dash.HiddenApi
import com.openauto.unitproto.topway.TopwayProtocol
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The one TWUtil link to a Topway unit's MCU that Dashwheel opens, shared by
 * [TopwayBus] (reverse, canbox) and [TopwayRadio] (tuner, presets, source).
 *
 * The framework keeps one link per app: opened twice, the later open took the
 * messages and the radio, opened first, never heard a station while the unit's
 * own media bar did. So every channel is opened at once, here, and each message
 * handed to every user, who keeps the channels it reads.
 */
internal object TopwayLink {
    private const val TAG = "TopwayLink"
    private const val HANDLER_TAG = "Dashwheel"

    private val users = CopyOnWriteArraySet<(Message) -> Unit>()
    private var tw: Any? = null
    private var writer2: Method? = null
    private var writer3: Method? = null
    private var thread: HandlerThread? = null

    /** Every channel Dashwheel reads, opened in one go. */
    private val CHANNELS: ShortArray = (TopwayProtocol.LISTENED.toList() + TopwayProtocol.RADIO_LISTENED.toList()).distinct().toShortArray()

    /** [user] gets every message from now on (on the link's own thread); false when the unit refused the link. */
    @Synchronized
    fun acquire(context: Context, user: (Message) -> Unit): Boolean {
        users += user
        if (tw == null) open(context.applicationContext)
        return tw != null
    }

    @Synchronized
    fun release(user: (Message) -> Unit) {
        users -= user
        if (users.isEmpty()) close()
    }

    /** Writes [what] with one or two arguments to the MCU, as the firmware's own apps do; false without the link. */
    fun write(what: Int, arg1: Int, arg2: Int? = null): Boolean {
        val util = tw ?: return false
        return runCatching {
            if (arg2 == null) writer2?.invoke(util, what, arg1) else writer3?.invoke(util, what, arg1, arg2)
            true
        }.onFailure { Log.w(TAG, "write $what not made", it) }.getOrDefault(false)
    }

    /** Channels a first message came on: noted once each, so the log says what the MCU really sends Dashwheel. */
    private val heard = java.util.Collections.synchronizedSet(HashSet<Int>())
    private const val MAX_NOTED = 60
    private var appContext: Context? = null

    private fun open(app: Context) {
        appContext = app
        val t = HandlerThread(TAG).also { it.start() }
        val handler = Handler(t.looper) { msg ->
            // Each kind of message (channel and arg1) once, a few dozen at most: what the MCU sends Dashwheel, in the log.
            if (heard.size < MAX_NOTED && heard.add(msg.what * 1000 + (msg.arg1 and 0xFF))) appContext?.let {
                DebugLog.note(it, "Topway MCU: 0x${Integer.toHexString(msg.what)} arg1 ${msg.arg1} arg2 ${msg.arg2} obj ${(msg.obj as? String) ?: msg.obj?.javaClass?.simpleName}")
            }
            users.forEach { runCatching { it(msg) } }
            true
        }
        runCatching {
            val cls = Class.forName(TopwayProtocol.TWUTIL_CLASS)
            val util = runCatching { cls.getConstructor().newInstance() }.getOrElse { HiddenApi.newInstance(cls) }
            val opened = method(cls, "open", ShortArray::class.java).invoke(util, CHANNELS) as Int
            if (opened != 0) error("open answered $opened")
            method(cls, "start").invoke(util)
            method(cls, "addHandler", String::class.java, Handler::class.java).invoke(util, HANDLER_TAG, handler)
            val int = Int::class.javaPrimitiveType!!
            writer2 = runCatching { method(cls, "write", int, int) }.getOrNull()
            writer3 = runCatching { method(cls, "write", int, int, int) }.getOrNull()
            tw = util
            thread = t
            DebugLog.note(app, "Topway MCU: one link for ${CHANNELS.size} channels (reverse, canbox, radio)")
        }.onFailure {
            Log.w(TAG, "TWUtil is not there for Dashwheel", it)
            DebugLog.note(app, "Topway MCU: TWUtil refused (${it.cause ?: it})")
            t.quitSafely()
        }
    }

    private fun close() {
        val util = tw ?: return
        tw = null
        runCatching {
            val cls = util.javaClass
            method(cls, "removeHandler", String::class.java).invoke(util, HANDLER_TAG)
            method(cls, "stop").invoke(util)
            method(cls, "close").invoke(util)
        }
        thread?.quitSafely()
        thread = null
    }

    /** Public members first; the hidden-API way only when Android keeps them from Dashwheel. */
    private fun method(cls: Class<*>, name: String, vararg types: Class<*>): Method =
        runCatching { cls.getMethod(name, *types) }.getOrElse { HiddenApi.getDeclaredMethod(cls, name, *types) }
}
