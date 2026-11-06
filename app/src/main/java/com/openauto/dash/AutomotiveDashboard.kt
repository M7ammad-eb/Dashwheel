@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openauto.dash

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.ignitionFlow
import com.openauto.dash.vehicle.rememberVehicle
import kotlin.math.abs
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.width
import kotlin.math.min
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.geometry.Rect
import kotlin.math.roundToInt
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.compose.ReportDrawn
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource

/** How long the dashboard offers Undo after a template. */
private const val TEMPLATE_UNDO_MS = 15_000L

/** How many layout steps Undo can walk back while arranging. */
internal const val MAX_UNDO = 30

/**
 * Gap between the docked Maps window and the divider, or the pages when the
 * divider is away. The system claims the 30 dp band around a freeform window
 * as its resize handle (WindowManager's RESIZE_HANDLE_WIDTH_IN_DP) and
 * swallows drags that start in it; the divider, and a page swipe, have to
 * start outside that band to be received at all.
 */
private val DOCK_RESIZE_CLEARANCE = 32.dp

/**
 * Simple car launcher: three swipeable "virtual desktop" dashboards. Each page
 * is a grid the user fills with app shortcuts and widgets (our built-in Maps /
 * media / OBD cards, or any real Android app-widget) via the "+" tile. A single
 * Apps button opens the full app drawer. Nothing launches automatically.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AutomotiveDashboard(inSplitMode: Boolean = false) {
    val context = LocalContext.current
    // Tells the system the launcher is up once the dashboard has composed: the
    // start-up time it records (and the profile it compiles for) ends here.
    ReportDrawn()
    var themeMode by remember { mutableStateOf(DashThemeStore.load(context)) }
    var appearance by remember { mutableStateOf(DashThemeStore.loadAppearance(context)) }
    LaunchedEffect(Unit) { DashThemeStore.appearanceAsked.collect { appearance = it } }
    LaunchedEffect(Unit) { DashThemeStore.modeAsked.collect { themeMode = it } }
    var effects by remember { mutableStateOf(DashThemeStore.loadEffects(context)) }
    var barAutoHide by remember { mutableStateOf(DashThemeStore.loadBarAutoHide(context)) }
    var barHideSeconds by remember { mutableIntStateOf(DashThemeStore.loadBarHideSeconds(context)) }
    // Navigation (Settings, Display): the side rail in the bar's place, and tabs in it instead of swiping.
    var railChoice by remember { mutableStateOf(DashThemeStore.loadRail(context)) }
    var tabsChoice by remember { mutableStateOf(DashThemeStore.loadTabs(context)) }
    LaunchedEffect(Unit) { DashThemeStore.railAsked.collect { railChoice = it } }
    val railOn = DashThemeStore.railOn(railChoice, themeMode)
    val barState = rememberBarAutoHideState()
    val barRevealTap = rememberTapFeedback()
    // A look just chosen in Settings whose own Home goes on (SkinLayouts.kt), taken up below.
    var lookLayoutAsk by remember { mutableStateOf<DashThemeMode?>(null) }
    val themeState = ThemeState(
        mode = themeMode, appearance = appearance, effects = effects,
        onMode = {
            if (it != themeMode && DashThemeStore.loadLookLayout(context) && skinLayout(it) != null) lookLayoutAsk = it
            themeMode = it; DashThemeStore.save(context, it)
        },
        onArrangeHome = { if (skinLayout(themeMode) != null) lookLayoutAsk = themeMode },
        onAppearance = { appearance = it; DashThemeStore.saveAppearance(context, it) },
        onEffects = { effects = it; DashThemeStore.saveEffects(context, it) },
        barAutoHide = barAutoHide, barHideSeconds = barHideSeconds,
        onBarAutoHide = { barAutoHide = it; DashThemeStore.saveBarAutoHide(context, it) },
        onBarHideSeconds = { barHideSeconds = it; DashThemeStore.saveBarHideSeconds(context, it) },
        rail = railOn,
        tabs = tabsChoice,
        onRail = { railChoice = it; DashThemeStore.saveRail(context, it) },
        onTabs = { tabsChoice = it; DashThemeStore.saveTabs(context, it) }
    )
    var layout by remember { mutableStateOf(DashLayoutStore.load(context)) }
    // Canvas over an app (Google Maps as the wallpaper): the pages become the rail's tabs (CanvasTabs.kt).
    CanvasTabs.load(context) // returns at once after the first call
    val mapApp by CanvasTabs.mapApp.collectAsState()
    val barLists by CanvasTabs.lists.collectAsState()
    val barApps by CanvasTabs.apps.collectAsState()
    val embedAllowed = remember { EmbeddedApp.allowed(context) }
    // Tabs in the rail instead of swipes: asked for, or a map app under the
    // dashboards (which Canvas always did). The pages are the same either way.
    val tabsMode = railOn && (tabsChoice || (mapApp != null && embedAllowed))
    val tabsModeState = rememberUpdatedState(tabsMode)
    // Kept as a State and read only by the two panes it sizes: dragging the
    // divider writes it every frame, and a read here would recompose everything.
    val dockFraction = remember { mutableFloatStateOf(DashLayoutStore.loadDockFraction(context)) }
    // One arrangement per screen direction (ScreenShape.layoutPrefix): the Maps
    // dock, the theme and the tabs all show the same dashboards. The copies each
    // of those used to keep are folded in once, keeping the one in use before
    // the update (DashboardStore.mergeLegacyVariants).
    remember {
        val suffix = DashboardStore.legacySuffix(
            canvas = themeMode == DashThemeMode.CANVAS, overApp = mapApp != null && embedAllowed, docked = layout != DashLayout.GRID
        )
        DashboardStore.mergeLegacyVariants(context, suffix)?.also { CanvasTabs.mergeLegacy(suffix, it) }
    }
    // Read when called, never captured: gesture handlers outlive a composition.
    fun variant() = ScreenShape.layoutPrefix
    /** The other screen direction's arrangement. */
    fun otherVariant() = if (ScreenShape.vertical) "" else "_v"
    // What is open (sheets, dialogs, pickers, arranging, the setup, the tour):
    // DashboardUiState.kt. Read through local delegates of the same names.
    val ui = remember {
        DashboardUiState(
            settingsTab = if (ScreenShape.settingsWanted()) SettingsTab.HEAD_UNIT else null,
            setupDone = SetupStore.isDone(context),
            setupPillOff = SetupStore.pillOff(context),
        )
    }
    var showTemplates by ui::showTemplates
    var fillPage by ui::fillPage
    var barSettings by ui::barSettings
    var settingsTab by ui::settingsTab
    fun closeSettings() = ui.closeSettings()
    DashColors.Sync(themeMode, appearance, effects)
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // Shared with the second screen's cluster (SecondScreenController).
    val mediaController = remember { CarMediaController.shared(context) }
    val updateManager = remember { UpdateManager(context) }
    // The live readings stay States: reading them here would recompose the whole
    // dashboard on every OBD sample. Tiles read them where they draw them.
    // The link's state too: it flips at every dial while the adapter is out of
    // reach, and read here that would recompose the whole dashboard each time.
    val obdConnection = ObdBluetoothManager.connectionState.collectAsState()
    val obdAdapter = ObdBluetoothManager.adapter.collectAsState()
    // Demo mode (Settings → Advanced) plays its own made-up tracks in place of the real session.
    val demoState = DemoMode.active.collectAsState()
    val demoOn by demoState
    val realMedia = mediaController.mediaState.collectAsState()
    val demoMedia = DemoMode.media.collectAsState()
    val media = remember { derivedStateOf { if (demoState.value) demoMedia.value else realMedia.value } }
    val updateStatus by updateManager.status.collectAsState()

    // Enumerating every launchable app (labels + icons) is the slowest part of
    // a cold start, so it runs on IO; tiles render their placeholder until then.
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    // Bumped when an app is installed, removed or updated; the list is read again.
    var appsChanged by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val stop = AppLauncher.watchPackages(context) { appsChanged++ }
        onDispose { stop() }
    }
    LaunchedEffect(appsChanged) {
        // An update fires several callbacks in a row: read the list once they settle.
        if (appsChanged > 0) delay(1_000)
        apps = withContext(Dispatchers.IO) { AppLauncher.loadApps(context) }
    }
    val appsByPackage = remember(apps) { apps.associateBy { it.packageName } }

    var pages by remember { mutableStateOf(DashboardStore.load(context, variant())) }
    // The dashboard bar's dashboards for this layout (CanvasTabs.kt): the rail's
    // tabs over an app, the named pages of the cross otherwise.
    val barTabs = CanvasTabs.tabsFor(CanvasTabs.keyFor(variant()), barLists, pages)
    val barTabsState = rememberUpdatedState(barTabs)
    // A cross's first dashboards (its pages with tiles) are kept once seen, so
    // emptying one of them leaves it where it is instead of taking it away.
    val barKeyNow = CanvasTabs.keyFor(variant())
    LaunchedEffect(barKeyNow, barKeyNow in barLists) {
        if (barKeyNow !in barLists) CanvasTabs.saveTabs(barKeyNow, barTabsState.value)
    }
    // Over an app, the tab on screen, as a page.
    var tabPage by remember { mutableIntStateOf(barTabs.firstOrNull()?.page ?: 0) }
    // The other arrangement's window apps, cached (see windowAppsEverywhere); null = read again.
    var otherLayoutWindows by remember { mutableStateOf<Set<String>?>(null) }
    // Snapshots for Undo while arranging (newest last, capped): the pages and
    // the dashboards' names and places, so moving or removing a dashboard is
    // walked back whole.
    var history by remember { mutableStateOf<List<Snapshot>>(emptyList()) }
    // A system widget taken off a page keeps its id while Undo, or another
    // arrangement showing the same widget, can still bring it up.
    LaunchedEffect(pages, history) {
        if (!WidgetHostHolder.hasRetired(context)) return@LaunchedEffect
        // Every saved layout and backup parsed: off the main thread, which was
        // doing it inside the drop's own frame for the 30 edits an id waits.
        val saved = withContext(Dispatchers.IO) { DashboardStore.savedWidgetIds(context) }
        WidgetHostHolder.sweep(context) { saved?.plus(DashboardStore.widgetIds(pages + history.flatMap { it.pages })) }
    }

    val shellAccess = shellAccess()
    // The default layout is built before the shell is known. Once it is, and
    // nothing was saved yet, build it again: with the CANbox tiles under root,
    // without them elsewhere (see DashboardStore.defaultPages).
    LaunchedEffect(shellAccess) {
        if (shellAccess != PrivilegedShell.Access.UNKNOWN && !DashboardStore.exists(context, variant())) {
            pages = DashboardStore.load(context, variant())
        }
    }

    /** Switches layout: the Maps dock comes or goes, the dashboards stay the same. */
    val switchLayout: (DashLayout) -> Unit = { next ->
        if (next != layout) {
            layout = next
            DashLayoutStore.save(context, next)
        }
    }
    // The Maps dock gave up on a unit that opens Maps full screen (PipAnchor.giveUpWindow).
    val dockDropped by PipAnchor.dockDropped.collectAsState()
    LaunchedEffect(dockDropped) {
        if (dockDropped > 0) switchLayout(DashLayout.GRID)
    }
    var launchBarEditor by ui::launchBarEditor
    // Only the driver's dashboards are swiped (CanvasTabs.tabsFor): Home always,
    // the rest of the cross as they are added. Pages 0-2 sideways, the middle one
    // also up/down (see DashboardStore.COLUMN); the launcher starts on Home.
    val cross = remember(barTabs) { DashboardStore.shownCross(barTabs.map { it.page }) }
    val crossState = rememberUpdatedState(cross)
    // When a dashboard comes or goes the pagers are made again, on the page that
    // was on screen (Home if it went), or on one just asked for.
    val anchor = remember { PageAnchor() }
    val startPage = anchor.wanted.takeIf { it in cross } ?: anchor.shown.takeIf { it in cross } ?: DashboardStore.CENTER
    val pagerState = key(cross.row) { rememberPagerState(initialPage = cross.rowIndexOf(startPage), pageCount = { cross.row.size }) }
    val columnState = key(cross.column) { rememberPagerState(initialPage = cross.columnIndexOf(startPage), pageCount = { cross.column.size }) }
    /** The dashboard on screen, as an index into pages. */
    val currentPage by remember(pagerState, columnState) {
        derivedStateOf {
            if (tabsModeState.value) tabPage else crossState.value.pageAt(pagerState.currentPage, columnState.currentPage)
        }
    }
    SideEffect {
        anchor.shown = currentPage
        if (anchor.wanted == currentPage) anchor.wanted = -1
        // The bars' position chip (PagePosition.kt) reads where we are.
        PagePosition.update(currentPage, cross, tabsMode)
    }
    // Tabs taking over from the swipes start on the page that was on screen.
    LaunchedEffect(tabsMode) {
        if (tabsMode) tabPage = anchor.shown.takeIf { it in crossState.value } ?: DashboardStore.CENTER
    }
    /** Brings [page] on screen: back to the middle row first when it is above or below, and the reverse. Over an app, its tab. */
    fun showPage(page: Int) {
        if (tabsModeState.value) {
            tabPage = page
            return
        }
        val shown = crossState.value
        // A dashboard just added: the pagers made with it start on it.
        if (page !in shown) {
            anchor.wanted = page
            return
        }
        scope.launch {
            if (page != DashboardStore.CENTER && page in shown.column) {
                pagerState.animateScrollToPage(shown.rowHome)
                columnState.animateScrollToPage(shown.column.indexOf(page))
            } else {
                columnState.animateScrollToPage(shown.columnHome)
                pagerState.animateScrollToPage(shown.row.indexOf(page))
            }
        }
    }

    /** Home: the middle of the cross. */
    fun showHome() = showPage(DashboardStore.CENTER)
    // Cleared when this dashboard goes: a static callback kept the old one
    // (its pages, pictures and window) alive after the activity was rebuilt.
    val pickPage by rememberUpdatedState<(Int) -> Unit> { showPage(it) }
    DisposableEffect(Unit) {
        val mine: (Int) -> Unit = { pickPage(it) }
        PagePosition.onPick = mine
        onDispose { if (PagePosition.onPick === mine) PagePosition.onPick = null }
    }

    // After a page change the floating cross shows for a moment, then fades.
    // A sideways swipe above or below Home, where nothing lies beside the
    // page, moves nothing: it shows the cross too, with the way back to Home.
    var pageIndicatorShown by remember { mutableStateOf(false) }
    var pageIndicatorFor by remember { mutableIntStateOf(currentPage) }
    var wayHomePulse by remember { mutableIntStateOf(0) }
    var wayHomeShown by remember { mutableStateOf(false) }
    LaunchedEffect(currentPage, wayHomePulse) {
        val hint = wayHomePulse > 0 && currentPage == pageIndicatorFor
        if (currentPage != pageIndicatorFor || hint) {
            pageIndicatorFor = currentPage
            // The tabs say which one shows themselves.
            if (tabsModeState.value) return@LaunchedEffect
            wayHomeShown = hint
            pageIndicatorShown = true
            delay(PAGE_INDICATOR_MS)
            pageIndicatorShown = false
            wayHomeShown = false
        }
    }
    var showAllApps by ui::showAllApps
    var showSplitPicker by ui::showSplitPicker
    var showSplitEnable by ui::showSplitEnable
    var blockPagerSwipe by remember { mutableStateOf(false) }
    var editing by ui::editing
    var dashSheet by ui::dashSheet
    var appSheet by ui::appSheet
    var showBarChooser by ui::showBarChooser
    var pickBarApp by ui::pickBarApp
    var showMoments by ui::showMoments
    // A dashboard can come up by itself: as the car sets off, parks, or finds a new fault.
    FollowTabTriggers(barTabs) { trigger ->
        if (!editing && dashSheet == null) CanvasTabs.tabFor(barTabsState.value, trigger)?.let { showPage(it.page) }
    }
    // Moments (Moments.kt): Home shows the moment of the drive, in its own place.
    MomentStore.load(context)
    val momentSettings by MomentStore.settings.collectAsState()
    // An app in a window or inside a tile on Home keeps its place on screen: no moment takes it away.
    val homeHoldsApps = pages.getOrNull(DashboardStore.CENTER).orEmpty().any { it.holdsAnApp() }
    val activeMoments by rememberActiveMoments(momentSettings.master && momentSettings.on.isNotEmpty() && !homeHoldsApps)
    val wantedMoment = Moment.pick(activeMoments, momentSettings.live).takeIf { !homeHoldsApps }
    // The moment the driver sent away with the Home key; it stays away until another comes.
    var dismissedMoment by remember { mutableStateOf<Moment?>(null) }
    var shownMoment by remember { mutableStateOf<Moment?>(null) }
    // When the screen was last touched: a moment never changes Home under a finger.
    val lastTouch = remember { longArrayOf(0L) }
    LaunchedEffect(wantedMoment) {
        if (dismissedMoment != null && dismissedMoment != wantedMoment) dismissedMoment = null
    }
    LaunchedEffect(wantedMoment, dismissedMoment) {
        val target = wantedMoment?.takeIf { it != dismissedMoment }
        while (target != shownMoment && (
                System.currentTimeMillis() - lastTouch[0] < MOMENT_TOUCH_QUIET_MS ||
                    editing || showTemplates || fillPage != null || showMoments || ui.fullScreenSheet
                )
        ) delay(500)
        val began = target != null && target != shownMoment
        shownMoment = target
        // A moment beginning brings Home on screen: it is the page that follows the drive.
        if (began) showHome()
    }
    // The Home key: close whatever is open and come back to the middle of the cross.
    val homePressed by MainActivity.homePressed.collectAsState()
    LaunchedEffect(homePressed) {
        if (OneShot.fresh("home", homePressed)) {
            // Home on Home while a moment shows it: the driver's own Home, until the next moment.
            if (anchor.shown == DashboardStore.CENTER && shownMoment != null) {
                dismissedMoment = shownMoment
                shownMoment = null
            }
            ui.closeForHome()
            showHome()
        }
    }
    // A learned steering wheel button stepping through the dashboards: the
    // tabs over an app, else the pages of the middle row.
    val dashboardStep by MainActivity.dashboardStep.collectAsState()
    LaunchedEffect(dashboardStep) {
        val step = dashboardStep.second
        if (!OneShot.fresh("step", dashboardStep.first) || step == 0) return@LaunchedEffect
        if (tabsModeState.value) {
            val tabs = barTabsState.value
            val at = tabs.indexOfFirst { it.page == tabPage }.coerceAtLeast(0)
            tabs.getOrNull(CanvasTabs.stepped(at, step, tabs.size))?.let { tabPage = it.page }
        } else if (columnState.currentPage != crossState.value.columnHome) {
            // Off the row there is nothing beside the page: one explicit button press means Home, and says so.
            showPage(DashboardStore.CENTER)
            Toast.makeText(context, R.string.dash_page_home, Toast.LENGTH_SHORT).show()
        } else {
            pagerState.animateScrollToPage(CanvasTabs.stepped(pagerState.currentPage, step, crossState.value.row.size))
        }
    }
    // The same up and down the middle column, going round; over an app, through its tabs.
    val dashboardClimb by MainActivity.dashboardClimb.collectAsState()
    LaunchedEffect(dashboardClimb) {
        val step = dashboardClimb.second
        if (!OneShot.fresh("climb", dashboardClimb.first) || step == 0) return@LaunchedEffect
        if (tabsModeState.value) {
            val tabs = barTabsState.value
            val at = tabs.indexOfFirst { it.page == tabPage }.coerceAtLeast(0)
            tabs.getOrNull(CanvasTabs.stepped(at, step, tabs.size))?.let { tabPage = it.page }
        } else {
            val shown = crossState.value
            val at = if (pagerState.currentPage == shown.rowHome) columnState.currentPage else shown.columnHome
            showPage(shown.column[CanvasTabs.stepped(at, step, shown.column.size)])
        }
    }
    // Dashwheel's Gemini asking for one dashboard by its name (DashAssistant).
    val dashboardGoTo by MainActivity.dashboardGoTo.collectAsState()
    LaunchedEffect(dashboardGoTo) {
        if (OneShot.fresh("goTo", dashboardGoTo.first) && dashboardGoTo.second >= 0) showPage(dashboardGoTo.second)
    }
    // What Gemini is told about the dashboards and the music (CarFacts).
    LaunchedEffect(barTabs) { LiveFacts.dashboards = barTabs.map { it.page to it.label(context) } }
    // Where each kind of tile is, for a tap on the Now card (NowCard.kt).
    LaunchedEffect(barTabs, pages) { LiveFacts.widgetPages = widgetPagesOf(barTabs.map { it.page }, pages) }
    LaunchedEffect(mediaController) { mediaController.mediaState.collect { LiveFacts.media = it } }
    // A learned steering wheel button asking for the app drawer (SteeringWheelActions.kt).
    val openAppsRequested by MainActivity.openAppsRequested.collectAsState()
    LaunchedEffect(openAppsRequested) {
        if (OneShot.fresh("apps", openAppsRequested)) {
            closeSettings()
            showAllApps = true
        }
    }
    var hasMediaAccess by remember { mutableStateOf(CarMediaController.hasNotificationAccess(context)) }
    // The demo's music and directions need no access grant.
    val mediaAccess = hasMediaAccess || demoOn
    var showDevicePicker by ui::showDevicePicker
    var pairedDevices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var usbAdapters by remember { mutableStateOf<List<UsbObdAdapter>>(emptyList()) }

    // "+" add flow: the page a tile is being added to, and the sheet that offers them (AddSheet.kt).
    var addTargetPage by remember { mutableIntStateOf(-1) }
    var showAddSheet by ui::showAddSheet
    // Add was tapped on a page with no free cell: said at once, before anything is picked.
    var pageFull by remember { mutableStateOf<Int?>(null) }
    var layoutNotice by remember { mutableStateOf<String?>(null) }
    // (page, tile index) whose panel is open while arranging (TileOptions.kt),
    // where that tile is and where the pages are, in root coordinates.
    var tileOptions by ui::tileOptions
    var selectedTileBounds by remember { mutableStateOf<Pair<Pair<Int, Int>, Rect>?>(null) }
    var pagesArea by remember { mutableStateOf(Rect.Zero) }
    var removeChoice by ui::removeChoice
    var confirmTemplate by ui::confirmTemplate
    // A template, a shared dashboard or a look's Home just put on, said in words: the dashboard offers Undo for a while.
    var templateUndo by remember { mutableStateOf<String?>(null) }
    // Share (DashShare.kt): open from the edit bar, or by itself when the phone sends a code.
    var shareOpen by remember { mutableStateOf(false) }
    val incomingShare by DashShare.incoming.collectAsState()
    LaunchedEffect(templateUndo) {
        if (templateUndo != null) {
            delay(TEMPLATE_UNDO_MS)
            templateUndo = null
        }
    }
    var setupDone by ui::setupDone
    var setupPillOff by ui::setupPillOff
    var setupStep by ui::setupStep
    var setupShownStep by ui::setupShownStep
    var setupResume by ui::setupResume
    var offerCarTiles by ui::offerCarTiles
    /**
     * The one way out of the setup (Done, Skip, Back, the bar): it counts as
     * seen, and the tour of the basics is offered next. The pill is left as
     * it was; only "Don't remind me" on the access step turns it off.
     */
    fun closeSetup() {
        SetupStore.markDone(context); setupDone = true
        setupStep = null
        setupResume = null
        TourStore.offer(context)
    }
    var tourStep by ui::tourStep
    var tourOffered by ui::tourOffered
    var tourAfterSetup by ui::tourAfterSetup
    var tourEditing by ui::tourEditing
    /** [declined]: "Not now" on the welcome, which comes back once at the next parked start; anything else counts as seen. */
    fun closeTour(declined: Boolean = false) {
        if (declined) TourStore.notNow(context) else TourStore.markSeen(context)
        CarVoice.stop()
        tourStep = null
    }
    // Bumped on every return to the launcher: an access granted in the system settings shows at once.
    var accessGeneration by remember { mutableIntStateOf(0) }

    // The Back key (the head unit's button, or a wheel button taught to it):
    // closes what is open, top-most first, then heads back to Home. Dialogs
    // are windows of their own and take Back themselves before this runs.
    val offHome = currentPage != DashboardStore.CENTER
    BackHandler(enabled = tourStep != null || setupStep != null || showAllApps || settingsTab != null || showAddSheet || tileOptions != null || editing || offHome) {
        when {
            tourStep != null -> closeTour(declined = tourStep == TourStep.WELCOME)
            // The setup takes Back itself, a step at a time (SetupScreen).
            setupStep != null -> closeSetup()
            showAddSheet -> showAddSheet = false
            showAllApps -> showAllApps = false
            settingsTab != null -> closeSettings()
            tileOptions != null -> tileOptions = null
            editing -> editing = false
            else -> showHome()
        }
    }

    var showSystemDialog by ui::showSystemDialog
    var showObd by ui::showObd

    // The drive lock (DriveLock.kt): while the car moves, arranging, settings
    // and pickers wait. Anything open when it engages closes; a locked tap
    // shows the notice chip for a moment instead of doing nothing.
    var lockWhileMoving by remember { mutableStateOf(DriveLockStore.load(context)) }
    // Moving is also read with the lock off: the skins' background motion holds
    // still while driving (LocalCarMoving, SkinKit.kt). With no motion to hold
    // and no lock, no speed is read at all.
    val carMoving by rememberMoving(lockWhileMoving || effects != DashEffects.NONE, demoOn)
    val moving = lockWhileMoving && carMoving
    var lockNoticeAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(lockNoticeAt) {
        if (lockNoticeAt > 0L) {
            delay(LOCK_NOTICE_MS)
            lockNoticeAt = 0L
        }
    }
    /** Runs [action] now, or shows the parked-only notice while moving. */
    fun whenParked(action: () -> Unit) {
        if (moving) lockNoticeAt = System.currentTimeMillis() else action()
    }
    // The same lock for the tiles' own dialogs (LocalDriveLock, DriveLock.kt).
    val driveLock = remember(moving) { DriveLockState(moving) { lockNoticeAt = System.currentTimeMillis() } }
    LaunchedEffect(moving) {
        if (moving) ui.closeForDriving() else ui.resumeAfterDriving()
    }
    // The tour comes up by itself once the first setup has closed, parked, and
    // so has whatever a bar button opened in the setup's place.
    val tourCanStart = ui.tourCanStart(moving)
    LaunchedEffect(tourCanStart) {
        if (tourCanStart && !tourOffered && TourStore.isPending(context)) {
            tourOffered = true
            tourAfterSetup = true
            tourStep = TourStep.WELCOME
        }
    }
    LaunchedEffect(tourStep) {
        val step = tourStep
        // Its tips about the tiles and the Add button show arranging; it ends as it began.
        val wants = step?.wantsEditing == true
        if (wants && !editing) {
            editing = true
            tourEditing = true
        } else if (!wants && tourEditing) {
            editing = false
            tourEditing = false
        }
        when (step) {
            // The first tile is lit up: on Home, where there is one.
            TourStep.ARRANGE -> showHome()
            // The menu and the OBD pill are on the bar, even one that hides itself.
            TourStep.LOOK, TourStep.OBD -> barState.reveal()
            // Trying the swipe moves the tour on.
            TourStep.SWIPE -> {
                val from = currentPage
                snapshotFlow { currentPage }.first { it != from }
                delay(TOUR_SWIPE_SETTLE_MS)
                if (tourStep == TourStep.SWIPE) tourStep = TourStep.ARRANGE
            }
            else -> {}
        }
    }
    // The panel belongs to a tile of the page on screen, while arranging.
    LaunchedEffect(editing, currentPage) {
        if (tileOptions?.first != currentPage || !editing) tileOptions = null
    }

    // Docked app windows sit above dialogs and menus on this head unit. Those
    // report where they are (keepClearOfWindows), so only a window they overlap
    // steps aside. The app drawer covers everything, so it sends every window
    // aside. A page swipe, sideways or up/down, must take the pages' windows
    // along at once, but the Maps dock beside the pages does not move with them
    // and stays put.
    val fullScreenSheet = ui.fullScreenSheet
    // The tour dims the whole screen: windows step aside for it too.
    val tourOn = tourStep != null
    LaunchedEffect(fullScreenSheet, tourOn) { PipAnchor.steppedAside.value = fullScreenSheet || tourOn }
    // The floating bar would cover the bottom of the edit bar, Settings and the
    // app drawer: it steps down while they are open (a swipe up still brings
    // it), and comes back as they close, since it is what opened them.
    val barCovers = editing || fullScreenSheet
    LaunchedEffect(barCovers) {
        if (barCovers) barState.visible.targetState = false else barState.reveal()
    }
    // Followed from an effect, not read here: read in the dashboard's own
    // body, each start and end of a swipe recomposed all of it.
    LaunchedEffect(pagerState, columnState) {
        snapshotFlow { pagerState.isScrollInProgress || columnState.isScrollInProgress }
            .collect { PipAnchor.pageSwiping.value = it }
    }

    val insideAllowed = rememberInsideAllowed()

    /** Apps shown in a window by [items]' tiles. */
    fun tileWindowApps(items: List<DashboardItem>): Set<String> = items.mapNotNullTo(HashSet()) {
        when {
            it is DashboardItem.BuiltinWidget && it.kind == BuiltinKind.PIP_ANCHOR -> PipAnchor.MAPS_PACKAGE
            it is DashboardItem.AppWindow && !it.runsInside(insideAllowed) -> it.packageName
            else -> null
        }
    }

    /** Apps shown in a window by [items]' tiles, plus Maps when a layout docks it beside the pages. */
    fun windowApps(items: List<DashboardItem>): Set<String> =
        tileWindowApps(items) + if (layout != DashLayout.GRID) setOf(PipAnchor.MAPS_PACKAGE) else emptySet()

    /**
     * Window apps of every page, in both arrangements (a tile in the other layout
     * still owns its window). The other arrangement is read from storage once and
     * kept until it can change (a layout switch or a template), not on every swipe.
     */
    fun windowAppsEverywhere(): Set<String> {
        val other = otherLayoutWindows ?: tileWindowApps(DashboardStore.peek(context, otherVariant()).orEmpty())
            .also { otherLayoutWindows = it }
        return windowApps(pages.flatten()) + other
    }

    // As soon as the current page changes (mid-swipe), clear the windows whose
    // tile is not on the new page; waiting for the old page to be disposed left
    // a strip of the window visible for a few seconds after the swipe. They are
    // parked aside, still running, so a navigation or a song goes on.
    LaunchedEffect(currentPage, pages, layout) {
        PipAnchor.placedPackages.value = windowAppsEverywhere()
        PipAnchor.stashAllExcept(context, windowApps(pages.getOrNull(currentPage).orEmpty()))
    }
    // An app inside a tile runs until the last tile showing it goes, in either
    // arrangement; then its display is closed and the app with it (EmbeddedApp).
    // One the arrangement on screen shows in a window instead (Maps docked
    // beside the pages) is let go, so that window can have it.
    LaunchedEffect(pages, layout, tabsMode, mapApp) {
        // A look at the other arrangement, not a load: loading it marked its
        // kinds as the placed ones in the add sheet and swapped its unknown
        // tiles into this arrangement's next save.
        val other = withContext(Dispatchers.IO) { DashboardStore.peek(context, otherVariant()).orEmpty() }
        fun insideApps(items: List<DashboardItem>) = items.mapNotNullTo(HashSet()) {
            when {
                it is DashboardItem.BuiltinWidget && it.kind == BuiltinKind.MAPS_INSIDE -> EmbeddedApp.MAPS_PACKAGE
                it is DashboardItem.AppWindow && it.runsInside(insideAllowed) -> it.packageName
                else -> null
            }
        }
        val here = pages.flatten()
        EmbeddedApp.tilePages = buildMap { pages.forEachIndexed { page, items -> insideApps(items).forEach { putIfAbsent(it, page) } } }
        val inside = EmbeddedApp.keptInside(insideApps(here), insideApps(other), windowApps(here)).toHashSet()
        // The app under Canvas runs as long as it is there.
        if (tabsMode) mapApp?.let { inside += it }
        EmbeddedApp.releaseUnless(inside)
    }
    var rootChecked by remember { mutableStateOf(false) }
    var rootAvailable by remember { mutableStateOf(false) }
    var systemBusy by remember { mutableStateOf(false) }
    var systemInstalled by remember { mutableStateOf(false) }
    var systemMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(showSystemDialog) {
        if (showSystemDialog && !rootChecked) {
            rootAvailable = withContext(Dispatchers.IO) { SystemInstaller.isRootAvailable() }
            rootChecked = true
        }
    }

    // The privileged /system install used to run automatically on first launch
    // so the Maps tile could embed Google Maps. Embedding turned out to need
    // platform signing, so priv-app status buys nothing: the install is now
    // only offered from the system dialog (Build icon), never run unasked.

    /** Replaces one page, remembering the previous layout for Undo, and persists. */
    fun mutatePage(page: Int, transform: (List<DashboardItem>) -> List<DashboardItem>) {
        val before = pages
        val after = before.mapIndexed { i, list -> if (i == page) transform(list) else list }
        if (after == before) return
        history = (history + Snapshot(before, barTabsState.value)).takeLast(MAX_UNDO)
        pages = after
        DashboardStore.save(context, pages, variant())
    }

    /** Adds at the first free cell; returns the new tile's index, or -1 when the page is full. */
    fun addItemAt(page: Int, item: DashboardItem): Int {
        val list = pages.getOrNull(page) ?: return -1
        // Its own size where that fits, else the largest that does (AddRoom.kt).
        val fit = largestFit(list, item.w, item.h, item.minW(), item.minH())
        if (fit == null) {
            layoutNotice = context.getString(R.string.dash_notice_no_space)
            return -1
        }
        mutatePage(page) { it + item.withCell(fit.x, fit.y, fit.w, fit.h) }
        return list.size
    }

    /** Add only when the tile fits; a full page must never create a hidden overlap. */
    fun addItem(page: Int, item: DashboardItem): Boolean = addItemAt(page, item) >= 0

    /** Removing an app's last window tile ends the tile's keep-it-open duty. */
    fun releaseMapsAnchorIfGone() {
        // Both arrangements count: a window tile that exists only in the other
        // layout must keep its app's keep-open intent.
        val keep = windowAppsEverywhere()
        PipAnchor.placedPackages.value = keep
        PipAnchor.releaseAutoOpenExcept(context, keep)
    }
    // A layout switch adds or removes the Maps dock; redo the keep-open bookkeeping.
    LaunchedEffect(layout) { releaseMapsAnchorIfGone() }

    fun removeAt(page: Int, index: Int) {
        val item = pages.getOrNull(page)?.getOrNull(index) ?: return
        if (item is DashboardItem.SystemWidget) WidgetHostHolder.retire(context, item.appWidgetId)
        mutatePage(page) { list -> list.filterIndexed { i, _ -> i != index } }
        releaseMapsAnchorIfGone()
    }

    /** Replaces the tile at [index] (same cell) with [item], e.g. an edited launch bar. */
    fun updateItem(page: Int, index: Int, item: DashboardItem) {
        mutatePage(page) { list ->
            list.mapIndexed { i, old -> if (i == index) item.withCell(old.x, old.y, old.w, old.h) else old }
        }
    }

    // Move a tile's top-left to grid cell (x, y), keeping its span. A tile in
    // the way is swapped or nudged aside; only a page with no room refuses.
    fun moveCell(page: Int, index: Int, x: Int, y: Int) {
        val list = pages.getOrNull(page) ?: return
        val resolved = DashboardStore.moveResolving(list, index, x, y)
        if (resolved == null) {
            layoutNotice = context.getString(R.string.dash_notice_no_room_to_move)
            return
        }
        mutatePage(page) { resolved }
    }

    // Resize a tile to span w x h cells, keeping its top-left. Width/height are
    // capped at the grid edge from the tile's position so it grows in place
    // instead of being shoved left/up to make a too-big span fit.
    fun resizeCell(page: Int, index: Int, w: Int, h: Int) {
        val list = pages.getOrNull(page) ?: return
        val it = list.getOrNull(index) ?: return
        val cw = w.coerceIn(it.minW(), GRID_COLS - it.x)
        val ch = h.coerceIn(it.minH(), GRID_ROWS - it.y)
        if (!DashboardStore.canPlace(list, index, it.x, it.y, cw, ch)) {
            layoutNotice = context.getString(R.string.dash_notice_resize_overlap)
            return
        }
        mutatePage(page) { l -> l.mapIndexed { i, t -> if (i == index) t.withCell(t.x, t.y, cw, ch) else t } }
    }

    /** Draws the tile at [index] with its text and icons [zoom] times their size. */
    fun zoomTile(page: Int, index: Int, zoom: Float) {
        mutatePage(page) { list -> list.mapIndexed { i, t -> if (i == index) t.withZoom(zoom) else t } }
    }

    /** Frames the tile at [index]: its opacity and border, and whether it lies over the others (TileFrame). */
    fun frameTile(page: Int, index: Int, frame: TileFrame) {
        mutatePage(page) { list -> list.mapIndexed { i, t -> if (i == index) t.withFrame(frame) else t } }
    }

    /**
     * Lifts the tile at [index] over the other tiles, or lays it back into the
     * grid: where it is when its cells are free, else at the nearest free ones.
     * A page with no room for it says so, and the tile stays over the others.
     */
    fun setOverlap(page: Int, index: Int, over: Boolean) {
        val list = pages.getOrNull(page) ?: return
        val tile = list.getOrNull(index) ?: return
        if (over) {
            frameTile(page, index, tile.frame.copy(overlap = true))
            return
        }
        val landed = DashboardStore.intoGrid(list, index)
        if (landed == null) {
            layoutNotice = context.getString(R.string.dash_notice_no_grid_spot)
            return
        }
        mutatePage(page) { landed }
    }

    /** Puts the tile at [index], one over the others, on top of them ([toFront]) or under them; its panel follows it. */
    fun layerTile(page: Int, index: Int, toFront: Boolean) {
        val list = pages.getOrNull(page) ?: return
        val (reordered, at) = DashboardStore.reorder(list, index, toFront)
        mutatePage(page) { reordered }
        tileOptions = page to at
    }

    /** Restores the layout from before the last add / move / resize / remove, and the dashboards' names and places with it. */
    fun undo() {
        val previous = history.lastOrNull() ?: return
        history = history.dropLast(1)
        pages = previous.pages
        DashboardStore.save(context, pages, variant())
        if (previous.tabs != barTabsState.value) CanvasTabs.saveTabs(CanvasTabs.keyFor(variant()), previous.tabs)
    }

    /** Empties one dashboard (releasing any hosted app-widgets); it stays in the swipes. Undo brings the tiles back. */
    fun emptyDashboard(page: Int) {
        tileOptions = null
        pages.getOrNull(page)?.filterIsInstance<DashboardItem.SystemWidget>()
            ?.forEach { WidgetHostHolder.retire(context, it.appWidgetId) }
        mutatePage(page) { emptyList() }
        releaseMapsAnchorIfGone()
    }

    /**
     * Takes a dashboard out of the swipes, its tiles with it; one Undo step
     * brings both back, name and place too. Home stays.
     */
    fun removeDashboard(page: Int) {
        if (page == DashboardStore.CENTER) return
        tileOptions = null
        val tabsBefore = barTabsState.value
        history = (history + Snapshot(pages, tabsBefore)).takeLast(MAX_UNDO)
        pages.getOrNull(page)?.filterIsInstance<DashboardItem.SystemWidget>()
            ?.forEach { WidgetHostHolder.retire(context, it.appWidgetId) }
        pages = pages.mapIndexed { i, l -> if (i == page) emptyList() else l }
        DashboardStore.save(context, pages, variant())
        if (tabPage == page) tabPage = DashboardStore.CENTER
        CanvasTabs.saveTabs(CanvasTabs.keyFor(variant()), tabsBefore.filter { it.page != page })
        releaseMapsAnchorIfGone()
    }

    /** The old name of [removeDashboard], kept for its callers. */
    fun removeCrossDashboard(page: Int) = removeDashboard(page)

    /** Replaces every page at once (a template); one Undo step brings them all back. */
    fun mutateAll(after: List<List<DashboardItem>>) {
        if (after == pages) return
        history = (history + Snapshot(pages, barTabsState.value)).takeLast(MAX_UNDO)
        pages = after
        DashboardStore.save(context, pages, variant())
    }

    val screenConfig = LocalConfiguration.current
    // What the car says now, from any source: the templates leave out only the tiles no source can feed.
    val suppliedKeys by UnitPlatform.supplied.collectAsState(initial = UnitPlatform.suppliedKeys())
    /** The car and screen a template is placed for, in the full-width ([half] false) or docked arrangement. */
    fun templateScreen(half: Boolean): TemplateScreen = TemplateScreen.of(
        // Upright, the Maps dock sits above or below the pages and takes height, not width.
        pageWidthDp = screenConfig.screenWidthDp * if (half && !ScreenShape.vertical) 1f - dockFraction.floatValue else 1f,
        // Roughly what the bars leave the grid.
        pageHeightDp = screenConfig.screenHeightDp * (if (ScreenShape.vertical) 0.9f else 0.8f) *
            if (half && ScreenShape.vertical) 1f - dockFraction.floatValue else 1f,
        obdPaired = obdAdapter.value.canDial || obdConnection.value == ObdConnectionState.CONNECTED,
        carKeys = suppliedKeys,
        driverOnRight = CarProfileStore.current.driverOnRight,
        mapsDocked = half,
        dockApps = TemplatePlacer.dockApps(pages, appsByPackage.keys),
        canbox = shellAccess.root,
        tyres = UnitPlatform.delivers(Cap.TYRES)
    )

    // What the moment on Home shows: laid out for this screen, worked out once per moment and arrangement.
    val momentItems = shownMoment?.let { m ->
        remember(m, momentSettings, layout, pages, appsByPackage.keys, obdAdapter.value, shellAccess, screenConfig) {
            MomentStore.itemsFor(momentSettings, m, variant(), templateScreen(layout != DashLayout.GRID))
        }
    }

    /**
     * Lays the template of [choice] out on its pages: every one of them when
     * replacing, else only the empty dashboards among them that are in the
     * swipes (and the ones above and below when they were asked for). Each
     * page filled takes the template's name for it. One Undo step takes it
     * all back. False when there was nothing to fill, which is said.
     */
    fun applyTemplate(choice: TemplateChoice): Boolean {
        val shown = crossState.value
        val askedColumn = choice.pages.containsAll(DashboardStore.COLUMN)
        val targets = choice.pages.filter { p ->
            p in choice.template.pages && (choice.replace || (pages.getOrNull(p).orEmpty().isEmpty() && (p in shown || askedColumn)))
        }
        if (targets.isEmpty()) {
            layoutNotice = context.getString(R.string.templates_none_empty)
            return false
        }
        val built = TemplatePlacer.pages(choice.template, templateScreen(layout != DashLayout.GRID), targets.toSet())
        if (choice.replace) {
            targets.flatMap { pages.getOrNull(it).orEmpty() }.filterIsInstance<DashboardItem.SystemWidget>()
                .forEach { WidgetHostHolder.retire(context, it.appWidgetId) }
        }
        val named = targets.fold(barTabsState.value) { tabs, p ->
            CanvasTabs.named(tabs, p, choice.template.pages[p]?.preset?.takeIf { p != DashboardStore.CENTER })
        }
        mutateAll(pages.mapIndexed { p, old -> if (p in targets) built[p] else old })
        CanvasTabs.saveTabs(CanvasTabs.keyFor(variant()), named)
        releaseMapsAnchorIfGone()
        return true
    }

    /**
     * Puts the Home [mode] was drawn around on Home (SkinLayouts.kt), as one
     * Undo step. Home holding an app (Maps or Waze in a tile) keeps it: the
     * driver arranged it around that app. False when nothing changed.
     */
    fun applyLookLayout(mode: DashThemeMode): Boolean {
        val look = skinLayout(mode) ?: return false
        val center = DashboardStore.CENTER
        val old = pages.getOrNull(center).orEmpty()
        if (old.any { it.holdsAnApp() }) return false
        val built = skinHome(look, templateScreen(layout != DashLayout.GRID))
        if (built.isEmpty() || built == old) return false
        old.filterIsInstance<DashboardItem.SystemWidget>().forEach { WidgetHostHolder.retire(context, it.appWidgetId) }
        mutateAll(pages.mapIndexed { p, items -> if (p == center) built else items })
        releaseMapsAnchorIfGone()
        return true
    }
    LaunchedEffect(lookLayoutAsk) {
        val mode = lookLayoutAsk ?: return@LaunchedEffect
        lookLayoutAsk = null
        if (applyLookLayout(mode)) templateUndo = context.getString(R.string.look_layout_applied, context.getString(mode.titleRes))
    }

    // System app-widget picker; adds the bound widget to the page that requested it.
    val addSystemWidget = rememberSystemWidgetAdder { id ->
        if (addTargetPage in 0 until DashboardStore.PAGE_COUNT) {
            if (!addItem(addTargetPage, DashboardItem.SystemWidget(id))) {
                // The picker already allocated a host ID. Release it if the
                // layout cannot accept the widget, otherwise it leaks unused IDs.
                WidgetHostHolder.delete(context, id)
            }
        }
    }

    // Restarted when the clock choice changes, so the bar switches at once.
    val units = LocalUnits.current
    var clock by remember { mutableStateOf(currentClock(units)) }
    // Started again at each switch-on: the wait below stands still while the
    // unit sleeps, and the bar showed last night's time for up to a minute.
    val ignition = rememberVehicle(K.IGNITION)
    LaunchedEffect(units.clock24, ignition) {
        while (true) {
            clock = currentClock(units)
            // The bar shows hours and minutes, so wake at the next minute boundary (+ a beat).
            delay(60_000L - System.currentTimeMillis() % 60_000L + 50L)
        }
    }

    // Observe media; re-check notification access on resume so granting it in
    // system settings takes effect without an app restart. The OBD link, its
    // poll and the reconnect run in VehicleMonitor, for the whole process; the
    // screen only says whether the launcher is in front.
    DisposableEffect(lifecycleOwner) {
        ObdBluetoothManager.setContext(context)
        McuReader.setContext(context)
        PrivilegedShell.setContext(context)
        WeatherRepo.setContext(context)
        ApiKeys.setContext(context)
        // Shizuku's binder arrives on its own once it runs; the probe is told when it does.
        ShizukuShell.start(context)
        PrivilegedShell.probe()
        PmPatch.finishSetup(context)
        CarProfileStore.setContext(context)
        MyCarLook.setContext(context)
        SpeedCorrection.setContext(context)
        MediaVolume.setContext(context)
        SpeedVolume.start(context)
        CarCare.setContext(context)
        Maintenance.setContext(context)
        DriveLog.start(context)
        FuelLog.start(context)
        SinceFill.start(context)
        FuelUsed.start(context)
        LpgTank.start(context)
        BarItems.setContext(context)
        Radios.start(context)
        MessageAlerts.start()
        CarNews.start(context)
        PidExplorer.setContext(context)
        MyReadings.setContext(context)
        AiMechanic.setContext(context)
        SensorWatch.setContext(context)
        SteeringWheelStore.setContext(context)
        // Read now: a wheel button may ask for home, or the drawer for the apps used most, at any time.
        PlacesStore.load(context)
        AppUsage.load(context)
        StartupBriefing.start(context)
        VehicleMonitor.start(context)
        mediaController.acquire()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    hasMediaAccess = CarMediaController.hasNotificationAccess(context)
                    // Root granted or ADB turned on meanwhile: the features come back with it.
                    PrivilegedShell.refresh()
                    accessGeneration++
                    if (hasMediaAccess) mediaController.start()
                    VehicleMonitor.connectSaved()
                    VehicleMonitor.setForeground(true)
                }
                Lifecycle.Event.ON_PAUSE -> VehicleMonitor.setForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            VehicleMonitor.setForeground(false)
            mediaController.release()
        }
    }

    // The unit sleeps at ignition off rather than restarting, often for days: a
    // check at start alone missed every release after it. So also at each
    // ignition on, and every six hours awake. Never in the Play edition: its
    // updates come from Google Play (UpdateManager stays Idle there anyway).
    if (Edition.full) LaunchedEffect(Unit) {
        launch { ignitionFlow().drop(1).filter { it == true }.collect { updateManager.checkForUpdate() } }
        while (true) {
            updateManager.checkForUpdate()
            delay(UPDATE_CHECK_EVERY_MS)
        }
    }
    // The head unit running hot is said once, tile or not (HeadUnitMonitor).
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { HeadUnitMonitor.watchHeat(context) } }

    // Installing restarts the launcher, and the permission screen is another
    // app's: neither while the car moves.
    val installUpdate: () -> Unit = {
        val status = updateStatus
        val info = status.updateInfo
        if (info != null && status !is UpdateStatus.Downloading && status !is UpdateStatus.Installing) whenParked {
            when {
                !updateManager.canInstallPackages() -> {
                    // Android's own page comes up without a word: say what to do there, and that Update is to be tapped again.
                    Toast.makeText(context, R.string.dash_update_allow_installs, Toast.LENGTH_LONG).show()
                    updateManager.openInstallPermissionSettings()
                }
                status is UpdateStatus.Ready -> updateManager.install(status.file)
                else -> scope.launch { updateManager.downloadAndInstall(info) }
            }
        }
    }
    // "Update to vX" (the ⋮ menu, Settings) first shows what the build brings:
    // the release's notes, with the Update button that installs it.
    var releaseNotes by remember { mutableStateOf<UpdateInfo?>(null) }
    val onUpdate: () -> Unit = {
        val info = updateStatus.updateInfo
        if (info != null) whenParked { releaseNotes = info }
    }
    // Asked by hand (Settings, the beta switch): a build found opens its notes
    // at once, "not this version" or not; the timed checks stay silent (issue #76).
    val checkForUpdates: () -> Unit = {
        scope.launch {
            updateManager.checkForUpdate()
            updateManager.status.value.updateInfo?.let { info -> whenParked { releaseNotes = info } }
        }
    }
    // A newer build downloads by itself on a connection that costs nothing
    // (never over a phone's hotspot), then asks once, parked: now or later.
    // No strip on the dashboard: a dot on ⋮ and a row in Settings say the rest.
    var autoDownloadedBuild by remember { mutableLongStateOf(-1L) }
    var promptedBuild by remember { mutableLongStateOf(-1L) }
    var updatePrompt by remember { mutableStateOf<UpdateStatus.Ready?>(null) }
    LaunchedEffect(updateStatus, moving) {
        val status = updateStatus
        if (status is UpdateStatus.Available && autoDownloadedBuild != status.info.buildNumber &&
            updateManager.canInstallPackages() && updateManager.onUnmeteredNetwork()
        ) {
            autoDownloadedBuild = status.info.buildNumber
            updateManager.download(status.info)
        }
        if (status is UpdateStatus.Ready && !moving && promptedBuild != status.info.buildNumber) {
            promptedBuild = status.info.buildNumber
            // One dialog at a time: the notes give way to "Update ready".
            releaseNotes = null
            updatePrompt = status
        }
    }

    /** The adapter picker is a list to read: parked only. Reconnecting a saved one needs no picker. */
    fun openDevicePicker() = whenParked {
        // The paired list and the USB list come from the Bluetooth and USB services: off the main thread.
        scope.launch {
            val (paired, usb) = withContext(Dispatchers.IO) { ObdBluetoothManager.bondedDevices() to UsbObd.attached(context) }
            pairedDevices = paired
            usbAdapters = usb
            showDevicePicker = true
        }
    }

    fun connectSavedOrPick() {
        val saved = ObdBluetoothManager.savedDeviceAddress()
        // Tapped by the driver: Bluetooth is switched on and a lost pairing redone (Android asks for the PIN).
        if (saved != null) scope.launch { ObdBluetoothManager.connect(saved, byDriver = true) } else openDevicePicker()
    }

    // Through the phone, the phone holds the adapter: nothing to pick or allow on this unit.
    val connectObd: () -> Unit = { scope.launch { ObdBluetoothManager.connect(ObdBluetoothManager.savedDeviceAddress().orEmpty(), byDriver = true) } }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) connectSavedOrPick()
    }

    val ensureBluetooth: (() -> Unit) -> Unit = { action ->
        val missing = requiredBluetoothPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray()) else action()
    }

    val onConnectObd: () -> Unit = {
        when {
            // Deep OBD makes the connection to the car itself: open it.
            DeepObdSource.owns() -> DeepObdSource.launch(context)
            // On a cable: Android asks for access to it if needed, nothing to pair. Through the phone: nothing to pair either.
            obdAdapter.value.usb || obdAdapter.value.phone -> connectObd()
            else -> ensureBluetooth { connectSavedOrPick() }
        }
    }
    val onPickDevice: () -> Unit = { ensureBluetooth { openDevicePicker() } }

    val onLaunchApp: (String) -> Unit = { pkg ->
        if (AppLauncher.launch(context, pkg)) showAllApps = false
    }

    val onLaunchSplitPair: (String, String) -> Unit = { primary, secondary ->
        if (SplitLauncher.isSystemSplitAvailable()) SplitLauncher.launchSplitPair(context, primary, secondary)
        else showSplitEnable = true
    }

    val onAdd: (Int) -> Unit = { page ->
        whenParked {
            addTargetPage = page
            // A full page says so now, not after the tile was picked.
            if (pageHasRoom(pages.getOrNull(page).orEmpty())) showAddSheet = true else pageFull = page
        }
    }
    // The bar stays in reach under the sheets that fill the pages (Settings,
    // the setup, the add sheet, the app grid): what it starts closes them
    // first, so nothing opens underneath one.
    val closeSheets: () -> Unit = {
        closeSettings()
        showAddSheet = false
        showAllApps = false
        if (setupStep != null) closeSetup()
    }
    // The bar's "Finish setting up" pill: a tile on some page still lacks
    // what it needs, the setup has been seen, and the driver has not skipped it.
    // Looked at again when the OBD adapter changes (all the answer depends on), but only a change of the answer reaches the bar.
    val setupPending by remember(pages, accessGeneration, setupDone, setupPillOff) {
        derivedStateOf {
            obdAdapter.value
            setupDone && !setupPillOff && AccessNeed.pending(context, pages).isNotEmpty()
        }
    }

    // Android forces the status bar on whenever a floating (freeform) window is
    // on screen, i.e. while a Maps window is docked, unless the head unit can
    // keep it off (FreeformBar). The head unit's bar paints
    // over its strip and takes every tap there, so the dashboard lays out below
    // it; the bar already shows the time, so the launcher bar swaps its clock
    // for the page dots. Not via WindowInsets.statusBars: on Android 10 that
    // stays at the bar's height even while the bar is hidden, which pushed the
    // whole dashboard down permanently.
    val barForced by FreeformBar.forced.collectAsState()
    val density = LocalDensity.current
    val rootView = LocalView.current
    val statusBarPx = WindowInsets.statusBarsIgnoringVisibility.getTop(density)
    // Only the part of the bar that really covers the dashboard is reserved.
    // When the bar comes up, the head unit already shifts the window's content
    // down below it while still reporting the bar's full height as an inset;
    // padding by that inset again left an empty strip as tall as the bar
    // between the bar and the tiles. So the padding is the bar's bottom edge
    // minus where the content actually starts on the screen, never less than 0.
    var contentTopPx by remember { mutableIntStateOf(0) }
    // Everything the bar shows and can do, built once: the bar, its menu and
    // the Settings screen all read the same model.
    val settingsModel = TopBarModel(
        clock = clock,
        versionName = updateManager.currentVersionName,
        obdConnectionState = obdConnection,
        editing = editing,
        layout = layout,
        onLayout = { next ->
            closeSheets()
            switchLayout(next)
        },
        onApps = {
            closeSheets()
            showAllApps = true
        },
        onConnectObd = onConnectObd,
        onSplit = {
            whenParked {
                closeSheets()
                if (SplitLauncher.isSystemSplitAvailable()) showSplitPicker = true
                else showSplitEnable = true
            }
        },
        onToggleEdit = {
            if (editing) editing = false
            else whenParked {
                closeSheets()
                editing = true
            }
        },
        onTemplates = {
            whenParked {
                closeSheets()
                showTemplates = true
            }
        },
        onSystem = { whenParked { showSystemDialog = true } },
        onObd = { showObd = true },
        onCheckUpdates = checkForUpdates,
        update = updateStatus,
        onUpdate = onUpdate,
        onDismissUpdate = { updateManager.dismiss() },
        setupPending = setupPending,
        onSetup = { fromStart ->
            whenParked {
                closeSheets()
                setupStep = if (fromStart) SetupStep.CAR else SetupStep.ACCESS
            }
        },
        demo = demoOn,
        onDemo = { DemoMode.toggle(context) },
        merged = barForced,
        page = currentPage,
        moving = moving,
        lockWhileMoving = lockWhileMoving,
        onLockWhileMoving = {
            lockWhileMoving = it
            DriveLockStore.save(context, it)
        },
        onSettings = {
            whenParked {
                closeSheets()
                editing = false
                settingsTab = SettingsTab.OVERVIEW
            }
        },
        onMoments = {
            whenParked {
                closeSettings()
                showMoments = true
            }
        },
        onTour = {
            whenParked {
                closeSheets()
                editing = false
                tourAfterSetup = false
                tourStep = TourStep.WELCOME
            }
        }
    )

    // The bar lives at the bottom: the OS status bar owns the top edge on
    // this head unit whenever a floating window is on screen, and it used to
    // cover the launcher bar there. A horizontal swipe across the bar (or
    // the page dots) changes page, for when a docked window covers the pages.
    // A parked window's cover sits in the bottom-right corner; the bar stops
    // short of it so its ⋮ button stays reachable.
    val coverShowing by ParkedCover.showing.collectAsState()
    val launcherBar: @Composable (@Composable () -> Unit) -> Unit = { bar ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = if (coverShowing) (ParkedCover.WIDTH_DP + 4).dp else 0.dp)
                // A long press beside the bar's buttons opens the bar's own settings (BarSettingsSheet).
                .pointerInput(Unit) { detectTapGestures(onLongPress = { whenParked { barSettings = true } }) }
                .pointerInput(Unit) {
                    var dragged = 0f
                    val threshold = 48.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = 0f },
                        onDragEnd = {
                            val step = when {
                                dragged <= -threshold -> 1
                                dragged >= threshold -> -1
                                else -> 0
                            }
                            // Sideways along the middle row; from a page above or
                            // below the centre that means back to the row first.
                            // With that option on, the second screen's cluster takes it.
                            if (step != 0 && SecondScreenStore.config.value.barSwipeTurnsPages && SecondScreenController.showsCluster) {
                                SecondScreenController.turnPage(step)
                            } else if (step != 0) {
                                if (columnState.currentPage != cross.columnHome) {
                                    wayHomePulse++
                                } else {
                                    val next = (pagerState.currentPage + step).coerceIn(0, cross.row.size - 1)
                                    scope.launch { pagerState.animateScrollToPage(next) }
                                }
                            }
                        }
                    ) { change, dx ->
                        change.consume()
                        dragged += dx
                    }
                }
        ) {
            BarSize.Scaled(bar)
        }
    }

    // Arranging with the edit bar up (a split screen's half has no room for it).
    val arranging = editing && !inSplitMode
    val barOverlapPx = if (barForced) (statusBarPx - contentTopPx).coerceAtLeast(0) else 0
    // Canvas: the live map is the page itself. The rail at the driver's side
    // stands in for the bar where it is asked for (Display, Navigation; on by
    // default with Canvas).
    val canvas = DashColors.Skin == DashSkin.CANVAS
    // Tabs in the rail: nothing is swiped, and an app under the dashboards is the wallpaper beside the rail.
    val tabsShown = tabsMode
    // The Maps dock is a real app window, drawn over the dashboard: on the
    // rail's side it covered the rail and, with it, the ⋯ menu. The rail
    // then moves to the other side, next to the pages.
    val driverOnRight = CarProfileStore.current.driverOnRight
    val dockOnRailSide = !inSplitMode && !tabsShown && !ScreenShape.vertical &&
        (layout == DashLayout.MAPS_LEFT && !driverOnRight || layout == DashLayout.MAPS_RIGHT && driverOnRight)
    val railOnRight = if (dockOnRailSide) !driverOnRight else driverOnRight
    SideEffect { CanvasTabs.railShown = tabsShown }
    val barModel = DashBarModel(
        tabs = barTabs,
        currentPage = currentPage,
        apps = barApps,
        appsByPackage = appsByPackage,
        onSelect = { showPage(it.page) },
        onOpenTab = { whenParked { dashSheet = it } },
        onLaunch = onLaunchApp,
        onOpenApp = { whenParked { appSheet = it } },
        onAddDashboard = { whenParked { dashSheet = -1 } },
        onAddApp = { whenParked { pickBarApp = true } },
        canAddDashboard = CanvasTabs.freePage(barTabs, pages) != null
    )
    val viewConfig = LocalViewConfiguration.current
    val carTouch = remember(viewConfig) { carTouchConfiguration(viewConfig) }
    // The previews of the templates and Moments screens: a dashboard page at
    // its real size, its tiles drawn as on the dashboard; apps in windows,
    // system widgets and the 3D car as while arranging (their stand-ins, never
    // a second live one). The map is drawn by the preview itself.
    val previewPageSize = with(density) {
        if (pagesArea.width > 0f) DpSize(pagesArea.width.toDp(), pagesArea.height.toDp())
        else DpSize(screenConfig.screenWidthDp.dp, (screenConfig.screenHeightDp * 0.85f).dp)
    }
    val previewTile: @Composable (DashboardItem) -> Unit = { item ->
        TileContent(
            item = item, editing = item.standsIn(), appsByPackage = appsByPackage, media = media, mediaController = mediaController,
            hasMediaAccess = mediaAccess, context = context, obdConnection = obdConnection,
            onConnectObd = {}, onPickDevice = {}, onLaunchApp = {}, onLaunchSplitPair = { _, _ -> },
            onEditLaunchBar = {}, onModelTouch = {}
        )
    }
    CompositionLocalProvider(
        LocalViewConfiguration provides carTouch,
        LocalDriveLock provides driveLock,
        LocalCarMoving provides carMoving,
        LocalDashboards provides Dashboards(barTabs, tabbed = tabsShown),
        LocalDashBar provides barModel
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastTouch[0] = System.currentTimeMillis()
                    }
                }
            }
    ) {
    val underApp = mapApp
    if (tabsShown && underApp != null) {
        CanvasAppBackdrop(underApp, railOnRight, Modifier.fillMaxSize())
    } else if (canvas) CanvasBackdrop(Modifier.fillMaxSize())
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(dashBackground())
            .onGloballyPositioned { coords ->
                val origin = IntArray(2).also { rootView.getLocationOnScreen(it) }
                contentTopPx = origin[1] + coords.positionInRoot().y.roundToInt()
            }
            .padding(top = with(density) { barOverlapPx.toDp() })
    ) {

        Box(
            modifier = Modifier
                .weight(1f)
                // With auto-hide the bar floats over the pages; once it has gone,
                // a swipe up from their bottom edge brings it back.
                .then(if (barAutoHide && !railOn) Modifier.swipeUpRevealsBar(barState, barRevealTap) else Modifier)
                // Docked app windows must stay inside this area (above the bar,
                // unless it floats over the pages and they step aside for it).
                .tourTarget(TourTarget.PAGES)
                .onGloballyPositioned { coords ->
                    val b = coords.boundsInRoot()
                    pagesArea = b
                    val origin = IntArray(2).also { rootView.getLocationOnScreen(it) }
                    PipAnchor.allowedArea.value = ScreenRect(
                        (b.left + origin[0]).roundToInt(), (b.top + origin[1]).roundToInt(),
                        (b.right + origin[0]).roundToInt(), (b.bottom + origin[1]).roundToInt()
                    )
                }
        ) {
            // "Maps left" layout: a permanent Google Maps dock takes the left half
            // and never leaves composition, so the window is placed once and
            // swiping pages never touches it. Not while the OS itself has us in
            // split-screen: half of a half is too small for either.
            val dockSide = if (inSplitMode || tabsShown) null else when (layout) {
                DashLayout.MAPS_LEFT -> Alignment.Start
                DashLayout.MAPS_RIGHT -> Alignment.End
                DashLayout.GRID -> null
            }
            // Upright, the dock sits above the pages (MAPS_LEFT) or below them (MAPS_RIGHT).
            val vertical = ScreenShape.vertical
            // While arranging, drag the divider to trade room between the dock and
            // the pages; the dock re-measures itself, so the Maps window follows once released.
            var splitLengthPx by remember { mutableIntStateOf(1) }
            /** One dashboard, by its index into pages. */
            val dashboardPage: @Composable (Int) -> Unit = { page ->
                // The pagers keep the pages beside this one composed; their
                // window tiles must leave the windows alone (LocalPageOnScreen),
                // and their clocks and motion stand still until the page can be
                // seen: as soon as the pages start sliding (LocalPageActive).
                // Home shows the moment of the drive, unless it is being arranged.
                val moment = shownMoment.takeIf { page == DashboardStore.CENTER && !editing }
                CompositionLocalProvider(
                    LocalPageOnScreen provides (page == currentPage),
                    // Only the pages a swipe can bring on screen wake: the row's for a sideways one, the column's for an up/down one.
                    LocalPageActive provides (
                        page == currentPage ||
                            (page in cross.row && pagerState.isScrollInProgress) ||
                            (page in cross.column && columnState.isScrollInProgress)
                        )
                ) {
                Box(Modifier.fillMaxSize()) {
                DashboardPage(
                    pageItems = if (moment != null) momentItems ?: pages[page] else pages[page],
                    editing = editing,
                    inSplitMode = inSplitMode,
                    onModelTouch = { blockPagerSwipe = it },
                    appsByPackage = appsByPackage,
                    media = media,
                    mediaController = mediaController,
                    hasMediaAccess = mediaAccess,
                                obdConnection = obdConnection,
                    onConnectObd = onConnectObd,
                    onPickDevice = onPickDevice,
                    onLaunchApp = onLaunchApp,
                    onLaunchSplitPair = onLaunchSplitPair,
                    onEditLaunchBar = { index -> if (moment == null) whenParked { launchBarEditor = page to index } },
                    onMoveCell = { index, x, y -> moveCell(page, index, x, y) },
                    onResizeCell = { index, w, h -> resizeCell(page, index, w, h) },
                    canPlace = { index, x, y, w, h ->
                        DashboardStore.canPlace(pages[page], index, x, y, w, h)
                    },
                    canMove = { index, x, y ->
                        DashboardStore.moveResolving(pages[page], index, x, y) != null
                    },
                    onAdd = { onAdd(page) },
                    onTemplates = { whenParked { showTemplates = true } },
                    onFillPage = { whenParked { fillPage = page } },
                    // Held, the page starts arranging; not over an app, which gets the touches between tiles.
                    onHoldPage = if (editing || tabsShown) null else ({ whenParked { closeSheets(); editing = true } }),
                    onTileOptions = { index -> tileOptions = page to index },
                    selectedIndex = tileOptions?.takeIf { it.first == page }?.second ?: -1,
                    onSelectedBounds = { bounds -> tileOptions?.let { selectedTileBounds = it to bounds } },
                    onTapEmpty = { tileOptions = null },
                    // An empty tab is the map alone; the edit bar still adds to it.
                    emptyPrompt = !tabsShown
                )
                if (moment != null) {
                    MomentChip(moment, Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 14.dp)) {
                        dismissedMoment = moment
                        shownMoment = null
                    }
                }
                }
                }
            }
            // The pages' pane reads the dock fraction and the pagers' scroll
            // The pages' pane reads the pagers' scroll state itself, so a swipe
            // starting and ending recomposes this pane, not the dashboard.
            val pagesPane: @Composable () -> Unit = pane@{
            // Over an app nothing is swiped: one tab's page at a time, and every
            // touch between its tiles goes on to the app under it.
            if (tabsShown) {
                val tab = tabPage.coerceIn(0, DashboardStore.PAGE_COUNT - 1)
                SideEffect { ClockInSight.on.value = pages.getOrNull(tab).orEmpty().any { it.isClock() } }
                Crossfade(targetState = tab, label = "tab") { t -> dashboardPage(t) }
                return@pane
            }
            // Sideways swipes only from the middle row: the pages above and
            // below the centre one have nothing beside them.
            val onHomeRow = columnState.currentPage == cross.columnHome && !columnState.isScrollInProgress
            // The neighbouring pages stay composed: a map tile survives a swipe
            // away and back instead of rebuilding its GL surface. Not for the
            // first frames: at a cold start the page on screen is drawn alone,
            // the four beside it right after, long before a swipe can reach them.
            // The row's neighbours first, the column's a couple of frames later: four pages in one frame was a long one.
            var beyondRow by remember { mutableIntStateOf(0) }
            var beyondColumn by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                withFrameNanos { }
                beyondRow = 1
                withFrameNanos { }
                withFrameNanos { }
                beyondColumn = 1
            }
            Box(Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !blockPagerSwipe && onHomeRow,
                beyondViewportPageCount = beyondRow,
                modifier = Modifier.fillMaxSize()
            ) { index ->
                val page = cross.row.getOrElse(index) { DashboardStore.CENTER }
                if (page == DashboardStore.CENTER) {
                    VerticalPager(
                        state = columnState,
                        userScrollEnabled = !blockPagerSwipe && !pagerState.isScrollInProgress,
                        beyondViewportPageCount = beyondColumn,
                        // Off the home row a sideways swipe moves nothing: there is
                        // nothing beside the page. It shows the cross with the way
                        // back to Home instead, so the swipe never feels dead.
                        modifier = Modifier.fillMaxSize().pointerInput(onHomeRow, blockPagerSwipe) {
                            if (onHomeRow || blockPagerSwipe) return@pointerInput
                            var dragged = 0f
                            val threshold = 48.dp.toPx()
                            detectHorizontalDragGestures(
                                onDragStart = { dragged = 0f },
                                onDragEnd = { if (abs(dragged) >= threshold) wayHomePulse++ }
                            ) { change, dx ->
                                dragged += dx
                                change.consume()
                            }
                        }
                    ) { row -> dashboardPage(cross.column.getOrElse(row) { DashboardStore.CENTER }) }
                } else {
                    dashboardPage(page)
                }
            }
            val shown = cross.pageAt(pagerState.currentPage, if (onHomeRow) cross.columnHome else columnState.currentPage)
            SideEffect { ClockInSight.on.value = pages.getOrNull(shown).orEmpty().any { it.isClock() } }
            // An app running in a tile takes every swipe over it: a page filled by one
            // could not be left. Its edges still turn the page.
            if (!editing && pages.getOrNull(shown).orEmpty().any { it.runsAnApp() }) {
                PageEdgeSwipes(
                    sideways = if (onHomeRow && cross.row.size < 2) null else { forward ->
                        if (onHomeRow) {
                            val to = (pagerState.currentPage + if (forward) 1 else -1).coerceIn(0, cross.row.lastIndex)
                            scope.launch { pagerState.animateScrollToPage(to) }
                        } else {
                            wayHomePulse++
                        }
                    },
                    upDown = if (pagerState.currentPage != cross.rowHome || cross.column.size < 2) null else { forward ->
                        val to = (columnState.currentPage + if (forward) 1 else -1).coerceIn(0, cross.column.lastIndex)
                        scope.launch { columnState.animateScrollToPage(to) }
                    }
                )
            }
            }
            }
            DockSplit(
                vertical = vertical,
                docked = dockSide != null,
                dockFirst = dockSide == Alignment.Start,
                dockFraction = { dockFraction.floatValue },
                modifier = Modifier.fillMaxSize()
                    .then(if (railOn) Modifier.padding(start = if (railOnRight) 0.dp else CANVAS_RAIL_SPACE, end = if (railOnRight) CANVAS_RAIL_SPACE else 0.dp) else Modifier)
                    .onSizeChanged { splitLengthPx = if (vertical) it.height else it.width },
                dock = {
                    // Android treats the 30 dp around a freeform window as its
                    // resize handle and takes any drag that starts there for the
                    // system, so the divider must sit further away than that from
                    // the Maps window's edge or dragging it does nothing.
                    val first = dockSide == Alignment.Start
                    val near = DOCK_RESIZE_CLEARANCE
                    Box(
                        modifier = Modifier.fillMaxSize().padding(
                            start = if (vertical || first) 8.dp else near,
                            end = if (vertical || !first) 8.dp else near,
                            top = if (!vertical || first) 8.dp else near,
                            bottom = if (!vertical || !first) 8.dp else near
                        )
                    ) {
                        PipAnchorCard(modifier = Modifier.fillMaxSize(), isDock = true)
                    }
                },
                // Only while arranging: the rest of the time its band was one more
                // gap between Maps and the tiles (asked on the forum), and the dock's
                // width is set like the tiles' places.
                divider = {
                    if (editing) {
                        // Arranging can end in the middle of a drag, which then never ends: the width is kept all the same.
                        DisposableEffect(Unit) { onDispose { DashLayoutStore.saveDockFraction(context, dockFraction.floatValue) } }
                        DockDivider(
                            vertical = vertical,
                            onDrag = { d ->
                                val delta = d / splitLengthPx.coerceAtLeast(1)
                                val signed = if (dockSide == Alignment.Start) delta else -delta
                                dockFraction.floatValue = (dockFraction.floatValue + signed)
                                    .coerceIn(DashLayoutStore.MIN_DOCK_FRACTION, DashLayoutStore.MAX_DOCK_FRACTION)
                            },
                            onDragEnd = { DashLayoutStore.saveDockFraction(context, dockFraction.floatValue) }
                        )
                    }
                },
                pane = pagesPane
            )

            if (tabsShown) {
                CanvasTabRail(
                    m = settingsModel,
                    bar = barModel,
                    onAdd = { whenParked { showBarChooser = true } },
                    modifier = Modifier.align(if (railOnRight) Alignment.CenterEnd else Alignment.CenterStart)
                )
            } else if (railOn) {
                CanvasRail(
                    m = settingsModel,
                    onHome = { showHome() },
                    modifier = Modifier.align(if (railOnRight) Alignment.CenterEnd else Alignment.CenterStart)
                )
            }

            // Floating swap button (bottom-centre), shown whenever the launcher
            // shares the screen — regardless of how the split was started (our
            // accessibility path or the OS's manual recents gesture). Swapping
            // Always in sight while the dashboard shows made-up data, with the way
            // out. The standard bar carries it itself; the skins' bars don't, so
            // there it floats over the pages.
            if (demoOn && DashColors.Skin != DashSkin.STANDARD) {
                DemoBadge(
                    onStop = DemoMode::stop,
                    // Canvas has its trip strip up there; the corner under the car is free.
                    modifier = if (canvas) Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 24.dp)
                        else Modifier.align(Alignment.TopCenter).padding(top = 6.dp)
                )
            }
            if (lockNoticeAt > 0L) {
                DriveLockChip(modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp))
            }
            FadingPageIndicator(
                // Home alone has no cross to show.
                shown = pageIndicatorShown && !editing && (cross.row.size > 1 || cross.column.size > 1),
                page = pageIndicatorFor,
                cross = cross,
                wayHome = wayHomeShown,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
            )
            if (barAutoHide && !arranging && !railOn) BarHandle(barState, Modifier.align(Alignment.BottomCenter))

            // The tapped tile's panel, over the side of the pages that leaves
            // the tile in sight (TileOptions.kt).
            val selected = tileOptions
            if (arranging && selected != null) {
                val (page, index) = selected
                val tile = pages.getOrNull(page)?.getOrNull(index)
                if (tile == null) {
                    // The tile went away (undo, template): close, but not mid-composition.
                    LaunchedEffect(Unit) { tileOptions = null }
                } else key(page, index) {
                    val hasDesigns = (tile as? DashboardItem.BuiltinWidget)?.kind?.let { it != BuiltinKind.DASH_BAR } == true
                    val sideWidth = with(density) { sidePanelWidth(pagesArea.width.toDp(), pagesArea.height.toDp(), hasDesigns) }
                    val panelPx = if (vertical) min(pagesArea.height * 0.45f, with(density) { PANEL_SHEET_MAX.toPx() })
                        else with(density) { sideWidth.toPx() }
                    val driverOnRight = CarProfileStore.current.driverOnRight
                    // Where the tile is, once it has said so (the frame after it was tapped).
                    val bounds = selectedTileBounds?.takeIf { it.first == selected }?.second
                    val side = bounds?.let { b -> panelSide(b, pagesArea, panelPx, vertical, driverOnRight) }
                        ?: if (vertical) PanelSide.BOTTOM else if (driverOnRight) PanelSide.RIGHT else PanelSide.LEFT
                    val widget = tile as? DashboardItem.BuiltinWidget
                    val placing = when (side) {
                        PanelSide.LEFT -> Modifier.align(AbsoluteAlignment.TopLeft).width(sideWidth)
                        PanelSide.RIGHT -> Modifier.align(AbsoluteAlignment.TopRight).width(sideWidth)
                        PanelSide.TOP -> Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        PanelSide.BOTTOM -> Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    }.then(
                        when {
                            // A tile with no designs and no layer to set: the panel is as tall as its few controls.
                            widget == null && !tile.canFrame(insideAllowed) -> Modifier
                            vertical -> Modifier.height(with(density) { panelPx.toDp() })
                            else -> Modifier.fillMaxHeight()
                        }
                    ).padding(8.dp)
                    TilePanel(
                        item = tile,
                        title = when (tile) {
                            is DashboardItem.AppShortcut -> appsByPackage[tile.packageName]?.label
                            is DashboardItem.AppWindow -> appsByPackage[tile.packageName]?.label?.let {
                                stringResource(if (tile.runsInside(insideAllowed)) R.string.dash_app_inside else R.string.dash_app_window, it)
                            }
                            else -> null
                        } ?: tile.describe(),
                        side = side,
                        designs = widget?.takeIf { it.kind != BuiltinKind.DASH_BAR }?.let {
                            { shelfModifier ->
                                DesignShelf(
                                    kind = widget.kind,
                                    current = widget.design,
                                    // Grid cells on the head unit are a little wider than tall.
                                    aspect = (widget.w * 1.1f) / widget.h,
                                    env = SkinTileEnv(
                                        editing = false, appsByPackage = appsByPackage, media = media, mediaController = mediaController,
                                        hasMediaAccess = mediaAccess, context = context, obdConnectionState = obdConnection,
                                        onConnectObd = onConnectObd, onPickDevice = onPickDevice, onLaunchApp = onLaunchApp, onEditLaunchBar = {}
                                    ),
                                    standardPreview = {
                                        // Arranging mode: view-hosting tiles show their placeholder, not a second live map.
                                        TileContent(
                                            item = widget.copy(design = WidgetDesign.STANDARD), editing = true, appsByPackage = appsByPackage,
                                            media = media, mediaController = mediaController, hasMediaAccess = mediaAccess,
                                            context = context, obdConnection = obdConnection, onConnectObd = onConnectObd,
                                            onPickDevice = onPickDevice, onLaunchApp = onLaunchApp, onLaunchSplitPair = onLaunchSplitPair,
                                            onEditLaunchBar = {}, onModelTouch = {}
                                        )
                                    },
                                    onPick = { design -> if (design != widget.design) updateItem(page, index, widget.copy(design = design)) },
                                    modifier = shelfModifier
                                )
                            }
                        },
                        onZoom = { zoomTile(page, index, it) },
                        onFrame = { frameTile(page, index, it) },
                        onOverlap = { over -> setOverlap(page, index, over) },
                        onLayer = { toFront -> layerTile(page, index, toFront) },
                        onNudge = { dx, dy -> moveCell(page, index, tile.x + dx, tile.y + dy) },
                        onResize = { dw, dh -> resizeCell(page, index, tile.w + dw, tile.h + dh) },
                        barActions = if (widget?.kind == BuiltinKind.DASH_BAR) {
                            {
                                DashBarPanelActions(
                                    canAddDashboard = barModel.canAddDashboard,
                                    onAddDashboard = { tileOptions = null; dashSheet = -1 },
                                    onAddApp = { tileOptions = null; pickBarApp = true }
                                )
                            }
                        } else null,
                        onRemove = { tileOptions = null; removeAt(page, index) },
                        onClose = { tileOptions = null },
                        wide = !vertical && sideWidth > PANEL_WIDTH,
                        modifier = placing
                    )
                }
            }

            // needs the accessibility service; if it isn't on, tapping prompts to
            // enable it instead of silently doing nothing.
            // Maps docked beside the dashboard is not a split of two apps: a popup
            // (the ⋯ menu) taking focus there must not bring the swap button up.
            val mapsDocked by PipAnchor.dockActive.collectAsState()
            if (inSplitMode && layout == DashLayout.GRID && !mapsDocked) {
                FloatingActionButton(
                    onClick = {
                        if (SplitLauncher.isSystemSplitAvailable()) SplitLauncher.swapSplit()
                        else showSplitEnable = true
                    },
                    containerColor = DashColors.Accent,
                    contentColor = DashColors.OnAccent,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .size(56.dp)
                ) {
                    Icon(
                        Icons.Filled.SwapHoriz,
                        contentDescription = stringResource(R.string.dash_swap_split)
                    )
                }
            }

            settingsTab?.let { tab ->
                SettingsScreen(
                    m = settingsModel,
                    theme = themeState,
                    initialTab = tab,
                    onPickObd = onPickDevice,
                    onClose = { closeSettings() },
                    modifier = Modifier.fillMaxSize().padding(10.dp)
                )
            }

            if (showAddSheet) {
                AddSheet(
                    page = addTargetPage,
                    apps = apps,
                    previewTile = { item ->
                        // Arranging mode, as in the design picker: view-hosting tiles show their placeholder, not a second live map.
                        TileContent(
                            item = item, editing = true, appsByPackage = appsByPackage,
                            media = media, mediaController = mediaController, hasMediaAccess = mediaAccess,
                            context = context, obdConnection = obdConnection, onConnectObd = onConnectObd,
                            onPickDevice = onPickDevice, onLaunchApp = onLaunchApp, onLaunchSplitPair = onLaunchSplitPair,
                            onEditLaunchBar = {}, onModelTouch = {}
                        )
                    },
                    onPickBuiltin = { kind ->
                        showAddSheet = false
                        if (addTargetPage >= 0) addItem(addTargetPage, DashboardItem.BuiltinWidget(kind, w = kind.defaultW, h = kind.defaultH))
                    },
                    onPickLaunchBar = {
                        showAddSheet = false
                        if (addTargetPage >= 0) {
                            // Open the editor right away so the new bar isn't left empty.
                            val index = addItemAt(addTargetPage, DashboardItem.LaunchBar())
                            if (index >= 0) launchBarEditor = addTargetPage to index
                        }
                    },
                    onPickSystemWidget = {
                        showAddSheet = false
                        addSystemWidget.pickFromList()
                    },
                    onPickApp = { app ->
                        showAddSheet = false
                        if (addTargetPage >= 0) addItem(addTargetPage, DashboardItem.AppShortcut(app.packageName))
                    },
                    onPickWindow = { app, inside ->
                        showAddSheet = false
                        if (addTargetPage >= 0) addItem(addTargetPage, DashboardItem.AppWindow(app.packageName, inside = inside))
                    },
                    onPickPair = { first, second ->
                        showAddSheet = false
                        if (addTargetPage >= 0) addItem(addTargetPage, DashboardItem.SplitPair(first, second))
                    },
                    onClose = { showAddSheet = false },
                    modifier = Modifier.fillMaxSize().padding(10.dp)
                )
            }

            setupStep?.let { step ->
                SetupScreen(
                    initialStep = step,
                    theme = themeState,
                    onPickObd = onPickDevice,
                    // No adapter, or a phone paired: the pill looks again.
                    onAccessChanged = { accessGeneration++ },
                    pillOff = setupPillOff,
                    onPillOff = { SetupStore.setPillOff(context, true); setupPillOff = true },
                    onStep = { setupShownStep = it },
                    onClose = { closeSetup() },
                    modifier = Modifier.fillMaxSize().padding(10.dp)
                )
            }

            if (showAllApps) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DashColors.Background.copy(alpha = 0.72f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { showAllApps = false }
                ) {
                    AppDrawer(
                        apps = apps,
                        moving = moving,
                        onLaunch = { onLaunchApp(it.packageName) },
                        onClose = { showAllApps = false },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp)
                    )
                }
            }
        }

        // One bar at a time: while arranging, the edit bar stands where the
        // launcher bar does (a swipe along it still turns the page), and
        // Done brings the launcher bar back.
        if (arranging) {
            val pageTiles = pages.getOrNull(currentPage).orEmpty()
            launcherBar {
                EditBar(
                    page = currentPage,
                    canUndo = history.isNotEmpty(),
                    // The page's text size: what its tiles share, or what most of them have.
                    pageZoom = pageTiles.filter { it.canZoom() }.map { it.zoom }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 1f,
                    onAdd = { onAdd(currentPage) },
                    onUndo = { undo() },
                    onReset = { removeChoice = currentPage },
                    onTemplates = { showTemplates = true },
                    // Not the floating windows: a size of their own would move them inside their tiles.
                    onPageZoom = { zoom -> mutatePage(currentPage) { list -> list.map { if (it.canZoom()) it.withZoom(zoom) else it } } },
                    onDashboard = { barTabs.indexOfFirst { it.page == currentPage }.takeIf { it >= 0 }?.let { dashSheet = it } },
                    // A cross: the overview adds and removes them; over an app, the rail's sheet.
                    onNewDashboard = if (tabsShown && barModel.canAddDashboard) ({ dashSheet = -1 }) else null,
                    onDashboards = if (!tabsShown) ({ DashboardsOverviewHost.open = true }) else null,
                    onShare = { shareOpen = true },
                    onDone = { editing = false }
                )
            }
        } else if (!barAutoHide && !railOn) {
            launcherBar { TopBar(settingsModel) }
        }
    }
    // Auto-hide (Settings › Display): the bar floats over the pages, which keep
    // the whole height, so showing or hiding it never resizes the dashboard.
    if (barAutoHide && !arranging && !railOn) {
        Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            launcherBar {
                AutoHidingBar(state = barState, hideSeconds = barHideSeconds) {
                    TopBar(settingsModel)
                }
            }
        }
    }
    // Every dashboard of the cross at once, with + and - (DashboardsOverview.kt).
    if (DashboardsOverviewHost.open && !tabsShown) {
        val overviewKey = CanvasTabs.keyFor(variant())
        DashboardsOverview(
            tabs = barTabs,
            pages = pages,
            current = currentPage,
            onOpen = { page -> DashboardsOverviewHost.open = false; showPage(page) },
            onAdd = { page -> CanvasTabs.saveTabs(overviewKey, barTabs + CanvasTab(page)) },
            onRemove = { page -> removeCrossDashboard(page) },
            onUndoRemove = { tab, index, hadTiles ->
                if (hadTiles) undo()
                // The tiles bring their page back unnamed: it takes its name and place again.
                val now = CanvasTabs.tabsFor(overviewKey, CanvasTabs.lists.value, pages)
                CanvasTabs.saveTabs(overviewKey, CanvasTabs.placed(now, tab, index))
            },
            onDismiss = { DashboardsOverviewHost.open = false }
        )
    }
    // Templates as a showroom, and Moments (TemplateShowroom.kt, MomentsScreen.kt): over the whole dashboard.
    if (showTemplates || fillPage != null) {
        val only = fillPage
        TemplateShowroom(
            screen = templateScreen(layout != DashLayout.GRID),
            pageSize = previewPageSize,
            onlyPage = only,
            tile = previewTile,
            onApply = { choice ->
                showTemplates = false
                fillPage = null
                if (applyTemplate(choice)) {
                    templateUndo = context.getString(R.string.templates_applied, context.getString(choice.template.titleRes))
                    // One empty page's own fill stays on screen.
                    if (only == null) showHome()
                }
            },
            onMoments = if (only == null) ({ showTemplates = false; showMoments = true }) else null,
            onDismiss = { showTemplates = false; fillPage = null }
        )
    }
    // A code the phone sent: the share sheet opens on it, once the car stands.
    val shareMoving = LocalDriveLock.current.moving
    LaunchedEffect(incomingShare, shareMoving) {
        if (incomingShare != null && !shareMoving) shareOpen = true
    }
    if (shareOpen) {
        val page = currentPage
        val shareName = pageName(page)
        DashShareSheet(
            items = pages.getOrNull(page).orEmpty(),
            name = shareName,
            installed = appsByPackage.keys,
            pageSize = previewPageSize,
            tile = previewTile,
            incoming = incomingShare,
            onImport = { items, look ->
                // As a template does: the Android widgets of the page it replaces are let go.
                pages.getOrNull(page).orEmpty().filterIsInstance<DashboardItem.SystemWidget>()
                    .forEach { WidgetHostHolder.retire(context, it.appWidgetId) }
                mutatePage(page) { items }
                look?.let { l ->
                    themeState.onMode(l.mode)
                    themeState.onAppearance(l.appearance)
                    themeState.onEffects(l.effects)
                    l.rail?.let { themeState.onRail(it) }
                    themeState.onTabs(l.tabs)
                    BarItems.set(l.barItems)
                    BarItems.setLook(l.barLook)
                }
                shareOpen = false
                DashShare.taken()
                templateUndo = context.getString(R.string.templates_applied, shareName)
            },
            onDismiss = { shareOpen = false; DashShare.taken() }
        )
    }
    if (showMoments) {
        val screenNow = templateScreen(layout != DashLayout.GRID)
        MomentsScreen(
            settings = momentSettings,
            now = shownMoment,
            itemsFor = { MomentStore.itemsFor(momentSettings, it, variant(), screenNow) },
            custom = { MomentStore.layoutKey(it, variant()) in momentSettings.layouts },
            pageSize = previewPageSize,
            tile = previewTile,
            dashboards = barTabs.filter { pages.getOrNull(it.page).orEmpty().isNotEmpty() }.map { it.page to it.label(context) },
            appsNote = homeHoldsApps,
            onMaster = { MomentStore.setMaster(it) },
            onToggle = { m, on -> MomentStore.setOn(m, on) },
            onCopy = { m, page ->
                MomentStore.setLayout(m, variant(), page?.let { MomentStore.copyable(pages.getOrNull(it).orEmpty()) })
            },
            onDismiss = { showMoments = false }
        )
    }
    // A template just put on: Undo, for a while.
    templateUndo?.let { name ->
        UndoBar(
            text = name,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 96.dp)
        ) {
            templateUndo = null
            undo()
        }
    }
    tourStep?.let { step ->
        TourOverlay(
            step = step,
            obd = obdConnection.value,
            onStep = { tourStep = it },
            onClose = { finished -> closeTour(declined = !finished && step == TourStep.WELCOME) },
            afterSetup = tourAfterSetup
        )
    }
    pageFull?.let { page ->
        PageFullDialog(
            // The nearest of the driver's dashboards that still has a free cell.
            other = nearestPageWithRoom(pages, page, barTabs.map { it.page }, tabbed = tabsShown),
            onAddTo = { other ->
                pageFull = null
                showPage(other)
                onAdd(other)
            },
            onDismiss = { pageFull = null }
        )
    }
    // A screen direction on trial, asked here while the Display settings are not on screen.
    KeepDirectionStrip(
        Modifier.align(Alignment.TopCenter).padding(top = with(density) { barOverlapPx.toDp() } + 8.dp, start = 12.dp, end = 12.dp)
    )
    }
    }

    launchBarEditor?.let { (page, index) ->
        val bar = pages.getOrNull(page)?.getOrNull(index) as? DashboardItem.LaunchBar
        if (bar == null) {
            LaunchedEffect(Unit) { launchBarEditor = null }
        } else {
            LaunchBarEditorDialog(
                apps = apps,
                appsByPackage = appsByPackage,
                packages = bar.packages,
                onSave = { pkgs ->
                    updateItem(page, index, bar.copy(packages = pkgs))
                    launchBarEditor = null
                },
                onDismiss = { launchBarEditor = null }
            )
        }
    }

    removeChoice?.let { page ->
        RemoveDashboardDialog(
            name = pageName(page),
            // Home is never taken out of the swipes: it can only be emptied.
            canTakeOut = page != DashboardStore.CENTER,
            onEmpty = { removeChoice = null; emptyDashboard(page) },
            onTakeOut = { removeChoice = null; removeDashboard(page) },
            onDismiss = { removeChoice = null }
        )
    }
    // --- The dashboard bar's sheets (CanvasTabs.kt) ---
    val barKey = CanvasTabs.keyFor(variant())
    if (showBarChooser) {
        DashBarAddChooser(
            canAddDashboard = barModel.canAddDashboard,
            onDashboard = { showBarChooser = false; dashSheet = -1 },
            onApp = { showBarChooser = false; pickBarApp = true },
            onDismiss = { showBarChooser = false }
        )
    }
    dashSheet?.let { index ->
        val tab = barTabs.getOrNull(index)
        val newPage = CanvasTabs.freePage(barTabs, pages)
        if (tab == null && newPage == null) {
            LaunchedEffect(Unit) { dashSheet = null }
            return@let
        }
        DashboardSheet(
            tab = tab,
            newPage = tab?.page ?: newPage ?: 0,
            tabs = barTabs,
            tabbed = tabsShown,
            pagesWithTiles = pages.indices.filterTo(HashSet()) { pages[it].isNotEmpty() },
            // Removing is the edit bar's (Remove dashboard: empty it, or take it out of the swipes).
            canRemove = false,
            onSave = { edited, place ->
                if (tabsShown) {
                    // Over an app: its place in the rail.
                    CanvasTabs.saveTabs(barKey, CanvasTabs.placed(barTabs, edited, place))
                    if (tab == null) tabPage = edited.page
                } else {
                    // A cross: its spot. Moving to a taken one trades the two pages.
                    var list = barTabs
                    if (tab != null && place != tab.page) {
                        tileOptions = null
                        // One Undo step: the pages, and the names and places with them.
                        history = (history + Snapshot(pages, barTabs)).takeLast(MAX_UNDO)
                        val a = tab.page
                        pages = pages.mapIndexed { i, l -> when (i) { a -> pages[place]; place -> pages[a]; else -> l } }
                        DashboardStore.save(context, pages, variant())
                        list = CanvasTabs.swapped(list, a, place)
                    }
                    CanvasTabs.saveTabs(barKey, CanvasTabs.placed(list, edited.copy(page = place), if (tab != null) index else list.size))
                    showPage(place)
                }
                dashSheet = null
            },
            onRemove = { dashSheet = null },
            onDismiss = { dashSheet = null }
        )
    }
    appSheet?.let { index ->
        val app = barApps.getOrNull(index)?.let { appsByPackage[it] }
        if (app == null) {
            LaunchedEffect(Unit) { appSheet = null }
            return@let
        }
        DashBarAppSheet(
            app = app,
            otherApps = barApps.filterIndexed { i, _ -> i != index }.map { appsByPackage[it]?.label ?: it },
            place = index,
            onSave = { at -> CanvasTabs.saveApps(CanvasTabs.moved(barApps, index, at)); appSheet = null },
            onRemove = { CanvasTabs.saveApps(barApps.filterIndexed { i, _ -> i != index }); appSheet = null },
            onDismiss = { appSheet = null }
        )
    }
    if (pickBarApp) {
        AppPickerDialog(
            apps = apps.filter { it.packageName !in barApps },
            onPick = { app -> CanvasTabs.saveApps(barApps + app.packageName); pickBarApp = false },
            onDismiss = { pickBarApp = false }
        )
    }

    if (barSettings) BarSettingsSheet(themeState) { barSettings = false }
    releaseNotes?.let { info ->
        ParkedOnly { releaseNotes = null }
        ReleaseNotesDialog(
            info = info,
            onUpdate = { releaseNotes = null; installUpdate() },
            onDismiss = { releaseNotes = null }
        )
    }

    updatePrompt?.let { ready ->
        ParkedOnly { updatePrompt = null }
        AlertDialog(
            modifier = Modifier.keepClearOfWindows(),
            onDismissRequest = { updatePrompt = null },
            containerColor = DashColors.Card,
            title = { Text(stringResource(R.string.dash_update_ready_title), color = DashColors.TextPrimary) },
            text = { Text(stringResource(R.string.dash_update_ready_body, ready.info.versionName), color = DashColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = { updatePrompt = null; updateManager.install(ready.file) }) {
                    Text(stringResource(R.string.dash_update_now), color = DashColors.AccentInk)
                }
            },
            dismissButton = {
                TextButton(onClick = { updatePrompt = null; updateManager.dismiss() }) {
                    Text(stringResource(R.string.dash_update_not_this_version), color = DashColors.Muted)
                }
            }
        )
    }

    // Tiles that need root or the unit's ADB, where Dashwheel has neither: said, then removed.
    RootlessTiles(pages, shellAccess) { kept ->
        mutateAll(kept)
        releaseMapsAnchorIfGone()
    }

    StopGuidancePrompt()
    // The last start ended in a crash: offered once, parked, as an issue to send.
    CrashOffer()
    layoutNotice?.let { notice ->
        AlertDialog(
            modifier = Modifier.keepClearOfWindows(),
            onDismissRequest = { layoutNotice = null },
            containerColor = DashColors.Card,
            title = { Text(stringResource(R.string.dash_notice_title), color = DashColors.TextPrimary) },
            text = { Text(notice, color = DashColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = { layoutNotice = null }) {
                    Text(stringResource(R.string.dash_got_it), color = DashColors.AccentInk)
                }
            }
        )
    }


    if (showDevicePicker) {
        DevicePickerDialog(
            devices = pairedDevices,
            usbAdapters = usbAdapters,
            onPick = { mac ->
                ObdBluetoothManager.saveDeviceAddress(mac)
                showDevicePicker = false
                scope.launch {
                    // A link already up (a cable) is closed for the one picked.
                    ObdBluetoothManager.disconnect()
                    ObdBluetoothManager.connect(mac, byDriver = true)
                }
                // Dashboards built without an adapter have no car tile: offered now that one is here.
                if (pages.flatten().none { it is DashboardItem.BuiltinWidget && TemplatePlacer.needsObd(it.kind) }) offerCarTiles = true
            },
            onPickUsb = { key ->
                ObdBluetoothManager.saveUsbAdapter(key)
                showDevicePicker = false
                scope.launch {
                    ObdBluetoothManager.disconnect()
                    // Android asks to let Dashwheel use it the first time.
                    ObdBluetoothManager.connect("", byDriver = true)
                }
                if (pages.flatten().none { it is DashboardItem.BuiltinWidget && TemplatePlacer.needsObd(it.kind) }) offerCarTiles = true
            },
            onDismiss = { showDevicePicker = false },
            onOpenSettings = {
                showDevicePicker = false
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        )
    }

    if (offerCarTiles) {
        AlertDialog(
            modifier = Modifier.keepClearOfWindows(),
            onDismissRequest = { offerCarTiles = false },
            containerColor = DashColors.Card,
            title = { Text(stringResource(R.string.setup_car_tiles_title), color = DashColors.TextPrimary) },
            text = { Text(stringResource(R.string.setup_car_tiles_body), color = DashColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    offerCarTiles = false
                    // The Daily template's car tiles, into the pages as they are (one Undo step).
                    mutateAll(TemplatePlacer.addCarTiles(pages, DashTemplate.DAILY, templateScreen(layout != DashLayout.GRID).copy(obdPaired = true)))
                }) { Text(stringResource(R.string.dash_add), color = DashColors.AccentInk) }
            },
            dismissButton = {
                TextButton(onClick = { offerCarTiles = false }) { Text(stringResource(R.string.tour_not_now), color = DashColors.Muted) }
            }
        )
    }

    if (showSplitPicker) {
        AppPickerDialog(
            apps = apps,
            title = stringResource(R.string.dash_split_with),
            onPick = { app ->
                showSplitPicker = false
                SplitLauncher.launchSplit(context, app.packageName)
            },
            onDismiss = { showSplitPicker = false }
        )
    }

    if (showSplitEnable) {
        AlertDialog(
            modifier = Modifier.keepClearOfWindows(),
            onDismissRequest = { showSplitEnable = false },
            containerColor = DashColors.Card,
            title = { Text(stringResource(R.string.dash_enable_split_title), color = DashColors.TextPrimary) },
            text = {
                Text(
                    stringResource(R.string.dash_enable_split_body),
                    color = DashColors.Muted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSplitEnable = false
                    SplitLauncher.openAccessibilitySettings(context)
                }) {
                    Text(stringResource(R.string.dash_open_settings), color = DashColors.TextPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSplitEnable = false }) {
                    Text(stringResource(R.string.dash_cancel), color = DashColors.Muted)
                }
            }
        )
    }

    if (showObd) ObdDialog(obdConnection.value, onConnectObd, onPickDevice) { showObd = false }

    // The Phone screen (PhoneScreen.kt): opened from any look's phone key and the Quick dial, shown here.
    val showPhone by PhoneScreen.shown.collectAsState()
    DisposableEffect(Unit) {
        PhoneScreen.hosted(true)
        onDispose { PhoneScreen.hosted(false) }
    }
    if (showPhone) PhoneDialog { PhoneScreen.close() }

    if (showSystemDialog) {
        AlertDialog(
            modifier = Modifier.keepClearOfWindows(),
            onDismissRequest = { if (!systemBusy) showSystemDialog = false },
            containerColor = DashColors.Card,
            title = { Text(stringResource(R.string.dash_system_app_title), color = DashColors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.dash_system_app_body, AdbInstaller.DEFAULT_PORT),
                        color = DashColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = when {
                            !rootChecked -> stringResource(R.string.dash_root_checking)
                            rootAvailable -> stringResource(R.string.dash_root_available)
                            else -> stringResource(R.string.dash_root_missing, AdbInstaller.DEFAULT_PORT)
                        },
                        color = if (rootAvailable) DashColors.Good else DashColors.Muted,
                        style = MaterialTheme.typography.labelLarge
                    )
                    systemMessage?.let {
                        Text(
                            it,
                            color = if (systemInstalled) DashColors.Good else DashColors.Warning,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (systemBusy) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = DashColors.Accent
                        )
                    }
                }
            },
            confirmButton = {
                if (systemInstalled) {
                    TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { PrivApp.rebootDevice(context) } } }) {
                        Text(stringResource(R.string.dash_reboot_now), color = DashColors.AccentInk)
                    }
                } else {
                    TextButton(
                        enabled = !systemBusy,
                        onClick = {
                            systemBusy = true
                            systemMessage = null
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    PrivApp.install(context)
                                }
                                systemBusy = false
                                res.onSuccess {
                                    systemInstalled = true
                                    systemMessage = context.getString(R.string.dash_system_installed)
                                }.onFailure {
                                    systemMessage = context.getString(R.string.dash_system_failed, it.message.orEmpty())
                                }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.dash_install_system_app), color = DashColors.AccentInk)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!systemBusy) showSystemDialog = false }) {
                    Text(stringResource(R.string.dash_close), color = DashColors.Muted)
                }
            }
        )
    }

    // The phone's call, when the overlay window can't show it over other apps.
    PhoneCallHost()
    MessageAlertHost()
    DoorAlertHost()
    RadarHost()
    ClimateHost()
    VolumeHost()
    TyreAlertHost()
    BeltAlertHost()
    RoadAlertHost()
}

/**
 * The page cross after a page change, fading in fast and out slowly. The fade
 * is read here, in a scope of its own: read where the dashboard is laid out,
 * every frame of it recomposed the whole dashboard, its bar and its background
 * for half a second after each swipe. [wayHome]: a sideways swipe off the row,
 * so Home and the swipe back to it are shown.
 */
@Composable
private fun FadingPageIndicator(shown: Boolean, page: Int, cross: ShownCross, wayHome: Boolean, modifier: Modifier = Modifier) {
    val fade by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(if (shown) 150 else 400),
        label = "pageIndicator"
    )
    if (fade > 0f) {
        // A docked app window is drawn above the dashboard: the indicator moves aside
        // into the stretch the windows leave clear rather than show under one.
        val view = LocalView.current
        val windows by PipAnchor.dockedAreas.collectAsState(emptyList())
        var at by remember { mutableStateOf<ScreenRect?>(null) }
        val dx = at?.let { r ->
            val origin = IntArray(2).also { view.getLocationOnScreen(it) }
            val area = ScreenRect(origin[0], origin[1], origin[0] + view.width, origin[1] + view.height)
            WindowListing.clearCentreX(r, area, windows)?.minus((r.left + r.right) / 2)
        } ?: 0
        PageIndicator(
            page, cross,
            modifier = modifier
                .onGloballyPositioned { coords ->
                    val b = coords.boundsInRoot()
                    val origin = IntArray(2).also { view.getLocationOnScreen(it) }
                    val r = ScreenRect(
                        (b.left + origin[0]).roundToInt(), (b.top + origin[1]).roundToInt(),
                        (b.right + origin[0]).roundToInt(), (b.bottom + origin[1]).roundToInt()
                    )
                    if (r != at) at = r
                }
                .offset { IntOffset(dx, 0) }
                .graphicsLayer { alpha = fade },
            wayHome = wayHome
        )
    }
}

/** One Undo step while arranging: the pages, and the dashboards' names and places as they were. */
private class Snapshot(val pages: List<List<DashboardItem>>, val tabs: List<CanvasTab>)

/**
 * "Remove dashboard": empty it (the tiles go, it stays in the swipes), or
 * take it out of the swipes with its tiles ([canTakeOut]: not Home). Undo
 * brings either back.
 */
@Composable
private fun RemoveDashboardDialog(name: String, canTakeOut: Boolean, onEmpty: () -> Unit, onTakeOut: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.keepClearOfWindows(),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { Text(stringResource(R.string.dash_remove_dashboard_title, name), color = DashColors.TextPrimary) },
        text = { Text(stringResource(R.string.dash_remove_dashboard_body), color = DashColors.TextSecondary) },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEmpty) { Text(stringResource(R.string.dash_remove_empty), color = DashColors.Critical) }
                if (canTakeOut) {
                    TextButton(onClick = onTakeOut) { Text(stringResource(R.string.dash_remove_take_out), color = DashColors.Critical) }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_cancel), color = DashColors.Muted) }
        }
    )
}

/**
 * The Maps dock, its divider and the pages side by side, or above one another
 * on an upright screen ([vertical]); without [docked], the pages alone. A
 * divider that draws nothing takes no room. The
 * dock's share is read while measuring, so dragging the divider re-measures
 * the panes without recomposing the dashboard. The three keep their places in
 * composition whichever side the dock is on ([dockFirst]): moving it never
 * rebuilds the dock, which would close the Maps window.
 */
@Composable
private fun DockSplit(
    vertical: Boolean,
    docked: Boolean,
    dockFirst: Boolean,
    dockFraction: () -> Float,
    modifier: Modifier,
    dock: @Composable () -> Unit,
    divider: @Composable () -> Unit,
    pane: @Composable () -> Unit
) {
    Layout(
        content = {
            if (docked) {
                Box { dock() }
                Box { divider() }
            }
            Box { pane() }
        },
        modifier = modifier
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val length = if (vertical) h else w
        fun exact(main: Int) = if (vertical) Constraints.fixed(w, main) else Constraints.fixed(main, h)
        if (measurables.size < 3) {
            val alone = measurables.last().measure(exact(length))
            return@Layout layout(w, h) { alone.place(0, 0) }
        }
        val (dockM, dividerM, paneM) = measurables
        val bar = dividerM.measure(
            if (vertical) Constraints(minWidth = w, maxWidth = w, maxHeight = length)
            else Constraints(maxWidth = length, minHeight = h, maxHeight = h)
        )
        val rest = (length - if (vertical) bar.height else bar.width).coerceAtLeast(0)
        val dockLength = (rest * dockFraction()).roundToInt().coerceIn(0, rest)
        val dockP = dockM.measure(exact(dockLength))
        val paneP = paneM.measure(exact(rest - dockLength))
        layout(w, h) {
            var at = 0
            for (part in if (dockFirst) listOf(dockP, bar, paneP) else listOf(paneP, bar, dockP)) {
                if (vertical) part.place(0, at) else part.place(at, 0)
                at += if (vertical) part.height else part.width
            }
        }
    }
}

/** A two-button question before something a tap could regret; where Undo still exists, the body says so. */
@Composable
internal fun ConfirmDialog(title: String, body: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.keepClearOfWindows(),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { Text(title, color = DashColors.TextPrimary) },
        text = { Text(body, color = DashColors.TextSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(action, color = DashColors.Critical) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_cancel), color = DashColors.Muted) }
        }
    )
}

private fun DashboardItem.isClock(): Boolean = this is DashboardItem.BuiltinWidget && kind == BuiltinKind.CLOCK

/** A tile the app inside takes every touch of: Maps inside, or an app window. */
private fun DashboardItem.runsAnApp(): Boolean =
    this is DashboardItem.AppWindow || (this is DashboardItem.BuiltinWidget && kind == BuiltinKind.MAPS_INSIDE)

/**
 * Thin strips along the pages' edges that turn the page when swiped across:
 * [sideways] with true for the next page to the right, [upDown] with true for
 * the page underneath; each null where there is no page that way.
 */
@Composable
private fun BoxScope.PageEdgeSwipes(sideways: ((Boolean) -> Unit)?, upDown: ((Boolean) -> Unit)?) {
    val edge = 20.dp
    @Composable
    fun Strip(align: Alignment, horizontal: Boolean, turn: (Boolean) -> Unit) {
        // The gesture below is set up once and lives on: it has to call the
        // turn of now, not the one from when the strip first appeared (which
        // could say "not on the home row" for good, and the swipe did nothing).
        val turnNow by rememberUpdatedState(turn)
        Box(
            Modifier.align(align)
                .then(if (horizontal) Modifier.width(edge).fillMaxHeight() else Modifier.height(edge).fillMaxWidth())
                .pointerInput(horizontal) {
                    var dragged = 0f
                    val threshold = 48.dp.toPx()
                    val end = { if (abs(dragged) >= threshold) turnNow(dragged < 0f) }
                    if (horizontal) {
                        detectHorizontalDragGestures(onDragStart = { dragged = 0f }, onDragEnd = end) { change, d ->
                            dragged += d
                            change.consume()
                        }
                    } else {
                        detectVerticalDragGestures(onDragStart = { dragged = 0f }, onDragEnd = end) { change, d ->
                            dragged += d
                            change.consume()
                        }
                    }
                }
        )
    }
    if (sideways != null) {
        Strip(Alignment.CenterStart, horizontal = true, turn = sideways)
        Strip(Alignment.CenterEnd, horizontal = true, turn = sideways)
    }
    if (upDown != null) {
        Strip(Alignment.TopCenter, horizontal = false, turn = upDown)
        Strip(Alignment.BottomCenter, horizontal = false, turn = upDown)
    }
}

/**
 * Where the pagers start when they are made again: the page last [shown], or
 * the one [wanted] before its dashboard was in the cross (-1 for none). Plain
 * fields, written after each composition: reading them draws nothing.
 */
private class PageAnchor {
    var shown = DashboardStore.CENTER
    var wanted = -1
}

private const val UPDATE_CHECK_EVERY_MS = 6 * 60 * 60_000L
/** After a swipe during the tour, the new page shows for this long before the next tip. */
private const val TOUR_SWIPE_SETTLE_MS = 700L
