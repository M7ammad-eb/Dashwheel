package com.openauto.dash

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

/**
 * False on the pages the pagers keep composed beside the one on screen (so a
 * map tile survives a swipe away and back). A window tile there is not on
 * screen: it must neither open nor place its app's window, or Maps comes up
 * over a page with no Maps tile, or fullscreen at start from the page next to
 * home (its tile's bounds are off the screen).
 */
internal val LocalPageOnScreen = compositionLocalOf { true }

/**
 * "Maps window" tile: while it is on screen the floating PiP window is kept
 * exactly over it; swiping to another page parks the window in a corner.
 */
@Composable
internal fun PipAnchorCard(
    modifier: Modifier = Modifier,
    isDock: Boolean = false,
    packageName: String = PipAnchor.MAPS_PACKAGE,
    appLabel: String = "Maps",
    /** Called with the window's pixel size when the system makes it larger than the tile. */
    onWindowBiggerThanTile: ((Int, Int) -> Unit)? = null
) {
    val context = LocalContext.current
    // With a permanent Maps dock on screen, a "Maps window" tile on a page must
    // not compete for the same window: it just points at the dock.
    val dockActive by PipAnchor.dockActive.collectAsState()
    val isMaps = packageName == PipAnchor.MAPS_PACKAGE
    if (isDock) {
        DisposableEffect(Unit) {
            PipAnchor.dockActive.value = true
            onDispose { PipAnchor.dockActive.value = false }
        }
    } else if (dockActive && isMaps) {
        Card(modifier = modifier) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(stringResource(R.string.apps_window_title, "MAPS"), color = DashColors.AccentInk, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.apps_window_maps_docked_beside), color = DashColors.TextSecondary, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val status by PipAnchor.statusOf(packageName).collectAsState()
    // The dock sits beside the pages and is always on screen. A page tile kept
    // composed off screen does nothing with the window until its page shows,
    // exactly as if it were not composed at all.
    val onScreen = isDock || LocalPageOnScreen.current
    val onScreenNow by rememberUpdatedState(onScreen)
    // An app's minimum window size can exceed the tile; let the tile grow to it
    // rather than have the window spill over its neighbours.
    LaunchedEffect(status.oversizePx) {
        val (w, h) = status.oversizePx ?: return@LaunchedEffect
        onWindowBiggerThanTile?.invoke(w, h)
    }
    // Counted while on screen, so the window is never mistaken for a stray while
    // the tracking loop is between restarts. Counted down before the window is
    // hidden, so hide() can see whether another tile of the app still shows it.
    if (onScreen) {
        DisposableEffect(packageName) {
            PipAnchor.tileShown(packageName)
            onDispose {
                PipAnchor.tileHidden(packageName)
                // A Maps page tile standing down for the dock must not close the very
                // window the dock is about to take over.
                val handingOverToDock = isMaps && !isDock && PipAnchor.dockActive.value
                if (!handingOverToDock) PipAnchor.hide(context, packageName)
            }
        }
    }

    var target by remember { mutableStateOf<ScreenRect?>(null) }
    var started by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { started = true; if (onScreenNow) PipAnchor.expectReturn(packageName) }
                // Back from a touch on the window (or any pause): look again at the quick pace.
                Lifecycle.Event.ON_RESUME -> PipAnchor.pollAgainSoon()
                // Another app took the whole screen: stop polling and park the
                // window aside, still running; coming back, track() docks it
                // again. (Touching the Maps window only *pauses* the launcher,
                // which must not hide anything.)
                Lifecycle.Event.ON_STOP -> { started = false; if (onScreenNow) PipAnchor.parkForOtherApp(context, packageName) }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The window steps aside only for a pop-up over this tile (or when every
    // window must); a menu or dialog elsewhere on screen leaves it open. A page
    // swipe takes the pages' windows aside with them, but not the dock's: it
    // sits beside the pages and does not move.
    val steppedAside by PipAnchor.steppedAside.collectAsState()
    val pageSwiping by PipAnchor.pageSwiping.collectAsState()
    val covered by PipAnchor.coveredAreas.collectAsState()
    val blocked = steppedAside || (pageSwiping && !isDock) || target?.let { t ->
        covered.values.any { WindowListing.overlaps(it, t, margin = POPUP_MARGIN_PX) }
    } == true
    // Parked once per pop-up or swipe, not once per frame: a page swipe moves
    // the tile (so its target) every frame, and each park is a shell round trip
    // that queued up behind the one before, so the window came back seconds
    // after the swipe had ended.
    val positioned = target != null
    // Not for a tile off screen: the window may be another tile's (the same
    // app on the page shown), and parking it would take it from there.
    LaunchedEffect(positioned, started, blocked, onScreen) {
        if (onScreen && positioned && started && blocked) PipAnchor.parkAside(context, packageName)
    }
    // Re-target after the tile settles: a drag in edit mode moves it many times
    // per second, and each ADB round trip costs real time. A tile that was just
    // uncovered (a swipe ended on it, a pop-up closed) or has not moved since it
    // was last tracked is already settled, and is tracked at once.
    val settle = remember { SettleState() }
    // A page that has just come on screen counts as uncovered.
    LaunchedEffect(target, started, blocked, onScreen) {
        val rect = target ?: return@LaunchedEffect
        val settled = settle.wasBlocked || rect == settle.tracked
        settle.wasBlocked = blocked || !onScreen
        if (!onScreen || !started || blocked) return@LaunchedEffect
        if (!settled) delay(SETTLE_MS)
        settle.tracked = rect
        PipAnchor.track(context, rect, packageName)
    }

    // The skin's frame (a round porthole, a chrome bezel...) over the docked Maps
    // window, only while it actually sits here and the dashboard is on screen.
    WindowFrameOverlay(
        bounds = status.windowBounds.takeIf { isMaps && onScreen && started && !blocked && status.docked && status.pipPackage != null }
    )

    Card(
        modifier = modifier.onGloballyPositioned { coords ->
            val b = coords.boundsInRoot()
            val origin = IntArray(2).also { view.getLocationOnScreen(it) }
            val r = ScreenRect(
                (b.left + origin[0]).roundToInt(), (b.top + origin[1]).roundToInt(),
                (b.right + origin[0]).roundToInt(), (b.bottom + origin[1]).roundToInt()
            )
            if (r != target) target = r
        }
    ) {
        val pkg = status.pipPackage
        val err = status.error
        val name = pkg?.substringAfterLast('.')
        // A status no poll has refreshed lately (the window is parked aside for
        // a pop-up, or tracking is paused) must not keep claiming "docked".
        // rememberNow only paces the recomposition: the age is on the monotonic
        // clock, as checkedAt is, so a GPS or NTP step cannot stale or revive it.
        rememberNow(1_000L)
        val checkedAgoS = ((SystemClock.elapsedRealtime() - status.checkedAt) / 1000L).coerceAtLeast(0L)
        val docked = status.docked && checkedAgoS * 1000L <= STALE_STATUS_MS
        // A docked window covers its tile, so a tap that reaches the tile means
        // the window is not showing, whatever the system says: nudge it back.
        val hiddenBehind = pkg != null && docked && status.mode == "freeform"
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable(enabled = hiddenBehind) { target?.let { PipAnchor.nudge(context, it, packageName) } }
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(stringResource(R.string.apps_window_title, appLabel.uppercase()), color = DashColors.AccentInk, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    // The mode ("freeform" / "pinned") is the system's own term, shown as is.
                    pkg != null && docked -> stringResource(R.string.apps_window_docked, name.orEmpty(), status.mode.orEmpty())
                    pkg != null && status.gaveUp -> stringResource(R.string.apps_window_gave_up, name.orEmpty())
                    pkg != null -> stringResource(R.string.apps_window_moving, name.orEmpty())
                    PipAnchor.autoOpen(context, packageName) -> stringResource(R.string.apps_window_opening, appLabel)
                    isMaps -> stringResource(R.string.apps_window_maps_hint)
                    else -> stringResource(R.string.apps_window_app_hint, appLabel)
                },
                color = when {
                    pkg != null && docked -> DashColors.Good
                    pkg != null && status.gaveUp -> DashColors.Warning
                    else -> DashColors.TextSecondary
                },
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
            if (hiddenBehind) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.apps_window_tap_to_raise), color = DashColors.TextSecondary, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
            }
            if (err != null) {
                Spacer(Modifier.height(4.dp))
                Text(err, color = DashColors.Warning, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
            }
            // Diagnostics, docked or not (the tile is only readable when its window is
            // missing): where the window is vs. where it should be, what the system
            // says of it, how long ago that was, and what the last command said.
            // Only in debug builds or when something went wrong: otherwise they showed
            // through whenever the window stepped aside for a menu.
            val diagnostics = BuildConfig.DEBUG || err != null
            if (pkg != null && diagnostics) {
                Spacer(Modifier.height(4.dp))
                val flags = listOfNotNull(
                    status.visible?.let { if (it) "visible" else "hidden" },
                    status.behindDashboard?.let { if (it) "behind" else "front" },
                    "${checkedAgoS}s"
                ).joinToString(" ")
                val at = (status.windowBounds?.let { "[${it.left},${it.top} ${it.right},${it.bottom}]" } ?: "?") + " $flags"
                val to = status.target?.let { "[${it.left},${it.top} ${it.right},${it.bottom}]" } ?: "?"
                Text(stringResource(R.string.apps_window_diag_position, at, to), color = DashColors.Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
                status.lastResult?.let {
                    Text(stringResource(R.string.apps_window_diag_last, it), color = DashColors.Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
                }
            }
            status.seen?.takeIf { diagnostics }?.let { seen ->
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.apps_window_diag_seen, seen), color = DashColors.Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
            }
            // Full Maps UI in a window sized to this tile (a freeform task), the
            // way the head unit's stock launcher shows it. Offered whenever the
            // docked window is not already that.
            if (status.mode != "freeform") {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val rect = target
                        val bounds = rect?.let { android.graphics.Rect(it.left, it.top, it.right, it.bottom) }
                        PipAnchor.setAutoOpen(context, true, packageName)
                        PipAnchor.openedByTile(packageName)
                        if (!SplitLauncher.launchFreeform(context, packageName, bounds)) {
                            AppLauncher.launchIntent(context, packageName)?.let { context.launchSafely(it) }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.OnAccent),
                    shape = DashShape.Medium,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) { Text(if (pkg == null) stringResource(R.string.apps_window_open, appLabel) else stringResource(R.string.apps_window_open_full, appLabel)) }
            }
        }
    }
}

/**
 * A tile status older than one idle poll (the tracker slows to one every 5 s
 * while nothing moves) and a quick one is stale: the window is not being
 * tracked right now.
 */
private const val STALE_STATUS_MS = 7_500L

/** How long a tile being dragged must hold still before its window is moved after it. */
private const val SETTLE_MS = 350L

/** What a tile remembers between re-targets: whether it was covered, and where its window was last sent. */
private class SettleState {
    var wasBlocked = false
    var tracked: ScreenRect? = null
}

/** How close (px) a pop-up may come to a window's tile before the window steps aside; covers its shadow. */
private const val POPUP_MARGIN_PX = 12

/**
 * Marks a pop-up (menu, dialog) that docked app windows must not cover: they
 * are drawn above everything on this head unit. While it is on screen, a
 * window whose tile it overlaps steps aside; windows elsewhere stay open.
 * `composed`, so the view it measures against is the pop-up's own window.
 */
internal fun Modifier.keepClearOfWindows(): Modifier = immersiveWindow().composed {
    val view = LocalView.current
    val owner = remember { Any() }
    DisposableEffect(owner) {
        onDispose { PipAnchor.coveredAreas.update { it - owner } }
    }
    onGloballyPositioned { coords ->
        val b = coords.boundsInRoot()
        val origin = IntArray(2).also { view.getLocationOnScreen(it) }
        val r = ScreenRect(
            (b.left + origin[0]).roundToInt(), (b.top + origin[1]).roundToInt(),
            (b.right + origin[0]).roundToInt(), (b.bottom + origin[1]).roundToInt()
        )
        PipAnchor.coveredAreas.update { if (it[owner] == r) it else it + (owner to r) }
    }
}
