package com.openauto.dash

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.Request

/**
 * Puts the PMPatch3 Magisk module on the unit, so Android grants Dashwheel
 * the firmware's signature-only permissions (see [EmbeddedApp] and the
 * README, section 7) without the user fetching and flashing it by hand.
 *
 * The module has no licence, so it isn't shipped inside the APK: its v1.2.0
 * release is downloaded from GitHub and refused unless it matches the
 * release's SHA-256. Magisk installs it (`magisk --install-module`) and
 * Zygisk, which it runs in, is turned on. Both take effect at the next start
 * of the unit.
 *
 * Android looks at signature permissions again at every start, so the
 * restart should be enough. Where it isn't, [finishSetup] reinstalls
 * Dashwheel once over itself, which always makes Android look again.
 *
 * Trade-off: PMPatch3 turns off signature checks for every app on the unit,
 * which the dialog says before anything is installed.
 */
internal object PmPatch {

    private const val TAG = "PmPatch"

    private const val URL = "https://github.com/vova7878-modules/PMPatch/releases/download/v1.2.0/PMPatch3.zip"
    private const val SHA256 = "cc3391bc1dee0dabd1f8d970d30bacabf80691dc958711a543d7ca9bf0d25bed"
    private const val MODULE_ID = "com_v7878_pmpatch_3"
    private const val TMP_ZIP = "/data/local/tmp/oad_pmpatch3.zip"
    private const val TMP_APK = "/data/local/tmp/oad_self.apk"

    /** `magisk --install-module` and Zygisk both came with Magisk 24. */
    private const val MIN_MAGISK = 24000

    private const val PREFS = "pmpatch"
    private const val KEY_PENDING = "pending"
    /** The kernel's boot id when [install] ran: the same one means no restart since. */
    private const val KEY_BOOT = "boot"

    enum class State {
        /** No `magisk` on the unit. */
        NO_MAGISK,
        /** Magisk older than 24. */
        OLD_MAGISK,
        /** Not installed, turned off in Magisk, or Zygisk off. */
        ABSENT,
        /** Installed since the unit started: a restart finishes it. */
        RESTART,
        /** Running; Android still has to grant the permissions ([reinstallSelf]). */
        RUNNING
    }

    private const val BOOT_ID = "echo \"BOOT $(cat /proc/sys/kernel/random/boot_id)\""

    // Prints what State needs, one word per line. Zygisk from another module
    // (Zygisk Next, ReZygisk) counts as on.
    private val STATE_SCRIPT = """
        command -v magisk >/dev/null 2>&1 || { echo NOMAGISK; exit 0; }
        echo "MAGISK $(magisk -V)"
        $BOOT_ID
        M=/data/adb/modules/$MODULE_ID
        if [ -d /data/adb/modules_update/$MODULE_ID ] || [ -f ${'$'}M/update ]; then echo PENDING
        elif [ -f ${'$'}M/module.prop ] && [ ! -f ${'$'}M/disable ] && [ ! -f ${'$'}M/remove ]; then echo PRESENT
        else echo ABSENT; fi
        for z in zygisksu rezygisk zygisk_next; do [ -d /data/adb/modules/${'$'}z ] && [ ! -f /data/adb/modules/${'$'}z/disable ] && echo ZYGISK_MODULE; done
        magisk --sqlite "SELECT value FROM settings WHERE key='zygisk'" 2>/dev/null
    """.trimIndent()

    fun state(context: Context): Result<State> = runCatching {
        val out = shell(context, STATE_SCRIPT)
        if (out.contains("NOMAGISK")) return@runCatching State.NO_MAGISK
        val version = Regex("MAGISK (\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val zygisk = out.contains("ZYGISK_MODULE") || out.contains("value=1")
        when {
            version < MIN_MAGISK -> State.OLD_MAGISK
            out.contains("PENDING") -> State.RESTART
            !out.contains("PRESENT") || !zygisk -> State.ABSENT
            // Zygisk turned on by install() only starts with the unit.
            bootId(out) != null && bootId(out) == prefs(context).getString(KEY_BOOT, null) -> State.RESTART
            else -> State.RUNNING
        }
    }

    private fun bootId(out: String): String? = Regex("BOOT (\\S+)").find(out)?.groupValues?.get(1)

    /**
     * Downloads the module, checks it, has Magisk install it and turns Zygisk
     * on. The unit then needs a restart ([PrivApp.rebootDevice]).
     */
    fun install(context: Context): Result<Unit> = runCatching {
        val zip = download(context)
        val out = shell(
            context,
            """
                magisk --install-module $TMP_ZIP || { rm -f $TMP_ZIP; echo FAIL:install; exit 0; }
                rm -f $TMP_ZIP
                Z=0
                for z in zygisksu rezygisk zygisk_next; do [ -d /data/adb/modules/${'$'}z ] && Z=1; done
                [ ${'$'}Z = 1 ] || magisk --sqlite "REPLACE INTO settings (key,value) VALUES('zygisk',1)" || { echo FAIL:zygisk; exit 0; }
                $BOOT_ID
                echo OKPMPATCH
            """.trimIndent(),
            zip to TMP_ZIP
        )
        zip.delete()
        if (!out.contains("OKPMPATCH")) error(reason(out))
        prefs(context).edit().putBoolean(KEY_PENDING, true).putString(KEY_BOOT, bootId(out)).apply()
    }

    private fun download(context: Context): File {
        val file = File(context.cacheDir, "PMPatch3.zip")
        val client = Http.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(URL).build()).execute().use { res ->
            check(res.isSuccessful) { "HTTP ${res.code}" }
            file.outputStream().use { out -> res.body!!.byteStream().copyTo(out) }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (digest != SHA256) {
            file.delete()
            error(context.getString(R.string.settings_pmpatch_bad_download))
        }
        return file
    }

    /**
     * Installs Dashwheel's own APK over itself, so Android grants the
     * permissions PMPatch3 now allows. Android stops Dashwheel on the way and
     * starts it again as the home screen.
     */
    fun reinstallSelf(context: Context): Result<Unit> = runCatching {
        prefs(context).edit().putBoolean(KEY_PENDING, false).commit()
        val apk = context.applicationInfo.sourceDir
        val out = shell(
            context,
            "cp '$apk' $TMP_APK && chmod 644 $TMP_APK && pm install -r $TMP_APK; rm -f $TMP_APK"
        )
        if (!out.contains("Success")) error(out.trim())
    }

    /**
     * At start: after the restart that follows [install], and only then,
     * reinstalls Dashwheel if Android still hasn't granted the permissions.
     * Once only, whatever happens.
     */
    fun finishSetup(context: Context) {
        val app = context.applicationContext
        if (!prefs(app).getBoolean(KEY_PENDING, false)) return
        if (EmbeddedApp.allowed(app)) {
            prefs(app).edit().putBoolean(KEY_PENDING, false).apply()
            PmPatchSteps.reached(app, PmPatchSteps.COUNT)
            return
        }
        thread(isDaemon = true, name = "pmpatch") {
            // No shell right now: try again at the next start.
            val state = state(app).getOrElse { return@thread }
            Log.i(TAG, "after install: $state")
            // Still waiting for a restart: try again at the next start.
            if (state == State.RESTART) return@thread
            if (state == State.RUNNING) {
                reinstallSelf(app).onFailure { Log.w(TAG, "reinstall failed", it) }
            } else {
                prefs(app).edit().putBoolean(KEY_PENDING, false).apply()
            }
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Runs [script] as root after putting each local file of [push] at its
     * path: through Magisk `su` (this is a Magisk feature), else over the
     * unit's internal ADB raised to root.
     */
    private fun shell(context: Context, script: String, vararg push: Pair<File, String>): String {
        val out = if (SystemInstaller.isRootAvailable()) {
            val copy = push.joinToString("") { (file, to) ->
                "cp '${file.absolutePath}' '$to' || { echo 'FAIL:copy ${file.name}'; exit 0; }; "
            }
            RootShell.su(null, SU_TIMEOUT_S, stdin = copy + script + "\nexit\n").all
        } else {
            val port = AdbInstaller.listeningPort()
                ?: error(context.getString(R.string.boot_no_adb, AdbInstaller.announcedPort()))
            check(AdbInstaller.ensureRoot(context, port)) { context.getString(R.string.settings_pmpatch_no_root) }
            AdbInstaller.connect(context, port, readTimeoutMs = AdbInstaller.LONG_TIMEOUT_MS).use { dadb ->
                push.forEach { (file, to) -> dadb.push(file, to) }
                dadb.shell(script).allOutput
            }
        }
        Log.d(TAG, out)
        return out
    }

    // Unpacking the module and `pm install` of a 30 MB APK are slow on this unit.
    private const val SU_TIMEOUT_S = 120L

    /** Why the script stopped: the line before its FAIL: line (what Magisk said), else everything it said. */
    private fun reason(out: String): String {
        val lines = out.trim().lines()
        val fail = lines.indexOfFirst { it.startsWith("FAIL:") }
        return when {
            fail > 0 -> lines[fail - 1].trim()
            fail == 0 -> lines[0].removePrefix("FAIL:")
            else -> out.trim()
        }
    }
}
