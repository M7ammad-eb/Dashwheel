package com.openauto.dash

import android.os.Bundle
import android.os.Process
import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * The service Shizuku starts for Dashwheel ([ShizukuShell] binds it): a
 * process of its own, run from this APK by `app_process` as Shizuku's user,
 * the shell (uid 2000) over wireless debugging or ADB, root where Shizuku was
 * started by root. One command at a time ([ShizukuShell] serialises), each
 * bounded by its caller's timeout, both streams read together so a full pipe
 * cannot stall it. No Application here: only what a bare process has.
 */
class ShizukuShellService : IShizukuShell.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun uid(): Int = Process.myUid()

    override fun run(cmd: String, timeoutS: Int): Bundle {
        val result = Bundle()
        // The shell tells its pid first (a PID line, taken off below), so a
        // command that runs out of time is killed with everything it started:
        // ending `sh` alone left the command and its children running.
        val process = try {
            ProcessBuilder("sh", "-c", "echo \"$PID \$\$\"; $cmd").redirectErrorStream(true).start()
        } catch (e: Exception) {
            Log.w(TAG, "could not start `$cmd`: ${e.message}")
            return result.apply { putString(OUT, "Error: ${e.message}"); putInt(EXIT, -1) }
        }
        // Nothing is ever typed to it: a command that reads its input would otherwise wait for the timeout.
        runCatching { process.outputStream.close() }
        // Read on its own thread, so the wait below can run out while the command still prints.
        val out = StringBuilder()
        val done = AtomicBoolean(false)
        val pid = AtomicInteger(0)
        // Appended under the builder's lock, and not once the result is taken
        // (done): a grandchild holding the pipe keeps this reader alive long
        // after the join below. Binder carries about a megabyte per call: keep well under it.
        fun put(text: String) = synchronized(out) {
            if (!done.get() && out.length < MAX_OUT) out.append(text, 0, minOf(text.length, MAX_OUT - out.length))
        }
        val reader = Thread({
            runCatching {
                process.inputStream.bufferedReader().use { r ->
                    val first = r.readLine() ?: return@use
                    val told = first.takeIf { it.startsWith(PID) }?.substringAfter(' ')?.trim()?.toIntOrNull()
                    if (told != null) pid.set(told) else put(first + "\n")
                    val buf = CharArray(8 * 1024)
                    while (!done.get()) {
                        val n = r.read(buf)
                        if (n < 0) break
                        put(String(buf, 0, n))
                    }
                }
            }
        }, "shizuku-shell-out").apply { isDaemon = true; start() }
        val finished = process.waitFor(timeoutS.toLong().coerceAtLeast(1L), TimeUnit.SECONDS)
        if (finished) {
            reader.join(2_000)
        } else {
            pid.get().takeIf { it > 0 }?.let(::killTree)
            process.destroyForcibly()
            reader.join(500)
            Log.w(TAG, "`${cmd.take(80)}` ran out of time (${timeoutS}s)")
        }
        val text = synchronized(out) {
            done.set(true)
            if (!finished) out.append("\nError: timed out after ${timeoutS}s")
            out.toString()
        }
        return result.apply { putString(OUT, text); putInt(EXIT, if (finished) process.exitValue() else -1) }
    }

    /** Kills [pid] and all it started, deepest first ([RootShell.killTreeScript]), bounded: the caller's timeout has already run out. */
    private fun killTree(pid: Int) {
        runCatching {
            val k = ProcessBuilder("sh", "-c", RootShell.killTreeScript(pid)).redirectErrorStream(true).start()
            if (!k.waitFor(5, TimeUnit.SECONDS)) k.destroyForcibly()
        }.onFailure { Log.w(TAG, "could not end a stuck command", it) }
    }

    companion object {
        private const val TAG = "ShizukuShellService"
        const val OUT = "out"
        const val EXIT = "exit"
        private const val MAX_OUT = 512 * 1024

        /** Marks the shell's pid on the first line of its output, see [run]. */
        private const val PID = "__DW_PID__"
    }
}
