package com.openauto.dash

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reglink's three plain-text whitelists on /system (which apps may start
 * by themselves, run in the background, and survive the sleep): Dashwheel
 * appended to each, through root with /system remounted for the moment.
 * A firmware update puts the files back; the Head unit card offers the step
 * again then.
 */
internal object ReglinkWhitelist {
    private const val TAG = "ReglinkWhitelist"

    val FILES = listOf(
        "/system/etc/reglink_autorun_whitelist.txt",
        "/system/etc/reglink_background_app_whitelist.txt",
        "/system/etc/suspend_kill_whitelist.txt"
    )

    /** Whether the files are there at all (a Reglink unit). */
    val available: Boolean get() = FILES.any { java.io.File(it).exists() }

    /** True when Dashwheel is in every file that exists afterwards. */
    suspend fun apply(context: Context): Boolean = withContext(Dispatchers.IO) {
        val pkg = context.packageName
        val present = FILES.filter { java.io.File(it).exists() }
        if (present.isEmpty()) return@withContext false
        val missing = present.filter { f -> runCatching { java.io.File(f).readLines().none { it.trim() == pkg } }.getOrDefault(true) }
        if (missing.isEmpty()) return@withContext true
        // Semicolons, not &&: one append refused (a read-only overlay) must not leave /system
        // writable by skipping the remount at the end. Success is judged by reading the files back.
        val cmds = listOf("mount -o rw,remount /system") +
            missing.map { "grep -qx '$pkg' $it || echo '$pkg' >> $it" } +
            "mount -o ro,remount /system"
        val out = runCatching { RootShell.su(cmds.joinToString("; "), timeoutS = 20).all }.getOrElse { it.message }
        val ok = present.all { f -> runCatching { java.io.File(f).readLines().any { it.trim() == pkg } }.getOrDefault(false) }
        Log.i(TAG, if (ok) "Dashwheel on the Reglink whitelists" else "whitelists not written: $out")
        ok
    }
}
