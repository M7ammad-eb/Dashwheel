package com.openauto.dash

/**
 * The tracking loop's decisions, one poll at a time, as pure functions of what
 * it sees and what it remembers. [PipAnchor.track] only carries out the
 * resulting [Step]; every rule about reopening, giving up, raising and placing
 * a window lives here, where it can be unit-tested without a device.
 *
 * The `now` handed to [onMissing] and [onPresent], and the times it is compared
 * with, are [android.os.SystemClock.elapsedRealtime] ms: the unit's clock is set
 * by GPS or NTP mid-drive, and a step would end a cooldown at once or freeze it.
 */
object DockPolicy {

    /** Minimum gap between two launches of the same app. */
    const val REOPEN_COOLDOWN_MS = 15_000L

    /** A window listed behind the dashboard is raised at most this often, so a wrong listing can't cause focus flicker. */
    const val RAISE_COOLDOWN_MS = 8_000L

    /** Placement attempts per window before the tile stops fighting the system. */
    const val MAX_ATTEMPTS = 6

    /** Launches without a window ever appearing before auto-open is switched off (or Home would be trapped). */
    const val MAX_OPEN_ATTEMPTS = 2

    /** After this many refused placements the tile tries dragging the window instead. */
    const val SWIPE_AFTER_ATTEMPTS = 3

    /** A window this much larger than its tile is reported as oversize so the tile can grow. */
    const val OVERSIZE_RATIO = 1.08f

    /** Window and tile sizes this close, in pixels, count as the same size. */
    const val SIZE_SLACK_PX = 8

    /** Tries to bring a window back from the hidden display before that display is given up on. */
    const val MAX_UNHIDE_ATTEMPTS = 3

    /** How long after the tile itself opened (or raised) an app its coming up fullscreen is taken for that launch going wrong. */
    const val FULLSCREEN_GRACE_MS = 20_000L

    /**
     * Times the tile sends a fullscreen app back before it stops opening it
     * (reset once it docks): a unit whose launches keep coming up fullscreen
     * cannot float the app, and reopening it there trapped Home on Maps.
     */
    const val MAX_FULLSCREEN_RETURNS = 2

    /** A tap on the dashboard this shortly before an app covered it is taken for the user opening that app. */
    const val TAP_OPENS_APP_MS = 3_000L

    /** Reopens after the app died, within [CRASH_WINDOW_MS], before an app that keeps crashing is left closed. */
    const val MAX_CRASH_REOPENS = 2

    /** Crashes this far apart are counted afresh: one a day is bad luck, three in ten minutes a loop. */
    const val CRASH_WINDOW_MS = 10 * 60_000L

    /**
     * The app the tile keeps open covers the whole screen. It is sent back into
     * its window only when the tile's own launch or raise put it there: that
     * happened within [FULLSCREEN_GRACE_MS] ([ownLaunchAt], 0 if never), the
     * dashboard was not tapped since, just before ([lastTouchAt]: that may well
     * be the user opening the app fullscreen), and it has not been sent back
     * [MAX_FULLSCREEN_RETURNS] times already.
     */
    fun sendBackFromFullscreen(autoOpen: Boolean, ownLaunchAt: Long, lastTouchAt: Long, returns: Int, now: Long): Boolean {
        val userTapped = lastTouchAt >= ownLaunchAt && now - lastTouchAt <= TAP_OPENS_APP_MS
        return autoOpen && ownLaunchAt > 0L && now - ownLaunchAt <= FULLSCREEN_GRACE_MS &&
            !userTapped && returns < MAX_FULLSCREEN_RETURNS
    }

    /** After [returns] sends back from fullscreen in a row, the tile stops opening its app. */
    fun giveUpAfterFullscreen(returns: Int): Boolean = returns >= MAX_FULLSCREEN_RETURNS

    /** What goes with a window the tile gave up on. */
    enum class GivenUp {
        /** The tile stops opening the app; the driver can still open it from the tile. */
        STOP_OPENING,
        /** The Maps dock goes too: the screen is the dashboards' alone again. */
        DASHBOARDS_ONLY
    }

    /**
     * The tile gave up on its window ([Step.GiveUp], or [giveUpAfterFullscreen]).
     * The Maps dock ([isDock]) on a unit that never showed a window
     * ([dockedBefore]) cannot float Maps at all: the dock layout goes and the
     * dashboards take the screen. On a unit that has floated one before (the
     * K706, whose first launch after a reboot can come up fullscreen) the
     * layout stays, and only the opening stops.
     */
    fun onGivenUp(isDock: Boolean, dockedBefore: Boolean): GivenUp =
        if (isDock && !dockedBefore) GivenUp.DASHBOARDS_ONLY else GivenUp.STOP_OPENING

    /**
     * Whether the layouts with the Maps dock are offered. [systemFloats]: the
     * system says it runs apps in windows (the freeform feature, or its
     * developer switch); [dockedBefore]: a window has really docked on this
     * unit; [refusedBefore]: the dock already fell back to the dashboards
     * here ([GivenUp.DASHBOARDS_ONLY]). A window seen docking outweighs
     * everything; a unit that refused it is not offered it again.
     */
    fun dockOffered(systemFloats: Boolean, dockedBefore: Boolean, refusedBefore: Boolean): Boolean =
        dockedBefore || (systemFloats && !refusedBefore)

    /** What the loop remembers between polls for one app. */
    data class Memory(
        val hadWindow: Boolean = false,
        val openAttempts: Int = 0,
        val attempts: Int = 0,
        val lastStack: Int? = null,
        val lastPlacementFailed: Boolean = false,
        /** The tile rectangle the window was last asked to take (tells a stale size from the app's minimum). */
        val askedFor: ScreenRect? = null,
        /** Failed tries so far to bring the window back from the hidden display. */
        val unhideAttempts: Int = 0,
        /** Reopens after the app died, and when the last one was ([CRASH_WINDOW_MS]). */
        val crashReopens: Int = 0,
        val lastCrashAt: Long = 0L
    )

    sealed class Step {
        /** Nothing to do this poll. */
        object Idle : Step()
        /** The window vanished and we did not close it: stop keeping it open. */
        object UserClosed : Step()
        /** Launched repeatedly and never became a window: stop keeping it open. */
        object GiveUp : Step()
        /** Launch the app as a window at the tile ([attempt] is 1-based). */
        data class Reopen(val attempt: Int) : Step()
        /** The window is parked on the hidden display: move it back onto the screen first ([attempt] is 1-based). */
        data class Unhide(val attempt: Int) : Step()
        /** The window will not come back from the hidden display: let that display go (the window closes and is reopened). */
        object ReleaseHidden : Step()
        /**
         * The window exists. [raise] brings it above the dashboard; [place] is
         * where to move/resize it (null when it sits well), by dragging when
         * [swipe] is set; [gaveUp] and [oversizePx] feed the tile's status.
         */
        data class Keep(
            val docked: Boolean,
            val raise: Boolean,
            val place: ScreenRect?,
            val swipe: Boolean,
            val gaveUp: Boolean,
            val oversizePx: Pair<Int, Int>?
        ) : Step()
    }

    /**
     * No window for this app right now. [expectedGone] is true when one of our
     * own close paths removed it; [autoOpen] is the persisted keep-open intent;
     * [lastReopenAt] is when we last launched it (0 if never). [died]: the
     * app's process is gone with its window, so it crashed or the system
     * killed it: the driver closed nothing, and the window comes back (Maps
     * in the middle of a route), unless it keeps crashing ([MAX_CRASH_REOPENS]).
     */
    fun onMissing(
        mem: Memory,
        expectedGone: Boolean,
        autoOpen: Boolean,
        lastReopenAt: Long,
        now: Long,
        died: Boolean = false
    ): Pair<Step, Memory> {
        val next = mem.copy(hadWindow = false, attempts = 0, lastStack = null)
        if (mem.hadWindow && !expectedGone) {
            val recent = if (now - mem.lastCrashAt <= CRASH_WINDOW_MS) mem.crashReopens else 0
            if (!died || !autoOpen || recent >= MAX_CRASH_REOPENS) return Step.UserClosed to next.copy(openAttempts = 0)
            return Step.Reopen(1) to next.copy(openAttempts = 1, crashReopens = recent + 1, lastCrashAt = now)
        }
        if (!autoOpen || now - lastReopenAt <= REOPEN_COOLDOWN_MS) return Step.Idle to next
        if (mem.openAttempts >= MAX_OPEN_ATTEMPTS) return Step.GiveUp to next.copy(openAttempts = 0)
        return Step.Reopen(mem.openAttempts + 1) to next.copy(openAttempts = mem.openAttempts + 1)
    }

    /**
     * The window exists at [win]; the tile wants it on [rect] inside [limit]
     * (the dashboard area, null if unknown). [lastRaiseAt] is when we last
     * raised it (0 if never).
     */
    fun onPresent(mem: Memory, win: FloatingWindow, rect: ScreenRect, limit: ScreenRect?, lastRaiseAt: Long, now: Long): Pair<Step, Memory> {
        if (win.offDisplay) {
            // Parked out of sight: nothing can be placed until it is back on the
            // screen. A window that will not come back is closed with its hidden
            // display and reopened by the usual path, rather than stay lost.
            val seen = mem.copy(hadWindow = true, openAttempts = 0, lastStack = win.stackId)
            if (mem.unhideAttempts >= MAX_UNHIDE_ATTEMPTS) return Step.ReleaseHidden to seen.copy(unhideAttempts = 0)
            return Step.Unhide(mem.unhideAttempts + 1) to seen.copy(unhideAttempts = mem.unhideAttempts + 1)
        }
        var attempts = if (win.stackId != mem.lastStack) 0 else mem.attempts
        val b = win.bounds
        val close = b != null && WindowListing.isClose(b, rect)
        val inside = b == null || WindowListing.withinArea(b, limit)
        // A window of another size may just still have an older tile's size (the
        // tile moved when the status bar came up, or was resized while arranging),
        // so it is first asked to take the tile's size. Only a window that keeps
        // its own size after that is at the app's minimum, and is accepted.
        val sizeSettled = b == null || sameSize(b, rect) || (mem.askedFor == rect && !mem.lastPlacementFailed)
        val docked = close && inside && sizeSettled
        if (docked) attempts = 0

        // Raised only when the listing says the dashboard covers it, never on a
        // hunch: on this head unit bringing a freeform task to the front can
        // leave its window blank or turn it into a fullscreen one, which then
        // opens over the dashboard while the tile reopens another window.
        val raise = win.mode == "freeform" && (!win.visible || win.behindDashboard) && now - lastRaiseAt > RAISE_COOLDOWN_MS

        val oversize = b?.takeIf {
            sizeSettled && ((it.right - it.left) > (rect.right - rect.left) * OVERSIZE_RATIO ||
                (it.bottom - it.top) > (rect.bottom - rect.top) * OVERSIZE_RATIO)
        }?.let { (it.right - it.left) to (it.bottom - it.top) }

        var place: ScreenRect? = null
        var swipe = false
        if (!docked && attempts < MAX_ATTEMPTS) {
            attempts++
            // A drag cannot resize and its first touch expands a PiP, so it is
            // only worth trying once the resize commands were refused.
            swipe = attempts >= SWIPE_AFTER_ATTEMPTS && b != null && mem.lastPlacementFailed
            // The system kept the window at its minimum size, larger than the tile:
            // keep that size but move it back inside the dashboard area.
            place = if (close && !inside && sizeSettled && b != null && limit != null) WindowListing.keepInside(b, limit) else rect
        }
        val step = Step.Keep(docked, raise, place, swipe, gaveUp = attempts >= MAX_ATTEMPTS, oversizePx = oversize)
        return step to mem.copy(
            hadWindow = true, openAttempts = 0, attempts = attempts, lastStack = win.stackId,
            askedFor = if (place == rect) rect else mem.askedFor, unhideAttempts = 0
        )
    }

    private fun sameSize(a: ScreenRect, b: ScreenRect): Boolean =
        kotlin.math.abs((a.right - a.left) - (b.right - b.left)) <= SIZE_SLACK_PX &&
            kotlin.math.abs((a.bottom - a.top) - (b.bottom - b.top)) <= SIZE_SLACK_PX
}
