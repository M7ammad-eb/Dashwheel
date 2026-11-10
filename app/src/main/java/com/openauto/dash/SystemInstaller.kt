package com.openauto.dash

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Whether the app runs as a system app, and whether root (`su`) answers. The
 * privileged install itself, the scripts and the ADB fallback, is the GitHub
 * edition's [PrivApp]; the Play edition's [PrivApp] refuses, and
 * [isRootAvailable] answers no before anything is run ([Edition.play]).
 */
object SystemInstaller {

    /** True if the app is already running from a system/privileged location. */
    fun isSystemApp(context: Context): Boolean {
        val info = context.applicationInfo
        val flagged = (info.flags and
            (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        val path = info.sourceDir.startsWith("/system/") ||
            info.sourceDir.startsWith("/system_ext/") ||
            info.sourceDir.startsWith("/priv-app/") ||
            info.sourceDir.startsWith("/product/") ||
            info.sourceDir.startsWith("/vendor/") ||
            info.sourceDir.startsWith("/odm/")
        return flagged || path
    }

    /**
     * True if a root shell (`su`) is available and granted. Bounded: if the
     * Magisk prompt is left unanswered the probe gives up instead of pinning an
     * IO thread forever.
     *
     * A yes is kept for the process (the probe, the shells and the installers
     * all ask). The probe command is the edition's ([PrivApp.rootProbe]): the
     * GitHub one also turns off the root manager's per-app toast.
     */
    fun isRootAvailable(): Boolean {
        // The Play edition never asks: no su, no root prompt.
        if (Edition.play) return false
        if (rootGranted) return true
        return runCatching {
            RootShell.su(PrivApp.rootProbe, ROOT_PROBE_TIMEOUT_S).exit == 0
        }.getOrDefault(false).also { if (it) rootGranted = true }
    }

    @Volatile private var rootGranted = false

    private const val ROOT_PROBE_TIMEOUT_S = 8L

    /** What every root path answers in the Play edition, which never runs `su`. */
    internal const val NO_ROOT_PLAY = "no root in the Play edition"
}


/**
 * The one way the app runs `su`: both output streams read at once (a full
 * pipe cannot stall the command) and the command killed after a timeout, so
 * an unanswered Magisk prompt or a stuck command cannot hang its caller.
 */
internal object RootShell {

    class Output(val exit: Int, val out: String, val err: String) {
        val all: String get() = out + err
    }

    /**
     * `su -c [cmd]`, or, with [cmd] null, `su` running [stdin] as its script
     * (no quoting gets in the way). Throws when [timeoutS] runs out.
     *
     * The root shell first tells its pid (a [PID] line on stderr, taken off
     * again), so a command that runs out of time is killed with everything it
     * started ([killTree]): ending the `su` client alone left the root shell
     * and its command running, one more after each timeout.
     */
    fun su(cmd: String?, timeoutS: Long, stdin: String? = null): Output {
        check(Edition.full) { SystemInstaller.NO_ROOT_PLAY }
        val tell = "echo \"$PID \$\$\" >&2"
        val process = Runtime.getRuntime().exec(if (cmd != null) arrayOf("su", "-c", "$tell; $cmd") else arrayOf("su"))
        // One builder per stream: the two readers run concurrently. Each is
        // appended under its lock, and not once the result is taken (done): a
        // grandchild holding a pipe keeps its reader alive past the join below.
        val out = StringBuilder()
        val err = StringBuilder()
        val done = AtomicBoolean(false)
        fun StringBuilder.put(text: String) = synchronized(this) { if (!done.get()) append(text) }
        val pid = AtomicInteger(0)
        val reader = Thread { runCatching { out.put(process.inputStream.bufferedReader().readText()) } }
        val errReader = Thread {
            runCatching {
                val r = process.errorStream.bufferedReader()
                val first = r.readLine() ?: return@runCatching
                val told = first.takeIf { it.startsWith(PID) }?.substringAfter(' ')?.trim()?.toIntOrNull()
                if (told != null) pid.set(told) else err.put(first + "\n")
                err.put(r.readText())
            }
        }
        reader.start(); errReader.start()
        try {
            // su gone before reading its script: the exit status says why.
            runCatching {
                process.outputStream.bufferedWriter().use { w ->
                    if (cmd == null) w.write("$tell\n")
                    stdin?.let(w::write)
                }
            }
            if (!process.waitFor(timeoutS, TimeUnit.SECONDS)) {
                pid.get().takeIf { it > 0 }?.let(::killTree)
                throw IllegalStateException("su timed out")
            }
            reader.join(2000); errReader.join(2000)
            done.set(true)
            return Output(process.exitValue(), synchronized(out) { out.toString() }, synchronized(err) { err.toString() })
        } finally {
            // A command that left a child holding the pipes would keep both
            // readers (and the streams) waiting for good: closing ends them.
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            process.destroy()
        }
    }

    /** Marks the root shell's pid on its stderr, see [su] (on stdout for [stream]). */
    private const val PID = "__DW_PID__"

    /**
     * `su -c [cmd]` left running for its caller to read line by line
     * ([Stream.reader]): a sniff of `getevent` or `logcat` that ends when its
     * reader stops. The root shell tells its pid first ([PID], on stdout, the
     * one stream read; [Stream.readPid] takes it off), so [Stream.kill] ends
     * the command itself: destroying the `su` client alone left `getevent`
     * running until its next key press.
     */
    fun stream(cmd: String): Stream {
        check(Edition.full) { SystemInstaller.NO_ROOT_PLAY }
        val tell = "echo \"$PID \$\$\""
        return Stream(Runtime.getRuntime().exec(arrayOf("su", "-c", "$tell; $cmd")))
    }

    class Stream internal constructor(private val process: Process) {
        val reader = process.inputStream.bufferedReader()

        /** The root shell's pid, once [readPid] saw its line; null while unread, or when su refused (no line at all). */
        @Volatile private var pid: Int? = null

        /** Takes the pid line off the output: the first line, before anything the command prints. */
        fun readPid() {
            pid = reader.readLine()?.takeIf { it.startsWith(PID) }?.substringAfter(' ')?.trim()?.toIntOrNull()
        }

        /**
         * Ends the command with everything it started ([killTree]), then the `su`
         * client. The stream is closed under the reader (not the reader itself:
         * a blocked readLine holds its lock), so a read under way ends in an IOException.
         */
        fun kill() {
            pid?.let { killTree(it) }
            runCatching { process.inputStream.close() }
            runCatching { process.destroy() }
            runCatching { process.destroyForcibly() }
        }
    }

    /**
     * Kills [pid] and everything it started, children first, from a root
     * shell of its own ([shell] for a test's plain shell), off the caller's thread.
     */
    fun killTree(pid: Int, shell: String = "su") {
        val script = killTreeScript(pid)
        thread(name = "su-kill", isDaemon = true) {
            runCatching {
                if (shell == "su") su(script, 5)
                else Runtime.getRuntime().exec(arrayOf(shell, "-c", script)).waitFor(5, TimeUnit.SECONDS)
            }.onFailure { Log.w("RootShell", "could not end a stuck shell", it) }
        }
    }

    /** The shell line that kills [pid] and all its descendants, deepest first. Pure, for the tests. */
    internal fun killTreeScript(pid: Int): String =
        "k() { for c in \$(pgrep -P \$1); do k \$c; done; kill -9 \$1; }; k $pid 2>/dev/null"

    /**
     * One `su` kept open, the commands written to it one after another and
     * each answer read back up to an end mark. The docking poller lists the
     * windows every few seconds, and a fresh su for each (a fork, Magisk's
     * grant, two reader threads) cost more than the listing itself. One
     * command at a time: the caller serialises. A command that runs out of
     * time, or a shell that has gone, closes the session; the next command
     * opens a new one. Both output streams come back as one ([Output.out]).
     * [shell] is `su`; a test hands it a plain shell. The Play edition never
     * opens `su` (a test's plain shell is still allowed).
     *
     * Killing the `su` client alone leaves the root shell and the stuck
     * command (often `am`, a whole JVM) running: a session that timed out
     * kills them as well ([killTree]), else each timeout left another behind
     * and a unit already slow got slower with every one, until a reboot.
     */
    class Session(private val shell: String = "su") {
        init {
            check(Edition.full || shell != "su") { SystemInstaller.NO_ROOT_PLAY }
        }

        private val process: Process = Runtime.getRuntime().exec(arrayOf(shell))
        private val input = process.outputStream.bufferedWriter()
        private val lines = LinkedBlockingQueue<String>()

        init {
            // Read on its own thread, so a wait for the end mark can time out.
            Thread({
                runCatching { process.inputStream.bufferedReader().forEachLine { lines.put(it) } }
                lines.put(GONE)
            }, "su-session").apply { isDaemon = true; start() }
        }

        /** The shell's own pid (`$$` is the root shell's, also in the subshell), for [killTree]. */
        private val pid: Int? = runCatching { run("echo \$\$", 8).out.trim().toIntOrNull() }.getOrNull()

        /** Whether the shell is still there to take a command. */
        val alive: Boolean get() = process.isAlive

        /** Runs [cmd] (one line), waiting [timeoutS] at most for its end; throws when the shell fails. */
        fun run(cmd: String, timeoutS: Long): Output {
            require('\n' !in cmd) { "one line at a time" }
            try {
                // A subshell keeps a command's `;` chains and redirections to itself.
                // The empty line puts the end mark on a line of its own, also after
                // an output that does not end its last line (it is taken off below).
                input.write("( $cmd ) 2>&1\n__dw=\$?\necho\necho \"$MARK \$__dw\"\n")
                input.flush()
            } catch (e: IOException) {
                close()
                throw IllegalStateException("su gone: ${e.message}")
            }
            val out = ArrayList<String>()
            val deadline = System.nanoTime() + timeoutS * 1_000_000_000L
            while (true) {
                val wait = deadline - System.nanoTime()
                val line = (if (wait > 0) lines.poll(wait, TimeUnit.NANOSECONDS) else null) ?: run {
                    killTree()
                    close()
                    throw IllegalStateException("su timed out")
                }
                if (line === GONE) {
                    close()
                    throw IllegalStateException("su exited")
                }
                if (line.startsWith(MARK)) {
                    if (out.lastOrNull()?.isEmpty() == true) out.removeAt(out.lastIndex)
                    return Output(line.substringAfter(' ').trim().toIntOrNull() ?: -1, out.joinToString("") { it + "\n" }, "")
                }
                out += line
            }
        }

        fun close() {
            runCatching { input.close() }
            process.destroy()
        }

        /** Kills the shell and everything it started, see [RootShell.killTree]. */
        private fun killTree() {
            killTree(pid ?: return, shell)
        }

        private companion object {
            /** Ends each command's output, followed by its exit status. */
            const val MARK = "__DW_END__"

            /** Queued once the shell's output ends: it has gone. */
            val GONE = String()
        }
    }
}
