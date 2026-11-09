package com.openauto.dash

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/*
 * The head unit itself: CPU, memory, chip temperature, storage, uptime and
 * the apps working hardest, for the Head unit widget. Memory, storage and
 * uptime come from Android; the CPU and the apps need the privileged shell
 * (toybox `top`, the same reading as on the unit's own console), in a root
 * session of its own so it never waits on, or holds up, the window shell.
 * Read every few seconds, and only while a Head unit tile can be seen.
 */

/** One app's share of the head unit; [packageName] null for the system's own processes, taken together. */
internal data class AppLoad(val packageName: String?, val label: String, val cpuPct: Float, val ramMb: Int)

internal data class HeadUnitStats(
    /** All cores together, 0-100; null without the shell. */
    val cpuPct: Int?,
    val cores: Int,
    /** The hottest CPU/SoC thermal zone; null when none can be read. */
    val tempC: Int?,
    val ramUsedMb: Long,
    val ramTotalMb: Long,
    val storageUsedGb: Float,
    val storageTotalGb: Float,
    val uptimeMs: Long,
    /** Busiest first; empty without the shell. */
    val apps: List<AppLoad>,
    /** Apps with a process running; null without the shell. */
    val appCount: Int?
) {
    val ramPct: Int get() = if (ramTotalMb > 0) (ramUsedMb * 100 / ramTotalMb).toInt() else 0
    val storagePct: Int get() = if (storageTotalGb > 0f) (storageUsedGb * 100 / storageTotalGb).roundToInt() else 0
}

internal object HeadUnitMonitor {

    private const val TAG = "HeadUnitMonitor"

    /** How often a visible tile is refreshed. */
    private const val POLL_MS = 3_000L

    /** From here the chip counts as hot: amber on the tile, and said once out loud. */
    const val HOT_C = 85

    /** Back under this, a new heat spell may be announced again. */
    private const val COOL_C = 75

    /** How often the chip's temperature is looked at when no tile shows. */
    private const val HEAT_WATCH_MS = 30_000L

    /** Rows of `top` read: enough for the busiest apps once the system's processes are set aside. */
    private const val TOP_LINES = 25

    /**
     * Processes that must keep running, besides the ones Android keeps alive
     * itself (persistent: System UI, the phone): the firmware's services, Google
     * Play services, the content providers. No Stop for them.
     */
    private val PROTECTED_PREFIXES = listOf("com.qf.", "com.google.android.gms", "com.google.android.gsf", "com.android.providers.", "com.android.keychain")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _stats = MutableStateFlow<HeadUnitStats?>(null)
    val stats: StateFlow<HeadUnitStats?> = _stats.asStateFlow()

    private var users = 0
    private var poller: Job? = null
    private var session: RootShell.Session? = null

    /** A visible Head unit tile starts the readings; the last one gone stops them. Main thread. */
    fun acquire(context: Context) {
        users++
        if (poller?.isActive == true) return
        val app = context.applicationContext
        poller = scope.launch {
            while (isActive) {
                runCatching { _stats.value = read(app) }.onFailure { Log.w(TAG, "reading failed", it) }
                delay(POLL_MS)
            }
        }
    }

    fun release() {
        if (users == 0) return
        if (--users > 0) return
        // The root shell stays open for the next tile: each new su makes Magisk show its toast.
        poller?.cancel()
        poller = null
    }

    /** Whether [packageName] may be force-stopped from the list. */
    fun canStop(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName || packageName == "android") return false
        if (PROTECTED_PREFIXES.any { packageName.startsWith(it) }) return false
        // The keyboard in use: the car would be left without one.
        val ime = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
        if (ime?.substringBefore('/') == packageName) return false
        val flags = runCatching { context.packageManager.getApplicationInfo(packageName, 0).flags }.getOrNull() ?: return false
        return flags and android.content.pm.ApplicationInfo.FLAG_PERSISTENT == 0
    }

    /** Force-stops [packageName] through the shell, then reads again so the list shows it gone. */
    suspend fun stop(context: Context, packageName: String) = withContext(Dispatchers.IO) {
        if (!canStop(context, packageName)) return@withContext
        shell(context, "am force-stop $packageName", 5)
        runCatching { _stats.value = read(context.applicationContext) }
    }

    /**
     * Says once, when car warnings are spoken ([SpokenEvent.CAR_TIPS]), that
     * the head unit is running hot, when the chip stays at [HOT_C] or above
     * for two looks in a row; again only after it has cooled under [COOL_C].
     * Reads the thermal zones directly (no shell): where Android hides them,
     * it stays quiet.
     */
    suspend fun watchHeat(context: Context) {
        var hotLooks = 0
        var announced = false
        while (true) {
            val t = readTempDirect()
            if (t == null) return
            when {
                t >= HOT_C -> hotLooks++
                t < COOL_C -> { hotLooks = 0; announced = false }
                else -> hotLooks = 0
            }
            if (hotLooks >= 2 && !announced && !DemoMode.isOn) {
                announced = true
                if (SpokenEvents.isOn(context, SpokenEvent.CAR_TIPS)) {
                    val line = AppLanguage.wrap(context).getString(R.string.monitor_hot_alert, Units.current.value.temp(t))
                    CarVoice.announce(line, InAppNav.locale(context))
                }
            }
            delay(HEAT_WATCH_MS)
        }
    }

    private suspend fun read(context: Context): HeadUnitStats {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val fs = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        val totalGb = fs?.let { it.totalBytes / GB } ?: 0f
        val freeGb = fs?.let { it.availableBytes / GB } ?: 0f
        val access = PrivilegedShell.access.value
        // Read directly where Android lets it; the shell lists the zones only where it does not.
        val direct = readTempDirect()
        val out = if (access.shell) {
            // Two frames: the first has no time behind it and reads 0 % everywhere.
            // Sorted on %CPU (column 2); toybox sorts on its own default column otherwise.
            shell(context, "top -b -n 2 -d 1 -s 2 -m $TOP_LINES -o PID,%CPU,RES,ARGS; echo $PS_MARK; ps -A -o ARGS=; echo $THERMAL_MARK; " +
                (if (direct == null) "for z in /sys/class/thermal/thermal_zone*; do echo \"$(cat \$z/type) $(cat \$z/temp)\"; done" else "true"), 12)
        } else null
        val parts = out?.let(::splitSections)
        val top = parts?.get(0)?.let(::lastFrame)
        val ps = parts?.get(1)
        val thermal = parts?.get(2)
        val pm = context.packageManager
        val label = { pkg: String -> appLabel(pm, pkg) }
        return HeadUnitStats(
            cpuPct = top?.let(::parseCpuPct),
            cores = Runtime.getRuntime().availableProcessors(),
            tempC = direct ?: thermal?.let(::parseThermal),
            ramUsedMb = (mem.totalMem - mem.availMem) / MB,
            ramTotalMb = mem.totalMem / MB,
            storageUsedGb = (totalGb - freeGb).coerceAtLeast(0f),
            storageTotalGb = totalGb,
            uptimeMs = SystemClock.elapsedRealtime(),
            apps = top?.let { appLoads(parseTopProcesses(it), label, context.getString(R.string.monitor_system)) }.orEmpty(),
            appCount = ps?.let { list -> list.lines().map { it.trim().substringBefore(' ').substringBefore(':') }.filter { it.contains('.') }.distinct().count { label(it) != null } }
        )
    }

    /** One command through root (a session kept open while tiles show), else the ADB shell. */
    private suspend fun shell(context: Context, cmd: String, timeoutS: Long): String? = runCatching {
        if (PrivilegedShell.access.value.root) {
            // A poller let go of mid-command and the next tile's may overlap: one command at a time.
            synchronized(this) {
                val s = session?.takeIf { it.alive } ?: RootShell.Session().also { session = it }
                s.run(cmd, timeoutS).out
            }
        } else {
            DockShell.shell(context, cmd)
        }
    }.onFailure { Log.w(TAG, "shell failed: ${it.message}") }.getOrNull()

    private val labels = HashMap<String, String?>()

    /** The app's name, or null when [pkg] is no installed app (a native process). */
    private fun appLabel(pm: PackageManager, pkg: String): String? = synchronized(labels) {
        labels.getOrPut(pkg) {
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        }
    }

    private fun readTempDirect(): Int? = runCatching {
        val zones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") } ?: return null
        parseThermal(zones.joinToString("\n") { z -> "${File(z, "type").readText().trim()} ${File(z, "temp").readText().trim()}" })
    }.getOrNull()

    private const val PS_MARK = "---DASH-PS---"
    private const val THERMAL_MARK = "---DASH-THERMAL---"

    /**
     * The command's output cut at the two marks: top, ps, the thermal zones.
     * Only a line that is the mark alone counts: the shell running the
     * command is itself in the process lists, its command line marks and all.
     */
    internal fun splitSections(out: String): List<String> {
        val parts = mutableListOf(StringBuilder(), StringBuilder(), StringBuilder())
        var at = 0
        out.lineSequence().forEach { line ->
            when (line.trim()) {
                PS_MARK -> at = 1
                THERMAL_MARK -> at = 2
                else -> parts[at].append(line).append('\n')
            }
        }
        return parts.map { it.toString() }
    }

    /** The last frame of `top -n 2`, from its last "%cpu" header on. */
    internal fun lastFrame(top: String): String {
        val lines = top.lines()
        val header = lines.indexOfLast { it.contains("%cpu") }
        return if (header < 0) top else lines.drop(header).joinToString("\n")
    }
    private const val MB = 1024L * 1024L
    private const val GB = 1024f * 1024f * 1024f

    // --- Parsing, pure for the tests --------------------------------------------------------------

    /** A process line of `top -o PID,%CPU,RES,ARGS`. */
    internal data class TopProcess(val name: String, val cpuPct: Float, val ramMb: Int)

    // Compiled once: the parsers run on every sample, over every line of top.
    private val CPU_TOTAL = Regex("(\\d+)%cpu")
    private val CPU_IDLE = Regex("(\\d+)%idle")
    private val TOP_LINE = Regex("^\\s*(\\d+)\\s+([\\d.]+)\\s+(\\S+)\\s+(.+)$")
    private val SPACES = Regex("\\s+")

    /** All cores' use from toybox top's header ("800%cpu ... 690%idle"), 0-100. */
    internal fun parseCpuPct(top: String): Int? {
        val total = CPU_TOTAL.find(top)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
        val idle = CPU_IDLE.find(top)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
        if (total <= 0f) return null
        return ((total - idle) / total * 100f).roundToInt().coerceIn(0, 100)
    }

    internal fun parseTopProcesses(top: String): List<TopProcess> = top.lines().mapNotNull { line ->
        val m = TOP_LINE.find(line) ?: return@mapNotNull null
        val name = m.groupValues[4].trim().substringBefore(' ')
        TopProcess(name, m.groupValues[2].toFloatOrNull() ?: 0f, parseMb(m.groupValues[3]))
    }

    /** "412M", "98K", "1.2G" (no unit: kilobytes) in megabytes. */
    internal fun parseMb(text: String): Int {
        val n = text.dropLastWhile { it.isLetter() }.toFloatOrNull() ?: return 0
        return when (text.lastOrNull()?.uppercaseChar()) {
            'G' -> n * 1024f
            'M' -> n
            'T' -> n * 1024f * 1024f
            else -> n / 1024f
        }.roundToInt()
    }

    /**
     * The processes per app (an app's `:service` processes counted with it),
     * busiest first; everything that is no app goes into one [systemLabel] row.
     */
    internal fun appLoads(processes: List<TopProcess>, label: (String) -> String?, systemLabel: String): List<AppLoad> {
        val apps = LinkedHashMap<String, AppLoad>()
        var systemCpu = 0f
        var systemRam = 0
        processes.forEach { p ->
            val pkg = p.name.substringBefore(':')
            val name = if (pkg.contains('.')) label(pkg) else null
            if (name == null) {
                if (p.name != "top") { systemCpu += p.cpuPct; systemRam += p.ramMb }
            } else {
                val had = apps[pkg]
                apps[pkg] = AppLoad(pkg, name, (had?.cpuPct ?: 0f) + p.cpuPct, (had?.ramMb ?: 0) + p.ramMb)
            }
        }
        val all = apps.values.toMutableList()
        if (systemCpu > 0f || systemRam > 0) all += AppLoad(null, systemLabel, systemCpu, systemRam)
        return all.sortedWith(compareByDescending<AppLoad> { it.cpuPct }.thenByDescending { it.ramMb })
    }

    /**
     * The hottest CPU or SoC zone from "type temp" lines, in °C (millidegrees
     * divided down); any plausible zone when none is named for the CPU.
     */
    internal fun parseThermal(text: String): Int? {
        val zones = text.lines().mapNotNull { line ->
            val parts = line.trim().split(SPACES)
            if (parts.size < 2) return@mapNotNull null
            val raw = parts.last().toFloatOrNull() ?: return@mapNotNull null
            val c = if (raw > 1000f) raw / 1000f else raw
            val type = parts.first().lowercase()
            // The battery and the charger are not the chip.
            if (c !in 1f..150f || NOT_CHIP.any { it in type }) null else type to c
        }
        val cpu = zones.filter { (type, _) -> "cpu" in type || "soc" in type }
        return (cpu.ifEmpty { zones }).maxOfOrNull { it.second }?.roundToInt()
    }

    private val NOT_CHIP = listOf("battery", "bat", "charger", "usb", "pmic")

    /** "Android 10" from the API level: some units report a newer release than they run. */
    fun androidName(): String = "Android " + when (Build.VERSION.SDK_INT) {
        29 -> "10"; 30 -> "11"; 31, 32 -> "12"; 33 -> "13"; 34 -> "14"; 35 -> "15"
        else -> Build.VERSION.RELEASE
    }
}
