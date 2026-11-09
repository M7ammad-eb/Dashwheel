package com.openauto.dash

import com.openauto.dash.link.ObdClose
import com.openauto.dash.link.ObdOffer
import com.openauto.dash.link.ObdOpen
import com.openauto.dash.link.ObdRelayState
import com.openauto.dash.link.ObdRx
import com.openauto.dash.link.ObdTx
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * OBD through the phone: the companion holds the Bluetooth link to the
 * ELM327 adapter and relays its bytes over the phone link ([PhoneLink]).
 * [open] gives [ObdBluetoothManager] a pair of streams that behave like the
 * adapter's own socket, so its polling, fault codes and AI mechanic run
 * unchanged. On units whose Bluetooth is one radio shared with calls and
 * phone projection, the phone's radio keeps the adapter answering.
 */
internal object PhoneObd {
    /**
     * How long the phone may take to reach the adapter. The wait holds the OBD
     * locks, so the Connect button does nothing meanwhile: kept short, and a
     * phone that needs longer (a call, its own Bluetooth busy) is asked again
     * at the next retry rather than waited for.
     */
    private const val OPEN_TIMEOUT_MS = 15_000L

    private val _offer = MutableStateFlow<String?>(null)
    /** The adapter the linked phone can relay (its name), or null: no phone, or none chosen there. */
    val offer: StateFlow<String?> = _offer

    @Volatile private var opening: CompletableDeferred<ObdRelayState>? = null
    @Volatile private var input: RelayInput? = null

    /** A relayed link, or why there is none (the phone's words, for the log). */
    sealed interface Opened {
        class Streams(val input: InputStream, val output: OutputStream) : Opened
        class Failed(val reason: String) : Opened
    }

    /** Asks the phone for the adapter and waits for it to answer. */
    suspend fun open(): Opened {
        close()
        val wait = CompletableDeferred<ObdRelayState>()
        opening = wait
        val relay = RelayInput()
        input = relay
        if (!PhoneLink.send(ObdOpen)) {
            // Nothing asked: nothing left armed, or the next open() would tell the phone to close a relay it never opened.
            opening = null
            input = null
            return Opened.Failed("no phone link")
        }
        val state = withTimeoutOrNull(OPEN_TIMEOUT_MS) { wait.await() }
        opening = null
        if (state == null) {
            PhoneLink.send(ObdClose)
            return Opened.Failed("the phone did not answer")
        }
        if (!state.open) return Opened.Failed(state.reason ?: "refused")
        return Opened.Streams(relay, RelayOutput(relay))
    }

    /** The relayed link is no longer wanted: the phone lets the adapter go. */
    fun close() {
        // An open still waiting for the phone's answer ends now: the link is no longer wanted.
        opening?.complete(ObdRelayState(open = false, reason = "closed"))
        val relay = input ?: return
        input = null
        if (!relay.closed) PhoneLink.send(ObdClose)
        relay.end()
    }

    fun onOffer(message: ObdOffer) {
        _offer.value = message.adapter
    }

    fun onState(message: ObdRelayState) {
        opening?.let {
            it.complete(message)
            return
        }
        // Up and then lost on the phone's side (adapter unplugged, out of range).
        if (!message.open) input?.end()
    }

    fun onRx(message: ObdRx) {
        input?.feed(message.data.toByteArray(Charsets.ISO_8859_1))
    }

    /** The phone link is gone: so is anything relayed through it. */
    fun linkDown() {
        _offer.value = null
        opening?.complete(ObdRelayState(false, "phone link lost"))
        input?.end()
        input = null
    }

    /**
     * The adapter's bytes as they arrive from the phone. [available] throws
     * once the relay ended, the way a closed socket does, so a command in
     * flight sees the link drop.
     */
    private class RelayInput : InputStream() {
        private val lock = Object()
        private var bytes = ByteArray(0)
        @Volatile var closed = false

        fun feed(data: ByteArray) = synchronized(lock) {
            bytes += data
            lock.notifyAll()
        }

        fun end() = synchronized(lock) {
            closed = true
            lock.notifyAll()
        }

        override fun available(): Int = synchronized(lock) {
            if (closed && bytes.isEmpty()) throw IOException("OBD relay closed")
            bytes.size
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = synchronized(lock) {
            while (bytes.isEmpty() && !closed) lock.wait()
            if (bytes.isEmpty()) return -1
            val n = minOf(len, bytes.size)
            System.arraycopy(bytes, 0, b, off, n)
            bytes = bytes.copyOfRange(n, bytes.size)
            n
        }

        override fun close() = PhoneObd.close()
    }

    /** Bytes for the adapter, sent to the phone at each flush (a command and its return). */
    private class RelayOutput(private val relay: RelayInput) : OutputStream() {
        private val pending = ByteArrayOutputStream()

        override fun write(b: Int) {
            pending.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            pending.write(b, off, len)
        }

        override fun flush() {
            if (relay.closed) throw IOException("OBD relay closed")
            val data = pending.toByteArray()
            pending.reset()
            if (data.isNotEmpty() && !PhoneLink.send(ObdTx(String(data, Charsets.ISO_8859_1)))) {
                throw IOException("phone link lost")
            }
        }

        override fun close() = PhoneObd.close()
    }
}
