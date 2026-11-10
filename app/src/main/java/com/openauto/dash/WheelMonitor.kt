package com.openauto.dash

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What the head unit shows while the button map is open: Android keys, CAN
 * frames that change after being still, raw input events (root `getevent`)
 * and log lines about keys (root `logcat`). Firmware differs in where the
 * wheel buttons surface; this shows where they do on this one, and a key, CAN,
 * input or log line can be tapped to learn it ([Line.learn]).
 *
 * The input events and the log are also a way in for buttons of their own:
 * a key the input system reports but never hands to an app ([inputPresses]),
 * or one the firmware only writes to its log ([logLines]). Read while the
 * map is open, or while such a button is bound ([holdInput], [holdLog]).
 */
internal object WheelMonitor {
    enum class Source { KEY, CAN, INPUT, LOG }

    data class Line(val id: Long, val source: Source, val text: String, val learn: WheelKey?)

    private const val MAX_LINES = 14
    /** A log line worth showing on the map: one that talks of a key or a button. "key" bare, so "KeyEvent" and "MainKey" count. */
    private val LOG_MATCH = Regex("(?i)(key|swc|wheel|steer|button)")
    // The CAN stream already has its own lines; McuReader parses them.
    private val LOG_SKIP = Regex("dispatchToClients")
    /** `logcat -v brief`: "D/Tag( 1234): message". The process id changes with every boot, so it is left out of what is learned. */
    private val BRIEF = Regex("""^([VDIWEFS])/(.*?)\(\s*\d+\)\s*:\s?(.*)$""")
    /** How much of a log line is kept to learn from. */
    private const val MAX_PATTERN = 200

    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    /** Newest first. */
    val lines: StateFlow<List<Line>> = _lines.asStateFlow()

    private val _inputPresses = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Each key the input system reports going down, as "KEY_NEXTSONG@event2" ([WheelKey.input]). */
    val inputPresses: SharedFlow<String> = _inputPresses.asSharedFlow()

    private val _logLines = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Each log line about a key, tidied ([logPattern]): a button learned from one ([WheelKey.log]) matches by containing its text. */
    val logLines: SharedFlow<String> = _logLines.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var nextId = 0L
    private var monitoring = false
    private var inputHeld = false
    private var inputJob: Job? = null
    private var inputProcess: RootShell.Stream? = null
    private var logHeld = false
    private var logJob: Job? = null
    private var logProcess: RootShell.Stream? = null

    fun add(source: Source, text: String, learn: WheelKey? = null) {
        _lines.update { listOf(Line(nextLineId(), source, text.take(160), learn)) + it.take(MAX_LINES - 1) }
    }

    @Synchronized
    private fun nextLineId(): Long = nextId++

    @Synchronized
    fun start() {
        monitoring = true
        _lines.value = emptyList()
        // Keys and CAN lines are added by SteeringWheelStore whatever the
        // device; the raw input and the log are read through su, which is not
        // asked for where root was not found (PrivilegedShell), nor in the Play edition.
        updateInputLocked()
        updateLogLocked()
    }

    @Synchronized
    fun stop() {
        monitoring = false
        updateInputLocked()
        updateLogLocked()
    }

    /** Keeps the input events read while a button bound to one needs them ([SteeringWheelStore]). */
    @Synchronized
    fun holdInput(on: Boolean) {
        inputHeld = on
        updateInputLocked()
    }

    /** Keeps the log read while a button learned from a log line needs it ([SteeringWheelStore]). */
    @Synchronized
    fun holdLog(on: Boolean) {
        logHeld = on
        updateLogLocked()
    }

    /** Whether the root readers (input events, the log) can run here at all: the map says so when they can't. */
    val canReadRoot: Boolean get() = Edition.full && PrivilegedShell.access.value.root

    private fun updateInputLocked() {
        val wanted = (monitoring || inputHeld) && canReadRoot
        if (wanted == (inputJob?.isActive == true)) return
        if (!wanted) {
            inputJob?.cancel()
            inputJob = null
            inputProcess?.kill()
            inputProcess = null
            return
        }
        inputJob = sniff("exec getevent -lq", onStart = { inputProcess = it }) { line ->
            val press = inputPress(line) ?: return@sniff
            _inputPresses.tryEmit(press)
            if (monitoring) add(Source.INPUT, tidyGetevent(line))
        }
    }

    private fun updateLogLocked() {
        val wanted = (monitoring || logHeld) && canReadRoot
        if (wanted == (logJob?.isActive == true)) return
        if (!wanted) {
            logJob?.cancel()
            logJob = null
            logProcess?.kill()
            logProcess = null
            return
        }
        logJob = sniff("exec logcat -v brief -T 1", onStart = { logProcess = it }) { line ->
            if (!LOG_MATCH.containsMatchIn(line) || LOG_SKIP.containsMatchIn(line)) return@sniff
            val pattern = logPattern(line)
            _logLines.tryEmit(pattern)
            if (monitoring) add(Source.LOG, pattern, WheelKey.log(pattern))
        }
    }

    /** "/dev/input/event2: EV_KEY KEY_NEXTSONG DOWN" → "KEY_NEXTSONG@event2"; null for anything but a key going down. */
    internal fun inputPress(line: String): String? {
        if ("EV_KEY" !in line) return null
        val parts = line.substringAfter("EV_KEY").trim().split(Regex("\\s+"))
        if (parts.size < 2 || parts[1] != "DOWN") return null
        if (isPointerKey(parts[0])) return null
        val device = line.substringBefore(':').substringAfterLast('/')
        return if (device.isNotEmpty() && device != line) "${parts[0]}@$device" else parts[0]
    }

    /**
     * A finger on the screen, a stylus or a mouse button, never a car button. A touchscreen sends
     * BTN_TOUCH on every tap: learned as a button and bound to Home, it sent every tap Home and
     * locked the driver out of Settings.
     */
    internal fun isPointerKey(key: String): Boolean =
        key == "BTN_TOUCH" || key.startsWith("BTN_TOOL_") || key.startsWith("BTN_STYLUS") ||
            key == "BTN_LEFT" || key == "BTN_RIGHT" || key == "BTN_MIDDLE"

    /** "/dev/input/event2: EV_KEY KEY_NEXTSONG DOWN" → "KEY_NEXTSONG DOWN (event2)". */
    internal fun tidyGetevent(line: String): String {
        val device = line.substringBefore(':').substringAfterLast('/')
        val rest = line.substringAfter("EV_KEY").trim().split(Regex("\\s+")).joinToString(" ")
        return if (device.isNotEmpty() && device != line) "$rest ($device)" else rest
    }

    /**
     * A log line as the map shows and learns it: "D/MainKey( 1234): key 576 down"
     * → "MainKey: key 576 down", the process id gone since it changes with every
     * boot. A line in another format is kept as it is, trimmed.
     */
    internal fun logPattern(line: String): String {
        val m = BRIEF.matchEntire(line.trim())
        val tidy = if (m != null) "${m.groupValues[2].trim()}: ${m.groupValues[3].trim()}" else line.trim()
        return tidy.take(MAX_PATTERN)
    }

    /**
     * Runs [command] under su until cancelled, handing each line to [onLine];
     * [onStart] keeps the stream to kill it. Through [RootShell.stream], which
     * knows the root shell's pid: killing a raw `su` client left `getevent`
     * running until its next key press.
     */
    private fun sniff(command: String, onStart: (RootShell.Stream) -> Unit = {}, onLine: (String) -> Unit): Job = scope.launch {
        if (Edition.play) return@launch
        val s = runCatching { RootShell.stream(command) }.getOrNull() ?: return@launch
        synchronized(this@WheelMonitor) { onStart(s) }
        try {
            s.readPid()
            while (isActive) onLine(s.reader.readLine() ?: break)
        } catch (e: IOException) {
            // Killed by stop(), or su refused: nothing more to show from here.
        } finally {
            s.kill()
        }
    }
}
