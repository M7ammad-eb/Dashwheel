package com.openauto.dash

/*
 * Reading floating windows out of `am stack list`, and the geometry rules the
 * tracker applies to them. Pure Kotlin: no Android types, fully unit-testable.
 */

/** Screen-pixel rectangle; a plain data class so the parser is JVM-testable. */
data class ScreenRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * A floating window as reported by `am stack list`: either a pinned
 * (picture-in-picture) stack or, as this ROM prefers, a freeform one.
 */

data class FloatingWindow(
    val stackId: Int,
    val taskId: Int?,
    val packageName: String,
    val bounds: ScreenRect?,
    val mode: String,
    /** False when another stack (e.g. the dashboard's) covers it. */
    val visible: Boolean = true,
    /** True when the dashboard's own stack is listed in front of this one (partly covering it). */
    val behindDashboard: Boolean = false,
    /** The display the window is on (0 is the screen). */
    val displayId: Int = 0,
    /** True when the window is on another display than the dashboard: parked on the hidden one, out of sight. */
    val offDisplay: Boolean = false
)

object WindowListing {

    /** Managed freeform windows whose tile is not on screen: they should not exist. */
    internal fun strayWindows(listing: String, managed: Set<String>, active: Set<String>): List<FloatingWindow> =
        strayWindows(allFloatingWindows(listing), managed, active)

    /** [strayWindows] of a listing already parsed ([allFloatingWindows]). */
    internal fun strayWindows(windows: List<FloatingWindow>, managed: Set<String>, active: Set<String>): List<FloatingWindow> =
        windows.filter { it.mode == "freeform" && it.packageName in managed && it.packageName !in active }

    /** True when [b] lies inside [limit] (a few pixels of slack for rounding). */
    internal fun withinArea(b: ScreenRect, limit: ScreenRect?, slack: Int = 4): Boolean =
        limit == null || (b.top >= limit.top - slack && b.bottom <= limit.bottom + slack &&
            b.left >= limit.left - slack && b.right <= limit.right + slack)

    /**
     * Where to centre a passing label that sits at [at] so no window of [windows]
     * covers it (they are drawn above the dashboard): its own centre when none
     * does, else the centre of the widest stretch of [area] those windows leave
     * clear at its height. Null when no stretch is wide enough for it.
     */
    internal fun clearCentreX(at: ScreenRect, area: ScreenRect, windows: Collection<ScreenRect>): Int? {
        if (windows.none { overlaps(it, at) }) return (at.left + at.right) / 2
        val blocking = windows.filter { it.top < at.bottom && at.top < it.bottom }.sortedBy { it.left }
        var best: IntRange? = null
        var from = area.left
        for (w in blocking + ScreenRect(area.right, 0, area.right, 0)) {
            if (w.left - from > (best?.let { it.last - it.first } ?: -1)) best = from..w.left
            from = maxOf(from, w.right)
        }
        val gap = best ?: return null
        return if (gap.last - gap.first >= at.right - at.left) (gap.first + gap.last) / 2 else null
    }

    /** True when [a] and [b] overlap; within [margin] px of each other counts too (a pop-up's shadow). */
    internal fun overlaps(a: ScreenRect, b: ScreenRect, margin: Int = 0): Boolean =
        a.left - margin < b.right && b.left - margin < a.right &&
            a.top - margin < b.bottom && b.top - margin < a.bottom

    /**
     * Moves [b] so it fits in [limit] without changing its size. If it is too
     * tall, the bottom edge wins (the bar must stay clear) and the top overflows.
     */

    internal fun keepInside(b: ScreenRect, limit: ScreenRect): ScreenRect {
        var dx = 0
        var dy = 0
        if (b.right > limit.right) dx = limit.right - b.right
        if (b.left + dx < limit.left) dx = limit.left - b.left
        if (b.bottom > limit.bottom) dy = limit.bottom - b.bottom
        if (b.top + dy < limit.top && b.bottom - b.top <= limit.bottom - limit.top) dy = limit.top - b.top
        return ScreenRect(b.left + dx, b.top + dy, b.right + dx, b.bottom + dy)
    }

    /**
     * True while a dialog or the app drawer is open. Docked windows are drawn
     * above everything on this head unit, so they would cover the dialog; the
     * tiles push their windows into the bottom-right corner meanwhile and dock them
     * again afterwards.
     */

    internal fun isClose(actual: ScreenRect, target: ScreenRect): Boolean {
        val cx = (actual.left + actual.right) / 2
        val cy = (actual.top + actual.bottom) / 2
        val insideX = cx in target.left..target.right
        val insideY = cy in target.top..target.bottom
        val w = (actual.right - actual.left).toFloat()
        val tw = (target.right - target.left).toFloat().coerceAtLeast(1f)
        return insideX && insideY && w / tw in 0.6f..1.4f
    }

    internal fun parseFloatingWindow(output: String, selfPackage: String = "com.openauto.dash", packageName: String? = null): FloatingWindow? =
        pickFloatingWindow(allFloatingWindows(output, selfPackage), packageName)

    /** [packageName]'s window among [windows] (any app's when null); a listing already parsed. */
    internal fun pickFloatingWindow(windows: List<FloatingWindow>, packageName: String?): FloatingWindow? {
        val found = windows.filter { packageName == null || it.packageName == packageName }
        // A freeform window carries the full app UI; prefer it over a PiP.
        return found.firstOrNull { it.mode == "freeform" } ?: found.firstOrNull()
    }

    /** Every pinned or freeform window in the listing, front to back, ours excluded. */
    internal fun allFloatingWindows(output: String, selfPackage: String = "com.openauto.dash"): List<FloatingWindow> {
        val found = mutableListOf<FloatingWindow>()
        // `am stack list` is ordered front to back: the dashboard's own stack
        // appearing before the window's means the dashboard is drawn over it.
        val blocks = stackBlocks(output)
        val selfIndex = blocks.indexOfFirst { holds(it, selfPackage) }
        // Stacks are listed display by display; only one on the dashboard's own
        // display can be in front of it. A window parked on the hidden display
        // is listed after everything on the screen, but it covers nothing.
        val selfDisplay = blocks.getOrNull(selfIndex)?.let { displayId(it) } ?: DEFAULT_DISPLAY
        for ((index, block) in blocks.withIndex()) {
            val mode = windowingMode(block) ?: continue
            if (mode != "pinned" && mode != "freeform") continue
            if (block.contains("ActivityType=home")) continue
            val id = block.takeWhile { it.isDigit() }.toIntOrNull() ?: continue
            val task = topTask(block) ?: continue
            val pkg = task.groupValues[2]
            if (pkg == selfPackage) continue
            // The stack's own bounds line comes first and, for freeform, spans
            // the whole display; the window's bounds are on the task line.
            val taskLine = block.substring(task.range.first).lineSequence().first()
            val b = (BOUNDS.find(taskLine) ?: BOUNDS.find(block))?.groupValues
            val bounds = b?.let { ScreenRect(it[1].toInt(), it[2].toInt(), it[3].toInt(), it[4].toInt()) }
            val visible = !taskLine.contains("visible=false")
            val display = displayId(block) ?: DEFAULT_DISPLAY
            val offDisplay = display != selfDisplay
            val behind = !offDisplay && selfIndex in 0 until index
            found += FloatingWindow(id, task.groupValues[1].toIntOrNull(), pkg, bounds, mode, visible, behind, display, offDisplay)
        }
        return found
    }

    /**
     * [packageName]'s front-most stack in the listing, whatever its windowing
     * mode and display: (stack id, display id). What the second screen moves
     * onto its display, and back.
     */
    internal fun stackOf(output: String, packageName: String): Pair<Int, Int>? {
        for (block in stackBlocks(output)) {
            if (block.contains("ActivityType=home")) continue
            if (!holds(block, packageName)) continue
            val id = block.takeWhile { it.isDigit() }.toIntOrNull() ?: continue
            return id to (displayId(block) ?: DEFAULT_DISPLAY)
        }
        return null
    }

    /**
     * The id of a fullscreen stack for ordinary apps on the dashboard's own
     * display: the one holding the dashboard's task when it is listed, else any
     * other. A floating task moved there leaves freeform and shows full screen,
     * still running. Null when the listing has no such stack (the Home stack
     * never counts: it only takes home activities).
     */
    internal fun fullscreenStackId(output: String, selfPackage: String = "com.openauto.dash"): Int? {
        val blocks = stackBlocks(output)
        val selfDisplay = blocks.firstOrNull { holds(it, selfPackage) }
            ?.let { displayId(it) } ?: DEFAULT_DISPLAY
        val candidates = blocks.filter {
            windowingMode(it) == "fullscreen" && !it.contains("ActivityType=home") &&
                (displayId(it) ?: DEFAULT_DISPLAY) == selfDisplay
        }
        val block = candidates.firstOrNull { holds(it, selfPackage) }
            ?: candidates.firstOrNull()
        return block?.takeWhile { it.isDigit() }?.toIntOrNull()
    }

    /**
     * The task in front on display [displayId] (the screen): raising it puts
     * that display back in front without changing what shows on it. The
     * pinned stack is left out, it is always on top anyway. Null when the
     * listing shows none.
     */
    internal fun frontTask(output: String, displayId: Int = DEFAULT_DISPLAY): Int? =
        stackBlocks(output).firstNotNullOfOrNull { block ->
            if ((displayId(block) ?: DEFAULT_DISPLAY) != displayId || windowingMode(block) == "pinned") return@firstNotNullOfOrNull null
            topTask(block)?.groupValues?.get(1)?.toIntOrNull()
        }

    /**
     * [packageName]'s task when it is the front-most fullscreen one on the
     * dashboard's display, i.e. the app covers the whole screen, dashboard
     * included; null otherwise. Floating windows above it do not count: they
     * never hide the dashboard.
     */
    internal fun fullscreenInFront(output: String, packageName: String, selfPackage: String = "com.openauto.dash"): FloatingWindow? {
        val blocks = stackBlocks(output)
        val selfDisplay = blocks.firstOrNull { holds(it, selfPackage) }
            ?.let { displayId(it) } ?: DEFAULT_DISPLAY
        val front = blocks.firstOrNull {
            windowingMode(it) == "fullscreen" && (displayId(it) ?: DEFAULT_DISPLAY) == selfDisplay
        } ?: return null
        val task = topTask(front) ?: return null
        if (task.groupValues[2] != packageName) return null
        val taskLine = front.substring(task.range.first).lineSequence().first()
        if (taskLine.contains("visible=false")) return null
        val id = front.takeWhile { it.isDigit() }.toIntOrNull() ?: return null
        val b = BOUNDS.find(taskLine)?.groupValues
        val bounds = b?.let { ScreenRect(it[1].toInt(), it[2].toInt(), it[3].toInt(), it[4].toInt()) }
        return FloatingWindow(id, task.groupValues[1].toIntOrNull(), packageName, bounds, "fullscreen", displayId = selfDisplay)
    }

    /** Android's id for the screen itself (`Display.DEFAULT_DISPLAY`, kept out of the Android types here). */
    const val DEFAULT_DISPLAY = 0

    /**
     * Displays that show an app inside a tile (EmbeddedApp). What runs there is
     * not a window of the dashboard: it is left out of every listing, so it is
     * never parked, closed or brought back onto the screen as if it were one.
     */
    @Volatile
    var embeddedDisplays: Set<Int> = emptySet()

    /**
     * One stack holding an app's task, wherever it is (tile displays included).
     * [visible] is false when the listing says the task is hidden, behind
     * another one on its display. [taskId] is the app's task in it. [empty]
     * when the stack holds no screen at all: what a task keeps after its
     * process ended without saving its screens (Waze on a Hizpo, issue #60).
     */
    data class AppStack(
        val stackId: Int,
        val displayId: Int,
        val mode: String?,
        val visible: Boolean = true,
        val taskId: Int? = null,
        val empty: Boolean = false
    )

    /**
     * Where one stack's block starts in the listing: "Stack id=" up to
     * Android 11, "RootTask id=" from Android 12 on. Read on the old word
     * alone, a newer unit's listing held no stack at all: every tile then
     * took its app for not running, started it again and fought it (a Joying
     * on Android 13: Google Maps lagging, two tiles squashed).
     */
    private val BLOCK_START = Regex("(?m)^\\s*(?:Stack|RootTask) id=")

    /**
     * Every stack with a task of [packageName], on every display, the tiles'
     * own ones too. A task line without "topActivity=" is a stack with no
     * screen left ([AppStack.empty]), but only where the listing names one
     * for other stacks: some builds never print it.
     */
    internal fun appStacks(output: String, packageName: String): List<AppStack> {
        val namesTops = output.contains(TOP_ACTIVITY)
        return output.split(BLOCK_START).drop(1).mapNotNull { block ->
            val task = TASK.findAll(block).firstOrNull { it.groupValues[2] == packageName } ?: return@mapNotNull null
            val id = block.takeWhile { it.isDigit() }.toIntOrNull() ?: return@mapNotNull null
            val taskLine = block.substring(task.range.first).lineSequence().first()
            AppStack(
                id, displayId(block) ?: DEFAULT_DISPLAY, windowingMode(block), !taskLine.contains("visible=false"),
                task.groupValues[1].toIntOrNull(), empty = namesTops && !taskLine.contains(TOP_ACTIVITY)
            )
        }
    }

    private const val TOP_ACTIVITY = "topActivity="

    /**
     * The app [packageName] handed its screen to, from listings [before] and
     * [after] it was started on a tile: the task now in front off the tiles'
     * displays, new or newly raised, of another app. Gemini's icon is only a
     * hand-off: its screen opens in the Google app, full screen on the main
     * screen, where the tile looking for Gemini's own windows never saw it.
     * Null when nothing else came to the front, or only an app of [others]
     * (another tile's, started at the same time).
     */
    internal fun handedTo(
        before: String,
        after: String,
        packageName: String,
        selfPackage: String,
        others: Set<String> = emptySet()
    ): String? {
        val was = frontTasks(before, selfPackage)
        val (taskId, pkg) = frontTasks(after, selfPackage).firstOrNull() ?: return null
        if (pkg == packageName || pkg == selfPackage || pkg == SYSTEM_UI || pkg in others) return null
        return pkg.takeIf { taskId != was.firstOrNull()?.first }
    }

    /** Each stack's top task (id, package), front first, on the screen [selfPackage] is on. */
    private fun frontTasks(output: String, selfPackage: String): List<Pair<Int, String>> {
        val blocks = stackBlocks(output)
        val selfDisplay = blocks.firstOrNull { holds(it, selfPackage) }
            ?.let { displayId(it) } ?: DEFAULT_DISPLAY
        return blocks.filter { (displayId(it) ?: DEFAULT_DISPLAY) == selfDisplay }.mapNotNull { block ->
            val task = topTask(block) ?: return@mapNotNull null
            val id = task.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            id to task.groupValues[2]
        }
    }

    /**
     * The docked (split-screen primary) stack to close when the firmware left a
     * split on with the dashboard on the whole screen ([StuckSplit]), on the
     * dashboard's display. Null when there is none, or when it is the
     * dashboard's own: closing that would close the dashboard.
     */
    internal fun dockedStackToClose(output: String, selfPackage: String): Int? {
        val blocks = stackBlocks(output)
        val selfDisplay = blocks.firstOrNull { holds(it, selfPackage) }
            ?.let { displayId(it) } ?: DEFAULT_DISPLAY
        val docked = blocks.firstOrNull { block ->
            (displayId(block) ?: DEFAULT_DISPLAY) == selfDisplay &&
                (MODE_NAME.find(block)?.groupValues?.get(1) == "split-screen-primary" ||
                    MODE_NUMBER.find(block)?.groupValues?.get(1) == "3")
        } ?: return null
        if (holds(docked, selfPackage)) return null
        return docked.takeWhile { it.isDigit() }.toIntOrNull()
    }

    private const val SYSTEM_UI = "com.android.systemui"

    /** The display a stack block says it is on, from its `Stack id=N ... displayId=N` line. */
    private fun displayId(block: String): Int? =
        DISPLAY.find(block.lineSequence().first())?.groupValues?.get(1)?.toIntOrNull()

    /** "mode package" per stack, for the tile's diagnostic line. */
    internal fun summarizeStacks(output: String): String? {
        val parts = stackBlocks(output).mapNotNull { block ->
            val mode = windowingMode(block) ?: return@mapNotNull null
            val pkg = topTask(block)?.groupValues?.get(2) ?: "(empty)"
            mode + " " + pkg.substringAfterLast('.')
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" \u00b7 ")
    }

    private val TASK = Regex("taskId=(\\d+): ([\\w.]+)/")

    /**
     * The task on top of a stack. A stack can hold several tasks (the Home
     * stack: the dashboard over a stock launcher Android started at boot), and
     * `am stack list` names them bottom first: the last one is the one shown.
     */
    private fun topTask(block: String): MatchResult? = TASK.findAll(block).lastOrNull()

    /** Whether one of the stack's tasks is [packageName]'s, at any depth. */
    private fun holds(block: String, packageName: String): Boolean = TASK.findAll(block).any { it.groupValues[2] == packageName }

    /** A task line: its id, its first screen, and the one on top ("topActivity=ComponentInfo{pkg/cls}"). */
    private val TASK_SCREENS = Regex("taskId=(\\d+): (\\S+) .*?topActivity=ComponentInfo\\{([^}]+)\\}")

    /**
     * Whether task [taskId] shows a screen over its first one: Waze's voice
     * prompt, its search, a report, a permission request. Moving the task
     * starts that screen over on the tile; one that then jumps out again and
     * again is let be where it opened ([EmbeddedApp]).
     */
    internal fun showsOwnScreen(output: String, taskId: Int): Boolean = ownScreenOf(output, taskId) != null

    /** The screen task [taskId] shows over its first one ("pkg/cls"), or null when none ([showsOwnScreen]). */
    internal fun ownScreenOf(output: String, taskId: Int): String? =
        TASK_SCREENS.findAll(output).firstOrNull { it.groupValues[1].toIntOrNull() == taskId }
            ?.takeIf { it.groupValues[2] != it.groupValues[3] }?.groupValues?.get(3)

    /** Whether [screen] ("pkg/cls") listens for speech: Waze's voice prompt ("SpeechRecognizerActivity"). */
    internal fun isVoiceScreen(screen: String): Boolean =
        screen.substringAfterLast('.').let { it.contains("Speech", ignoreCase = true) || it.contains("Voice", ignoreCase = true) }

    private val BOUNDS = Regex("bounds=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]")

    private val DISPLAY = Regex("displayId=(\\d+)")

    private val MODE_NAME = Regex("(?:indowingMode|winMode)=([a-z-]+)")

    private val MODE_NUMBER = Regex("indowingMode=(\\d)")

    private fun stackBlocks(output: String): List<String> {
        val blocks = output.split(BLOCK_START).drop(1)
        val embedded = embeddedDisplays
        return if (embedded.isEmpty()) blocks else blocks.filter { displayId(it) !in embedded }
    }

    private fun windowingMode(block: String): String? {
        MODE_NAME.find(block)?.let { return it.groupValues[1] }
        // Some builds print the numeric mode: 1 fullscreen, 2 pinned, 5 freeform.
        MODE_NUMBER.find(block)?.let {
            return when (it.groupValues[1]) { "1" -> "fullscreen"; "2" -> "pinned"; "5" -> "freeform"; else -> "other" }
        }
        return null
    }
}
