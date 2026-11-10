package com.openauto.dash

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Docks the system picture-in-picture window (Google Maps guidance after
 * pressing Home) onto a dashboard tile.
 *
 * No app can embed another app's PiP window: SystemUI draws it above
 * everything. What we *can* do on this head unit is tell the system where the
 * pinned stack should be, through the internal ADB socket (`am stack resize`,
 * a shell-uid command, no root needed). The [PipAnchorCard] measures its own
 * screen rectangle and, while it is on screen, keeps the PiP window sized and
 * positioned to match; when the tile scrolls away the window is parked
 * aside, still running, so a navigation or a song carries on meanwhile.
 */
object PipAnchor {

    private const val TAG = "PipAnchor"

    const val MAPS_PACKAGE = "com.google.android.apps.maps"

    private const val POLL_MS = 2_500L

    /**
     * The pace once a window has sat still for [IDLE_AFTER_POLLS] polls: every
     * `am stack list` is an ADB round trip for each window tile, forever. Any
     * sign that things may move (see [pollAgainSoon]) brings back [POLL_MS].
     * Kept short: Maps killed for memory, dragged by hand or opened by voice
     * raises none of those signs and is only seen by the next poll.
     */
    private const val IDLE_POLL_MS = 5_000L
    private const val IDLE_AFTER_POLLS = 4

    /** Bumped by events that may move a window (a touch, the dashboard coming back); see [idlePause]. */
    private val wakeups = MutableStateFlow(0L)

    /** Something may move a window soon: idle trackers poll at the quick pace again, starting now. */
    fun pollAgainSoon() {
        wakeups.update { it + 1 }
    }

    /**
     * Waits out an idle poll interval, cut short by anything that may move a
     * window: a touch, a page swipe, the dashboard area or the dock changing,
     * the windows stepping aside. True when cut short.
     */
    private suspend fun idlePause(): Boolean = withTimeoutOrNull(IDLE_POLL_MS) {
        combine(wakeups, pageSwiping, allowedArea, dockActive, steppedAside) { a, b, c, d, e -> listOf(a, b, c, d, e) }
            .drop(1) // the values right now; only a change counts
            .first()
    } != null

    /** What the tile shows. [pipPackage] is null while no PiP window exists. */
    data class Status(
        val pipPackage: String? = null,
        val docked: Boolean = false,
        val error: String? = null,
        /** "pinned" or "freeform": how the window is floating. */
        val mode: String? = null,
        /** One entry per stack the system reported, for on-tile diagnostics. */
        val seen: String? = null,
        /** Where the system says the window is, and where the tile wants it. */
        val windowBounds: ScreenRect? = null,
        val target: ScreenRect? = null,
        /** Outcome of the last placement command, e.g. "am stack resize 3: ok". */
        val lastResult: String? = null,
        /** Set once the tile has stopped fighting a system that keeps moving the window back. */
        val gaveUp: Boolean = false,
        /** The window's size (w, h px) when the system made it clearly larger than the tile (its minimum size). */
        val oversizePx: Pair<Int, Int>? = null,
        /** When a poll last looked ([SystemClock.elapsedRealtime] ms): a status not refreshed for a while is stale, e.g. while parked aside. */
        val checkedAt: Long = 0L,
        /** The listing's visibility flag, and whether it puts the dashboard in front of the window. */
        val visible: Boolean? = null,
        val behindDashboard: Boolean? = null
    )

    // One status per docked app: several tiles (Maps, YouTube Music, ...) can
    // each own a window at the same time.
    private val statuses = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<Status>>()

    private fun statusFlow(packageName: String) = statuses.getOrPut(packageName) { MutableStateFlow(Status()) }

    fun statusOf(packageName: String): StateFlow<Status> = statusFlow(packageName)

    /** Packages currently docked as freeform windows (drives the status-bar inset). */
    val dockedPackages = MutableStateFlow<Set<String>>(emptySet())

    /** Screen areas of the windows docked on their tiles right now, for what the dashboard draws that they would cover. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val dockedAreas: kotlinx.coroutines.flow.Flow<List<ScreenRect>> = dockedPackages.flatMapLatest { pkgs ->
        if (pkgs.isEmpty()) flowOf(emptyList())
        else combine(pkgs.map { statusFlow(it) }) { all -> all.mapNotNull { s -> s.windowBounds?.takeIf { s.docked } } }
    }

    private val freeformNow = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Packages a tile has tracked in this process. Together with the persisted
     * auto-open intent this is what "managed" means: windows of these apps are
     * ours to close when no tile shows them.
     */

    private val managed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * How many tiles currently show each app, counted from composition (a tile
     * enters / leaves the screen), not from the tracking loop: the loop restarts
     * on every re-target and a window must not look ownerless meanwhile.
     */

    private val tileCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun tileShown(packageName: String) {
        tileCounts.merge(packageName, 1, Int::plus)
        managed.add(packageName)
        lastReopenAt.remove(packageName) // back on screen: reopen at once if needed
        lastRaiseAt.remove(packageName) // and raise a window parked behind the dashboard at once
        pollAgainSoon()
    }

    fun tileHidden(packageName: String) {
        tileCounts.compute(packageName) { _, n -> if (n == null || n <= 1) null else n - 1 }
    }

    private fun activePackages(): Set<String> = tileCounts.keys.toSet()

    private fun managedPackages(context: Context): Set<String> = managed + autoOpenPackages(context)

    /**
     * Apps with a window tile anywhere in the dashboard, on any page (set by the
     * dashboard; null until it has). Their windows outlive a page change: they
     * are parked aside, still running, instead of being closed, so a Maps
     * navigation or a YouTube Music song carries on on the other pages.
     */
    val placedPackages = MutableStateFlow<Set<String>?>(null)

    /** Unknown yet counts as placed: closing a window by mistake loses the app's state. */
    private fun isPlaced(packageName: String): Boolean = placedPackages.value?.contains(packageName) ?: true

    /** Windows parked out of sight (off-page, or out of a dialog's way): alive, but not on the dashboard. */
    private val parked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** The window is back (or gone): the parked corner's cover goes once no window is left there. */
    private fun unpark(context: Context, packageName: String) {
        if (parked.remove(packageName) && parked.isEmpty()) ParkedCover.hide(context) { parked.isNotEmpty() }
    }

    /** Freeform windows actually showing on the dashboard (not parked aside). */
    private fun onScreenWindows(): Set<String> = freeformNow - parked

    /**
     * Safety net for windows whose tile is not on screen: parked aside while
     * their app still has a tile on another page, closed once it has none.
     */
    private suspend fun handleStrays(context: Context, listing: String) {
        // The windows [lookUp] just parsed out of this very listing, when it is the same text.
        val windows = lastParsed?.takeIf { it.first == listing }?.second ?: WindowListing.allFloatingWindows(listing, context.packageName)
        for (stray in WindowListing.strayWindows(windows, managedPackages(context), activePackages())) {
            if (isLent(stray.packageName)) continue
            if (isPlaced(stray.packageName)) park(context, stray, "no tile on screen")
            else closeWindow(context, stray, "no tile left")
        }
    }

    /**
     * [packageName] went inside a tile (EmbeddedApp), which takes whatever
     * window it had: it no longer counts as docked. Otherwise, back from a
     * layout with Maps docked beside the pages, the status bar stayed forced
     * over the dashboard until the next page change.
     */
    fun takenInside(context: Context, packageName: String) {
        if (packageName !in freeformNow && packageName !in parked) return
        scope.launch { forgetWindow(context, packageName) }
    }

    /** Forgets windows that are gone from [listing] (e.g. a parked one the system closed). */
    private fun syncFreeform(context: Context, listing: String) {
        val present = WindowListing.allFloatingWindows(listing).filter { it.mode == "freeform" }.map { it.packageName }.toSet()
        for (pkg in freeformNow.toList()) if (pkg !in present) {
            unpark(context, pkg)
            noteFreeform(pkg, false)
        }
    }

    /** Windows we closed ourselves; their disappearance must not count as "the user closed it". */
    private val expectedGone = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * The one way a window is closed: removes its stack, forgets its state, and
     * gives the dashboard its focus back when no docked window is left. Every
     * close path goes through here so they cannot drift apart.
     */

    private suspend fun closeWindow(context: Context, win: FloatingWindow, reason: String) {
        forgetWindow(context, win.packageName)
        val out = runGuarded { DockShell.shell(context, "am stack remove ${win.stackId}") }.getOrElse { "failed: ${it.message}" }
        Log.i(TAG, "closed ${win.packageName} ($reason): ${out.trim()}")
    }

    /**
     * The window of [packageName] is gone by our own doing (closed, or moved out
     * of freeform): its state is dropped, its disappearance is expected rather
     * than "the user closed it", and the dashboard takes its focus back when no
     * docked window is left.
     */
    private suspend fun forgetWindow(context: Context, packageName: String) {
        expectedGone.add(packageName)
        unpark(context, packageName)
        noteFreeform(packageName, false)
        statusFlow(packageName).value = Status(seen = lastSeen)
        if (onScreenWindows().isEmpty()) setDashboardFocusable(context, true)
    }

    // --- Lending windows to a split ------------------------------------------
    //
    // A split pair (Maps + YouTube Music side by side) and the tiles' windows
    // compete for the same apps. Launched on top of a tile's window, the pair
    // never split: Android reused the app's freeform task, so there was no
    // full-screen task for SystemUI to dock, and the dashboard, stopped by the
    // apps coming to the front, parked both windows into the bottom-right
    // corner as it does for any other app in front. So before a pair launches,
    // its apps' windows are taken away from the tiles (moved out of freeform,
    // still running, or closed when the system refuses) and the apps are lent
    // to the split for a while: meanwhile no tile parks, nudges or reopens
    // them. Once the loan ends and the dashboard is back on screen, a tile
    // whose app should live in it opens it again as usual.

    /** How long the tiles leave a pair's apps alone after a split launch: the launch itself takes about three seconds. */
    private const val SPLIT_LEND_MS = 8_000L

    /**
     * Package -> when its loan to a split ends ([SystemClock.elapsedRealtime]
     * ms, as every cooldown here: the unit's clock is set by GPS or NTP
     * mid-drive, and a step would end a loan at once or freeze it).
     */
    private val lentUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * True while [packageName] belongs to a split launch, runs inside a tile
     * (EmbeddedApp) or is shown on the second screen (SecondScreenController),
     * and the window tiles must leave it alone: an app has one task, so
     * opening its window would pull it out of there.
     */
    fun isLent(packageName: String): Boolean {
        if (EmbeddedApp.holds(packageName)) return true
        if (packageName in SecondScreenController.heldPackages.value) return true
        val until = lentUntil[packageName] ?: return false
        if (SystemClock.elapsedRealtime() < until) return true
        lentUntil.remove(packageName, until)
        return false
    }

    /**
     * Hands [packages]' windows over to a split about to be launched: each app
     * is lent for [SPLIT_LEND_MS], and any freeform window it has (docked on a
     * tile or parked aside) is moved into the fullscreen stack, so that the
     * launch that follows opens the app full screen rather than raising the
     * floating window. A window the system will not move is closed instead:
     * the pair then starts that app afresh. Returns once the windows are dealt
     * with, so the caller can launch straight after.
     */
    suspend fun lendToSplit(context: Context, packages: Collection<String>) {
        val now = SystemClock.elapsedRealtime()
        for (pkg in packages) {
            lentUntil[pkg] = now + SPLIT_LEND_MS
            lastReopenAt[pkg] = now
        }
        // A park under way would push the window we are about to move.
        for (pkg in packages) parking[pkg]?.cancel()
        val listing = runGuarded { DockShell.listStacks(context) }.getOrElse {
            Log.w(TAG, "lend to split: no stack listing", it)
            return
        }
        syncFreeform(context, listing)
        val windows = WindowListing.allFloatingWindows(listing, context.packageName)
            .filter { it.mode == "freeform" && it.packageName in packages }
        if (windows.isEmpty()) return
        val fullscreen = WindowListing.fullscreenStackId(listing, context.packageName)
        for (win in windows) {
            val moved = fullscreen != null && runGuarded { DockShell.moveTask(context, win, fullscreen) }
                .onSuccess { Log.i(TAG, "${win.packageName} lent to a split: $it") }
                .onFailure { Log.w(TAG, "${win.packageName} could not leave freeform for the split", it) }
                .isSuccess
            if (moved) forgetWindow(context, win.packageName)
        }
        // Whatever still floats after that (the move was refused, or the ROM kept
        // the window floating anyway) is closed: a fresh full-screen start is the
        // one way left for the pair to come up split.
        val after = runGuarded { DockShell.listStacks(context) }.getOrNull() ?: return
        for (win in WindowListing.allFloatingWindows(after, context.packageName)) {
            if (win.mode == "freeform" && win.packageName in packages) closeWindow(context, win, "lent to a split")
        }
    }

    /**
     * The way a window leaves the dashboard without being closed: moved onto
     * the launcher's [HiddenDisplay], where nothing of it shows and nothing
     * needs covering. The app keeps running and drawing; the tile moves the
     * window back onto the screen when it is on screen again. Only when that
     * display cannot be used (a shell without the permission, a ROM that
     * refuses) is the window pushed into the bottom-right corner instead, with
     * [ParkedCover] over the 48 × 32 dp the system keeps in view.
     */

    private suspend fun park(context: Context, win: FloatingWindow, reason: String) {
        if (win.mode == "freeform") noteFreeform(win.packageName, true)
        parked.add(win.packageName)
        if (onScreenWindows().isEmpty()) setDashboardFocusable(context, true)
        if (win.offDisplay) return // already out of sight
        // Picture-in-picture is SystemUI's own window, moved between displays by
        // nobody; it is parked small in the corner as before.
        val hidden = if (win.mode == "freeform") HiddenDisplay.acquire(context) else null
        if (hidden != null && moveOntoHiddenDisplay(context, win, hidden, reason)) {
            FreeformBar.offScreen(context, win.packageName)
            keysBackToScreen(context)
            return
        }
        parkInCorner(context, win, reason)
    }

    /**
     * A stack moved onto the hidden display puts that display in front, and
     * the unit's keys go to the display in front: with the FM radio full
     * screen and its window parked, Home did nothing until an app opened on
     * the screen. The screen's front task is raised again, which changes
     * nothing that shows. Dashwheel may be in the background by then; its
     * overlay permission is what lets it raise another app's task.
     */
    private suspend fun keysBackToScreen(context: Context) {
        DockShell.forgetListing()
        val front = runGuarded { WindowListing.frontTask(DockShell.listStacks(context)) }.getOrNull() ?: return
        grantOverlayPermission(context)
        runGuarded {
            context.getSystemService(ActivityManager::class.java)
                .moveTaskToFront(front, ActivityManager.MOVE_TASK_NO_USER_ACTION)
        }.onSuccess { Log.i(TAG, "keys back to the screen (task $front raised)") }
            .onFailure { Log.w(TAG, "can't give the keys back to the screen", it) }
        DockShell.forgetListing()
    }

    /** One park at a time per app: a page change parks the same window from the tile and from the pager at once. */
    private val parkLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    /**
     * Moves [win] onto the hidden display; true once it is there. A page change
     * asks twice for the same window (the tile going away and the pager both
     * park it), each from its own listing, so the second ask used to find the
     * window already moved and its move refused, and two refusals gave the
     * hidden display up for good. So asks are taken one at a time, each looks
     * again before moving, and a refusal is only held against the display when
     * a fresh look shows the window still on the screen.
     */
    private suspend fun moveOntoHiddenDisplay(context: Context, win: FloatingWindow, hidden: Int, reason: String): Boolean =
        parkLocks.getOrPut(win.packageName) { Mutex() }.withLock {
            DockShell.forgetListing()
            val now = runGuarded { findFloatingWindow(context, win.packageName) }.getOrNull() ?: return true // gone: nothing to park
            if (now.offDisplay) return true
            val moved = runGuarded { DockShell.moveToDisplay(context, now, hidden) }
            if (moved.isSuccess) {
                HiddenDisplay.noteMoveSucceeded()
                Log.i(TAG, "${win.packageName} parked on the hidden display ($reason)")
                return true
            }
            DockShell.forgetListing()
            val after = runGuarded { findFloatingWindow(context, win.packageName) }.getOrNull()
            if (after == null || after.offDisplay) {
                Log.i(TAG, "${win.packageName} is on the hidden display after all ($reason)")
                return true
            }
            HiddenDisplay.noteMoveFailed(moved.exceptionOrNull()!!)
            false
        }

    /** The fallback park: the corner, with the cover over what still shows. */
    private suspend fun parkInCorner(context: Context, win: FloatingWindow, reason: String) {
        val b = win.bounds ?: return
        val dm = context.resources.displayMetrics
        val left = dm.widthPixels - ASIDE_SLIVER_PX
        val top = dm.heightPixels - ASIDE_SLIVER_PX
        if (b.left < left || b.top < top) {
            if (win.mode == "freeform") FreeformBar.onScreen(context, win.packageName, top)
            runGuarded { DockShell.resize(context, win, ScreenRect(left, top, left + (b.right - b.left), top + (b.bottom - b.top))) }
                .onFailure { Log.w(TAG, "park aside failed", it) }
            Log.i(TAG, "${win.packageName} parked in the corner ($reason)")
        }
        if (win.packageName in parked && grantOverlayPermission(context)) ParkedCover.show(context) { parked.isNotEmpty() }
    }

    @Synchronized

    private fun noteFreeform(packageName: String, present: Boolean) {
        if (present) freeformNow.add(packageName) else freeformNow.remove(packageName)
        dockedPackages.value = freeformNow.toSet()
    }

    /**
     * Screen areas of our own pop-ups (menus, dialogs) on screen right now, by
     * owner. Docked windows are drawn above them, so a window whose tile
     * overlaps one steps aside meanwhile; windows elsewhere stay open.
     */
    val coveredAreas = MutableStateFlow<Map<Any, ScreenRect>>(emptyMap())

    /** runCatching that never swallows coroutine cancellation. */
    private inline fun <T> runGuarded(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    /**
     * Whether [packageName] has no process left. Closing its window leaves the
     * app running; a crash or the system killing it does not. A shell that
     * can't be asked counts as "still running": the window is then taken as
     * closed by the driver, as before.
     */
    private suspend fun processGone(context: Context, packageName: String): Boolean =
        // "|| true": pidof finding nothing ends with an error code, which the
        // root shell reports as a failed command. The answer wanted is its
        // empty output, or a killed Maps counted as closed by the driver.
        runGuarded { DockShell.shell(context, "pidof $packageName || true") }
            .map { out -> processGone(out, packageName) }
            .getOrDefault(false)

    /**
     * [out] of `pidof`: nothing at all (the shell's echo of the command aside)
     * means no process. A process id means running; anything else is an error,
     * which proves nothing, so it counts as running.
     */
    internal fun processGone(out: String, packageName: String): Boolean =
        out.lines().map { it.trim() }.none { it.isNotEmpty() && !it.contains(packageName) }

    /** True while the "Maps left" layout's permanent dock is on screen. */
    val dockActive = MutableStateFlow(false)

    /**
     * The dashboard's content area in screen pixels (pages, without the bar).
     * Windows are kept inside it: the ROM enforces a minimum size per app, so a
     * window can come out taller than its tile, and it must then be moved up
     * rather than be allowed to cover the launcher bar.
     */

    val allowedArea = MutableStateFlow<ScreenRect?>(null)

    /** Every docked window steps aside (the app drawer is open). */
    val steppedAside = MutableStateFlow(false)

    /** The pages are being swiped: windows on a page step aside with it; a dock beside the pages stays. */
    val pageSwiping = MutableStateFlow(false)

    /** Park requests in flight, one per app: a tile asking again meanwhile has nothing to add. */
    private val parking = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /**
     * Takes [packageName]'s window out of sight (see [park]) while a pop-up is
     * over its tile. One park at a time per app: a second request while one is
     * under way would only repeat its round trips.
     */
    fun parkAside(context: Context, packageName: String = MAPS_PACKAGE) {
        if (isLent(packageName)) return
        parking.compute(packageName) { _, running ->
            if (running?.isActive == true) running
            else scope.launch {
                val win = runGuarded { findFloatingWindow(context, packageName) }.getOrNull() ?: return@launch
                park(context, win, "stepped aside for a dialog")
            }
        }
    }

    private const val ASIDE_SLIVER_PX = 4

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun track(context: Context, rect: ScreenRect, packageName: String = MAPS_PACKAGE) {
        val status = statusFlow(packageName)
        managed.add(packageName)
        if (packageName == MAPS_PACKAGE && freshStarted.add(packageName)) forceStop(context, packageName)
        // Launches that never became a window count across restarts of this loop. The
        // dashboard coming back (Home) restarts it; counted afresh each time, they never
        // reached the give-up on a unit that opens the app fullscreen.
        var mem = DockPolicy.Memory(openAttempts = openAttemptsSoFar[packageName] ?: 0)
        var lastResult: String? = null
        // Polls in a row that changed nothing: the listing was the same and
        // there was nothing to do. Enough of them and the loop slows down.
        var quietPolls = 0
        var prevListing: String? = null
        while (true) {
            if (isLent(packageName)) {
                // Lent to a split: neither parked, placed nor reopened meanwhile,
                // and nothing remembered from before the loan, so its window
                // being gone afterwards is not taken for the user closing it.
                mem = DockPolicy.Memory()
                openAttemptsSoFar.remove(packageName)
                status.value = status.value.copy(docked = false, checkedAt = SystemClock.elapsedRealtime())
                delay(POLL_MS)
                continue
            }
            val lookup = runGuarded { lookUp(context, packageName) }
            // Safety net: a window whose tile left the screen but which a missed
            // hide() left in place is parked here (closed if its tile is gone).
            lastListing?.let { runGuarded { handleStrays(context, it) } }
            val listing = lookup.getOrNull()?.first
            val win = lookup.getOrNull()?.second
            val now = SystemClock.elapsedRealtime()
            val sameListing = listing != null && listing == prevListing
            prevListing = listing
            var quiet = false
            if (lookup.isFailure) {
                publishError(context, packageName, lookup.exceptionOrNull()!!)
                mem = mem.copy(attempts = 0, lastStack = null)
            } else if (win == null) {
                status.value = status.value.copy(pipPackage = null, docked = false, mode = null, seen = lastSeen, windowBounds = null, oversizePx = null, checkedAt = now, visible = null, behindDashboard = null)
                unpark(context, packageName)
                noteFreeform(packageName, false)
                if (onScreenWindows().isEmpty()) setDashboardFocusable(context, true)
                val keepOpen = autoOpen(context, packageName)
                val expected = expectedGone.remove(packageName)
                // Only asked when a window we did not close just went: did the app die with it?
                val died = mem.hadWindow && !expected && keepOpen && processGone(context, packageName)
                val (step, next) = DockPolicy.onMissing(
                    mem, expectedGone = expected,
                    autoOpen = keepOpen,
                    lastReopenAt = lastReopenAt[packageName] ?: 0L, now = now,
                    died = died
                )
                mem = next
                openAttemptsSoFar[packageName] = next.openAttempts
                // No window and none to open: nothing happens until the user
                // opens one (a touch, which brings the quick pace back).
                quiet = sameListing && step == DockPolicy.Step.Idle && !keepOpen
                when (step) {
                    DockPolicy.Step.UserClosed -> {
                        // Gone, and not by our hand: the user closed it. Respect that.
                        Log.i(TAG, "$packageName closed by the user; not reopening")
                        setAutoOpen(context, false, packageName)
                    }
                    DockPolicy.Step.GiveUp -> {
                        // Launched twice and no window ever showed up: the app opened
                        // fullscreen or refused. Stop, or Home would be trapped.
                        Log.w(TAG, "$packageName never appeared as a window; giving up")
                        giveUpWindow(context, packageName)
                    }
                    is DockPolicy.Step.Reopen -> {
                        if (died) Log.w(TAG, "$packageName died with its window; opening it again")
                        lastReopenAt[packageName] = now
                        val bounds = android.graphics.Rect(rect.left, rect.top, rect.right, rect.bottom)
                        Log.i(TAG, "opening $packageName at $rect (attempt ${step.attempt})")
                        openedByTile(packageName)
                        FreeformBar.onScreen(context, packageName, rect.top)
                        SplitLauncher.launchFreeform(context, packageName, bounds)
                    }
                    else -> Unit
                }
            } else {
                expectedGone.remove(packageName)
                // Back from being parked (another page, a dialog): placed below.
                unpark(context, packageName)
                val (step, next) = DockPolicy.onPresent(
                    mem, win, rect, allowedArea.value,
                    lastRaiseAt = lastRaiseAt[packageName] ?: 0L, now = now
                )
                mem = next
                openAttemptsSoFar.remove(packageName)
                if (step is DockPolicy.Step.Unhide || step is DockPolicy.Step.ReleaseHidden) {
                    // Parked on the hidden display: bring it back onto the screen
                    // first; it is placed on the tile by the next poll, at once.
                    noteFreeform(packageName, win.mode == "freeform")
                    val result = if (step is DockPolicy.Step.Unhide) {
                        Log.i(TAG, "bringing $packageName back from the hidden display (attempt ${step.attempt})")
                        FreeformBar.onScreen(context, packageName, rect.top)
                        runGuarded { DockShell.moveToDisplay(context, win, WindowListing.DEFAULT_DISPLAY) }
                    } else {
                        // It will not come back: the display goes, every window parked
                        // on it with it, and the tiles reopen their apps instead of
                        // waiting forever.
                        expectedGone.add(packageName)
                        expectedGone.addAll(parked)
                        runGuarded { HiddenDisplay.release("$packageName stuck on it"); "hidden display released" }
                    }
                    lastResult = result.fold({ it }, { "failed: ${it.message}" })
                    status.value = Status(
                        pipPackage = win.packageName, docked = false, mode = win.mode, seen = lastSeen,
                        windowBounds = win.bounds, target = rect, lastResult = lastResult,
                        checkedAt = now, visible = win.visible, behindDashboard = win.behindDashboard
                    )
                    result.onFailure { publishError(context, packageName, it) }
                    // The listing is stale by now whatever happened: the next poll looks afresh.
                    DockShell.forgetListing()
                    delay(if (result.isSuccess) UNHIDE_SETTLE_MS else POLL_MS)
                    continue
                }
                val keep = step as DockPolicy.Step.Keep
                if (keep.docked) fullscreenReturns.remove(packageName)
                if (keep.docked && win.mode == "freeform") DockSupport.noteDocked(context)
                if (win.mode == "freeform") {
                    // Before the window counts as docked, and before it is moved.
                    (keep.place ?: win.bounds)?.let { FreeformBar.onScreen(context, packageName, it.top) }
                    noteFreeform(packageName, true)
                    setDashboardFocusable(context, false)
                } else {
                    noteFreeform(packageName, false)
                }
                status.value = Status(
                    pipPackage = win.packageName, docked = keep.docked, mode = win.mode, seen = lastSeen,
                    windowBounds = win.bounds, target = rect, lastResult = lastResult,
                    gaveUp = keep.gaveUp, oversizePx = keep.oversizePx,
                    checkedAt = now, visible = win.visible, behindDashboard = win.behindDashboard
                )
                val place = keep.place
                var placementFailed = false
                if (place != null) {
                    val result = runGuarded {
                        if (keep.swipe) DockShell.swipeTo(context, win.bounds!!, place) else DockShell.resize(context, win, place)
                    }
                    lastResult = result.fold({ it }, { "failed: ${it.message}" })
                    placementFailed = result.isFailure
                    mem = mem.copy(lastPlacementFailed = result.isFailure)
                    result.onFailure { publishError(context, packageName, it) }
                    if (result.isSuccess) undoStatusBarPolicy(context)
                }
                if (keep.raise) {
                    // Listed behind the dashboard (it restarted over the window):
                    // raise it, after placing it so the window raised is one on
                    // the tile, not a sliver at the edge of the screen.
                    lastRaiseAt[packageName] = now
                    // Raising can turn the window fullscreen on this head unit.
                    openedByTile(packageName)
                    Log.i(TAG, "raising $packageName above the dashboard")
                    if (bringToFront(context, win.taskId)) DockShell.forgetListing()
                    else lastResult = listOfNotNull(lastResult.takeIf { place != null }, "failed: could not raise ${win.packageName} (task ${win.taskId})").joinToString(" \u00b7 ")
                }
                if (place != null || keep.raise) {
                    status.value = status.value.copy(lastResult = lastResult, error = if (placementFailed) status.value.error else null)
                }
                // Docked where it belongs, and nothing moved since the last look.
                quiet = sameListing && keep.docked && place == null && !keep.raise
            }
            quietPolls = if (quiet) quietPolls + 1 else 0
            if (quietPolls < IDLE_AFTER_POLLS) delay(POLL_MS)
            else if (idlePause()) quietPolls = 0 // something may move: look now, then at the quick pace
        }
    }


    private val lastRaiseAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** How long a window just moved back onto the screen is given before it is placed on its tile. */
    private const val UNHIDE_SETTLE_MS = 300L

    /** Apps already stopped once in this process, so a fresh window of ours is the only one. */
    private val freshStarted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * The first time the tile loads, Maps is stopped outright: the head unit's
     * own dashboard keeps Maps open in its window and pulls it back to itself,
     * fighting the tile over it. Stopped first, the window the tile then opens
     * is the only one. Once per process, so a running navigation is never cut
     * again after that.
     */
    private suspend fun forceStop(context: Context, packageName: String) {
        val out = runGuarded { DockShell.shell(context, "am force-stop $packageName") }
            .getOrElse { "failed: ${it.message}" }
        Log.i(TAG, "stopped $packageName before docking it: ${out.trim().ifEmpty { "ok" }}")
        openedByTile(packageName)
        unpark(context, packageName)
        noteFreeform(packageName, false)
        lastReopenAt.remove(packageName) // open ours straight away
        delay(FORCE_STOP_SETTLE_MS)
    }

    /** Gives the system a moment to take the stopped app's windows down before the listing is read. */
    private const val FORCE_STOP_SETTLE_MS = 500L

    /**
     * The user tapped a tile its window should be covering: whatever the
     * listing says, the window is not showing there. It is slid off the right
     * edge and straight back onto [rect], the trip a page change makes it take,
     * which does bring it up on this head unit. Raising the task instead can
     * turn it fullscreen, so that is never done here.
     */
    fun nudge(context: Context, rect: ScreenRect, packageName: String = MAPS_PACKAGE) {
        if (isLent(packageName)) return
        scope.launch {
            val win = runGuarded { findFloatingWindow(context, packageName) }.getOrNull() ?: return@launch
            if (win.mode != "freeform" || win.offDisplay) return@launch // the tracker brings a hidden window back
            val b = win.bounds ?: return@launch
            Log.i(TAG, "nudging $packageName (its tile was tapped)")
            val left = context.resources.displayMetrics.widthPixels - ASIDE_SLIVER_PX
            runGuarded { DockShell.resize(context, win, ScreenRect(left, b.top, left + (b.right - b.left), b.bottom)) }
                .onFailure { Log.w(TAG, "nudge aside failed", it) }
            delay(NUDGE_PAUSE_MS)
            runGuarded { DockShell.resize(context, win, rect) }.onFailure { Log.w(TAG, "nudge back failed", it) }
        }
    }

    /** How long a nudged window stays at the edge before coming back. */
    private const val NUDGE_PAUSE_MS = 250L

    @Volatile private var overlayGrantTried = false

    /**
     * Grants "display over other apps" (for the skin's frame over the docked
     * window) through the dock's shell, since a head unit rarely exposes the
     * settings screen. Tried once per process; true when the permission is held.
     */

    suspend fun grantOverlayPermission(context: Context): Boolean {
        if (android.provider.Settings.canDrawOverlays(context)) return true
        // The Play edition has no shell to grant it with: the driver allows it in the system settings (PhoneLinkUi).
        if (Edition.play) return false
        if (overlayGrantTried) return false
        val out = runGuarded { DockShell.shell(context, "appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow") }
            .getOrElse { "failed: ${it.message}" }
        // Only a grant that actually ran counts as tried: a cancelled one is tried again.
        overlayGrantTried = true
        // The app-op change can take a moment to reach this process.
        repeat(10) {
            if (android.provider.Settings.canDrawOverlays(context)) {
                Log.i(TAG, "overlay permission granted")
                return true
            }
            delay(200)
        }
        Log.w(TAG, "overlay permission not granted: ${out.trim()}")
        return false
    }

    private var statusBarPolicyChecked = false

    /**
     * An earlier build wrote a per-app immersive policy hoping to keep the
     * status bar hidden with a docked window. Android forces the bar whenever a
     * freeform window is visible, so the policy achieved nothing; clear it once.
     */

    private suspend fun undoStatusBarPolicy(context: Context) {
        if (statusBarPolicyChecked) return
        runGuarded {
            val current = DockShell.shell(context, "settings get global policy_control").trim()
            if (current.startsWith("immersive.status=") && current.contains(context.packageName)) {
                DockShell.shell(context, "settings delete global policy_control")
                Log.i(TAG, "cleared policy_control ('$current')")
            }
        }.onFailure { Log.w(TAG, "could not check status-bar policy", it) }
        // Checked once it ran (failed or not); a cancelled check is done again.
        statusBarPolicyChecked = true
    }

    // --- Keeping the docked window in front ---------------------------------
    //
    // Touching the dashboard normally brings its window above the floating Maps
    // window, which is why Maps "disappeared" on every touch. A window that
    // declines key focus (FLAG_NOT_FOCUSABLE) still gets touches but is never
    // raised by them, so the dashboard declines focus while Maps is docked and
    // takes it back when the window is gone. Side effect: no on-screen keyboard
    // for the dashboard while Maps is docked on the visible page.

    /** Written on the main thread only. */
    @Volatile private var focusDeclined = false
    /** The dashboard whose window carries the flag. */
    @Volatile private var declinedOn: java.lang.ref.WeakReference<android.app.Activity>? = null

    private suspend fun setDashboardFocusable(context: Context, focusable: Boolean) {
        val activity = context.findActivity() ?: return
        // A dashboard built anew (the language changed) has a window without the flag.
        if (focusDeclined && declinedOn?.get() !== activity) focusDeclined = false
        if (focusable != focusDeclined) return // already so: no trip to the main thread (every poll asks)
        // Checked again, flipped and applied together on the main thread:
        // callers on several IO threads could otherwise apply their changes in
        // the reverse order and leave the dashboard unable to take focus.
        val changed = withContext(Dispatchers.Main) {
            if (focusable != focusDeclined) return@withContext false
            focusDeclined = !focusable
            declinedOn = if (focusable) null else java.lang.ref.WeakReference(activity)
            val flag = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            if (focusable) activity.window.clearFlags(flag) else activity.window.addFlags(flag)
            true
        }
        if (changed) Log.i(TAG, if (focusable) "dashboard takes focus again" else "dashboard declines focus while Maps is docked")
    }

    private fun dashboardTaskId(context: Context): Int? = context.findActivity()?.taskId

    /** Raises a task; needs the normal REORDER_TASKS permission and a foreground caller, both true here. */
    private fun bringToFront(context: Context, taskId: Int?): Boolean {
        taskId ?: return false
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.moveTaskToFront(taskId, android.app.ActivityManager.MOVE_TASK_NO_USER_ACTION)
            true
        }.onFailure { Log.w(TAG, "moveTaskToFront($taskId) failed", it) }.getOrDefault(false)
    }

    private fun Context.findActivity(): android.app.Activity? {
        var c: Context? = this
        while (c is android.content.ContextWrapper) {
            if (c is android.app.Activity) return c
            c = c.baseContext
        }
        return null
    }

    private const val PREFS = "pip_anchor"


    private val lastReopenAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Maps keeps its historical key; other apps get one each. */
    private fun autoOpenKey(packageName: String) =
        if (packageName == MAPS_PACKAGE) "auto_open_maps" else "auto_open_$packageName"

    /**
     * Once the user has opened Maps from the tile, the tile's job is "Maps lives
     * here": whenever it is on screen and no Maps window exists, whatever closed
     * it (a page change, another app, a reboot), it opens one. Removing the
     * tile ends that. Persisted so the Maps page survives a restart.
     */

    fun autoOpen(context: Context, packageName: String = MAPS_PACKAGE): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(autoOpenKey(packageName), false)

    fun setAutoOpen(context: Context, on: Boolean, packageName: String = MAPS_PACKAGE) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(autoOpenKey(packageName), on).apply()
    }

    /** Every app with a persisted keep-open intent. */
    private fun autoOpenPackages(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            .filter { (k, v) -> k.startsWith("auto_open_") && v == true }
            .keys.map { if (it == "auto_open_maps") MAPS_PACKAGE else it.removePrefix("auto_open_") }.toSet()

    /** Stops keeping windows open for every app that no longer has a tile. */
    fun releaseAutoOpenExcept(context: Context, keep: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("auto_open_") }.forEach { key ->
            val pkg = if (key == "auto_open_maps") MAPS_PACKAGE else key.removePrefix("auto_open_")
            if (pkg !in keep) editor.remove(key)
        }
        editor.apply()
    }

    /**
     * Another app took the whole screen. This ROM keeps floating windows above
     * fullscreen apps too, so the window would sit over that app: park it
     * aside, still running (closing it stopped the music and reset Maps'
     * guidance); the tile docks it again when the dashboard is back.
     */

    fun parkForOtherApp(context: Context, packageName: String = MAPS_PACKAGE) {
        scope.launch {
            // A pair's apps come to the front on purpose: their windows are the split's now.
            if (!isLent(packageName)) {
                val win = runGuarded { findFloatingWindow(context, packageName) }.getOrNull()
                if (win?.mode == "freeform") park(context, win, "another app is in front")
                // The other app may be this one, fullscreen instead of in its window.
                sendBackIfFullscreen(context, packageName)
            }
            DockShell.release()
        }
    }

    /**
     * When the tile last opened, raised or stopped each app itself (ms). On the
     * wall clock with [lastTouchAt], which UnitPhoneScreen compares with
     * System.currentTimeMillis(): the three are only ever compared together.
     */
    private val ownLaunchAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Times each app was sent back from fullscreen since it last docked. */
    private val fullscreenReturns = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** When the dashboard itself was last touched (ms). */
    @Volatile var lastTouchAt = 0L
        private set

    /** The dashboard was touched: an app opening right after may be the user's doing. */
    fun noteUserTouch() {
        lastTouchAt = System.currentTimeMillis()
        pollAgainSoon()
    }

    /** The tile is opening (or raising) [packageName] itself, to show it in its window. */
    fun openedByTile(packageName: String) {
        ownLaunchAt[packageName] = System.currentTimeMillis()
        pollAgainSoon()
    }

    /**
     * The tile's own launch can come up fullscreen instead of in its window,
     * after a reboot above all, and the dashboard then stays covered by Maps
     * until Back is pressed. So when the dashboard is covered right after the
     * tile opened the app, and the app is what covers it, Back is pressed for
     * the user: the app leaves the screen, the dashboard comes back, and the
     * tile opens it in its window again. See [DockPolicy.sendBackFromFullscreen]
     * for when it is left alone.
     */
    private suspend fun sendBackIfFullscreen(context: Context, packageName: String) {
        repeat(FULLSCREEN_CHECKS) { check ->
            if (check > 0) delay(FULLSCREEN_CHECK_MS)
            val now = System.currentTimeMillis()
            val returns = fullscreenReturns[packageName] ?: 0
            if (!DockPolicy.sendBackFromFullscreen(autoOpen(context, packageName), ownLaunchAt[packageName] ?: 0L, lastTouchAt, returns, now)) return
            val listing = runGuarded { DockShell.listStacks(context) }.getOrNull() ?: return
            val full = WindowListing.fullscreenInFront(listing, packageName, context.packageName) ?: return@repeat
            fullscreenReturns[packageName] = returns + 1
            val out = runGuarded { DockShell.shell(context, "input keyevent $KEYCODE_BACK") }
                .fold({ it.trim().ifEmpty { "ok" } }, { "failed: ${it.message}" })
            Log.i(TAG, "$packageName came up fullscreen (task ${full.taskId}) instead of in its window; pressed Back: $out")
            if (DockPolicy.giveUpAfterFullscreen(returns + 1)) {
                Log.w(TAG, "$packageName came up fullscreen ${returns + 1} times; giving up its window")
                giveUpWindow(context, packageName)
            }
            return
        }
    }

    /** Launches so far that never became a window, per app, kept while [track] restarts. */
    private val openAttemptsSoFar = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * Bumped each time the Maps dock gives up on a unit that cannot float Maps
     * ([DockPolicy.GivenUp.DASHBOARDS_ONLY]): the dashboard switches to
     * Dashboards only. The layout is saved here already, for a dashboard not
     * composed right now.
     */
    val dockDropped = MutableStateFlow(0)

    /**
     * The tile's launches keep coming up fullscreen, or never as a window: it
     * stops opening [packageName], so Home is no longer handed straight back
     * to the app. The Maps dock on a unit that never floated a window goes
     * altogether ([DockPolicy.onGivenUp]).
     */
    private fun giveUpWindow(context: Context, packageName: String) {
        setAutoOpen(context, false, packageName)
        openAttemptsSoFar.remove(packageName)
        statusFlow(packageName).update { it.copy(error = context.getString(R.string.apps_window_error_gave_up)) }
        val isDock = packageName == MAPS_PACKAGE && dockActive.value
        if (DockPolicy.onGivenUp(isDock, DockSupport.dockedBefore(context)) != DockPolicy.GivenUp.DASHBOARDS_ONLY) return
        Log.w(TAG, "the Maps dock never floated Maps; back to the dashboards only")
        DockSupport.noteRefused(context)
        DashLayoutStore.save(context, DashLayout.GRID)
        dockDropped.update { it + 1 }
        val app = context.applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(app, R.string.dash_layout_dock_dropped, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** The listing may not show the app in front yet when the dashboard stops: looked at a few times. */
    private const val FULLSCREEN_CHECKS = 3
    private const val FULLSCREEN_CHECK_MS = 700L

    /** `KeyEvent.KEYCODE_BACK`, for `input keyevent`. */
    private const val KEYCODE_BACK = 4

    /**
     * Clears the dashboard of every managed window whose app is not in [onPage].
     * Called the moment the pager's current page changes, so a window leaves
     * with the swipe instead of a few seconds later when the old page is
     * finally disposed. Windows whose app still has a tile on another page are
     * parked aside, still running; only those whose tile is gone are closed.
     */

    fun stashAllExcept(context: Context, onPage: Set<String>) {
        scope.launch {
            val listing = runGuarded { DockShell.listStacks(context) }.getOrNull() ?: return@launch
            syncFreeform(context, listing)
            val mine = managedPackages(context)
            for (win in WindowListing.allFloatingWindows(listing, context.packageName)) {
                if (win.mode != "freeform" || win.packageName !in mine || win.packageName in onPage) continue
                if (isLent(win.packageName)) continue
                if (isPlaced(win.packageName)) park(context, win, "page change")
                else closeWindow(context, win, "tile removed")
            }
        }
    }

    /** Back on screen: let [track] reopen the app at once instead of waiting out the cooldown. */
    fun expectReturn(packageName: String) {
        lastReopenAt.remove(packageName)
    }

    /**
     * The tile left the screen (another page, edit mode). A freeform window
     * cannot be hidden on the screen: the window manager keeps part of it in
     * view, and raising the dashboard above it does not help either, this ROM
     * keeps floating windows drawn on top. So it is parked out of sight, still
     * running (closing it reset Maps' guidance and stopped the music), and the
     * tile docks it again when it is back; it is closed only once its app has
     * no tile left.
     * Picture-in-picture, which the system keeps on screen anyway, is parked
     * small in the bottom-right corner. Nothing is done while another tile of
     * the same app is on screen (the app on both pages of a swipe): the window
     * is that tile's now, and must not be parked from under it.
     */
    fun hide(context: Context, packageName: String = MAPS_PACKAGE) {
        if ((tileCounts[packageName] ?: 0) > 0) return
        if (isLent(packageName)) return
        scope.launch {
            val stack = runGuarded { findFloatingWindow(context, packageName) }.getOrNull()
            if (stack == null) {
                // Already gone: still hand focus back.
                unpark(context, packageName)
                noteFreeform(packageName, false)
                if (onScreenWindows().isEmpty()) setDashboardFocusable(context, true)
                return@launch
            }
            if (stack.mode == "freeform") {
                if (isPlaced(packageName)) park(context, stack, "tile off screen")
                else closeWindow(context, stack, "tile removed")
            } else {
                val dm = context.resources.displayMetrics
                val w = dm.widthPixels / 4
                val h = w * 9 / 16
                val margin = (12 * dm.density).roundToInt()
                val rect = ScreenRect(dm.widthPixels - w - margin, dm.heightPixels - h - margin, dm.widthPixels - margin, dm.heightPixels - margin)
                runGuarded { DockShell.resize(context, stack, rect) }.onFailure { Log.w(TAG, "park failed", it) }
            }
        }
    }

    /** Summary of the last stack listing, e.g. "fullscreen dash · freeform maps". */
    @Volatile private var lastSeen: String? = null

    private var lastListing: String? = null

    private suspend fun findFloatingWindow(context: Context, packageName: String? = null): FloatingWindow? =
        lookUp(context, packageName).second

    /** The stack listing, and [packageName]'s floating window in it. */
    private suspend fun lookUp(context: Context, packageName: String?): Pair<String, FloatingWindow?> {
        val listing = DockShell.listStacks(context)
        if (listing != lastListing) {
            // Full dump once per change: this is what tells us how the ROM
            // reports its floating windows.
            Log.i(TAG, "am stack list:\n$listing")
            lastListing = listing
        }
        lastSeen = WindowListing.summarizeStacks(listing)
        // Parsed once per poll; the stray check right after reads the same list.
        val windows = WindowListing.allFloatingWindows(listing, context.packageName)
        lastParsed = listing to windows
        return listing to WindowListing.pickFloatingWindow(windows, packageName)
    }

    /** The last listing [lookUp] read and the windows parsed out of it, for [handleStrays]. */
    @Volatile private var lastParsed: Pair<String, List<FloatingWindow>>? = null

    /** Shows [e] on the tile; [context] resolves the message in the UI language (shell errors stay as the system wrote them). */
    private fun publishError(context: Context, packageName: String, e: Throwable) {
        Log.w(TAG, "PiP anchor error", e)
        val msg = when {
            e is java.net.ConnectException || e.message?.contains("Connection refused") == true ->
                context.getString(R.string.apps_window_error_no_shell, AdbInstaller.announcedPort())
            else -> e.message ?: e.javaClass.simpleName
        }
        statusFlow(packageName).let { it.value = it.value.copy(error = msg) }
    }
}
