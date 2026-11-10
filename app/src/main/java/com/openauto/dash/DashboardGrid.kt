@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openauto.dash

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.material3.TextButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Map
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Density
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.draw.drawWithContent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import kotlin.math.ceil
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.vector.ImageVector

/*
 * The free-placement tile grid: page layout, drag / resize tiles, tile content routing.
 */

/** Natural height of a full widget tile stacked in the scrollable split-screen column. */
internal val SPLIT_WIDGET_HEIGHT = 300.dp

/** A split pane: no more than about square. A whole landscape screen is far wider than tall. */
internal fun isNarrowPane(width: Int, height: Int): Boolean = width <= 0 || height <= 0 || width < height * 1.3f

internal data class GridPreview(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val isValid: Boolean,
    /** A span being dragged out by the corner, which the ghost labels with its size. */
    val resizing: Boolean = false
)

/** True on the one tile of a page that carries the OBD connect button (the rest only show the state). */
internal val LocalObdPrompt = compositionLocalOf { true }

/** The tiles that used to each offer a connect button; the first one on a page keeps it. */
private val OBD_PROMPT_KINDS = setOf(BuiltinKind.TELEMETRY, BuiltinKind.OBD_ALL, BuiltinKind.OBD_DTC, BuiltinKind.RANGE)

@Composable
internal fun DashboardPage(
    pageItems: List<DashboardItem>,
    editing: Boolean,
    inSplitMode: Boolean,
    onModelTouch: (Boolean) -> Unit,
    appsByPackage: Map<String, AppEntry>,
    /** Live readings, passed as States so only the tiles that show them recompose. */
    media: State<MediaState>,
    mediaController: CarMediaController,
    hasMediaAccess: Boolean,
    obdConnection: State<ObdConnectionState>,
    onConnectObd: () -> Unit,
    onPickDevice: () -> Unit,
    onLaunchApp: (String) -> Unit,
    onLaunchSplitPair: (String, String) -> Unit,
    onEditLaunchBar: (Int) -> Unit,
    onMoveCell: (Int, Int, Int) -> Unit,
    onResizeCell: (Int, Int, Int) -> Unit,
    canPlace: (Int, Int, Int, Int, Int) -> Boolean,
    canMove: (Int, Int, Int) -> Boolean,
    onAdd: () -> Unit,
    /** Opens the tile's panel (design, text size, move, remove) for the tile at this index: a tap while arranging. */
    onTileOptions: (Int) -> Unit = {},
    /** The tile whose panel is open, outlined while arranging; -1 for none. */
    selectedIndex: Int = -1,
    /** Where that tile is, in root coordinates, so its panel can stand clear of it. */
    onSelectedBounds: (Rect) -> Unit = {},
    /** A tap on the page between tiles while arranging: closes the panel. */
    onTapEmpty: () -> Unit = {},
    /** Opens the template chooser for every dashboard (offered by an empty page). */
    onTemplates: () -> Unit = {},
    /** Fills this one page from a template (an empty page's first offer). */
    onFillPage: () -> Unit = {},
    /** An empty page offers to fill it; not a tab over an app, whose empty page is the map alone. */
    emptyPrompt: Boolean = true,
    /** A long press on the page while not arranging (between tiles, or on a tile's body): the second door into arranging. Null: none. */
    onHoldPage: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    // One connect action per page: the bar's OBD pill always connects, and
    // only the page's first vehicle tile repeats the offer; the others just
    // say the link is off (LocalObdPrompt).
    val promptIndex = remember(pageItems) {
        pageItems.indexOfFirst { it is DashboardItem.BuiltinWidget && it.kind in OBD_PROMPT_KINDS }
    }
    // Renders one tile's inner content with all the shared dependencies wired in.
    val tileContent: @Composable (Int, DashboardItem, ((Int, Int) -> Unit)?) -> Unit = { index, item, fit ->
        CompositionLocalProvider(LocalObdPrompt provides (index == promptIndex)) {
        TileZoom(item.zoom) {
            TileContent(
                item = item,
                editing = editing && !inSplitMode,
                appsByPackage = appsByPackage,
                media = media,
                mediaController = mediaController,
                hasMediaAccess = hasMediaAccess,
                context = context,
                obdConnection = obdConnection,
                onConnectObd = onConnectObd,
                onPickDevice = onPickDevice,
                onLaunchApp = onLaunchApp,
                onLaunchSplitPair = onLaunchSplitPair,
                onEditLaunchBar = { onEditLaunchBar(index) },
                onModelTouch = onModelTouch,
                onFitToWindow = fit
            )
        }
        }
    }

    if (inSplitMode) {
        // Sharing the screen: the pane is narrow and tall, so ignore the grid and
        // stack tiles vertically at a natural height, scrolling when they overflow.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val keys = remember(pageItems) { tileKeys(pageItems) }
            pageItems.forEachIndexed { index, item ->
                key(keys[index]) {
                    val h = when {
                        item.isCompactTile() -> 96.dp
                        item is DashboardItem.LaunchBar -> 88.dp
                        else -> SPLIT_WIDGET_HEIGHT
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(h)
                            .tileFrame(item.frame)
                            .clip(DashShape.Large)
                            .background(DashColors.Bar)
                    ) { tileContent(index, item, null) }
                }
            }
        }
        return
    }

    // Free placement on a GRID_COLS x GRID_ROWS grid: each tile sits at its own
    // cell rectangle and can be dragged to any cell and resized by its handle.
    val haptics = LocalHapticFeedback.current
    val holdPage by rememberUpdatedState(onHoldPage)
    val itemsNow by rememberUpdatedState(pageItems)
    val hostView = LocalView.current
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().padding(6.dp).onGloballyPositioned { coords ->
            // The page in sight, for the Blueprint start-up animation: a page beside it is clipped away.
            if (!StartSplash.collecting || coords.boundsInWindow().width < coords.size.width * 0.9f) return@onGloballyPositioned
            val origin = IntArray(2).also { hostView.getLocationOnScreen(it) }
            val at = coords.positionInWindow()
            StartSplash.grid(origin[0] + at.x, origin[1] + at.y, coords.size.width / GRID_COLS.toFloat(), coords.size.height / GRID_ROWS.toFloat(), itemsNow)
        }
    ) {
        val cellW = maxWidth / GRID_COLS
        val cellH = maxHeight / GRID_ROWS
        val cellWpx = with(density) { cellW.toPx() }
        val cellHpx = with(density) { cellH.toPx() }
        // Held, the page starts arranging (the menu's Edit dashboards is the other door).
        if (!editing && onHoldPage != null) {
            Box(
                Modifier.fillMaxSize().pointerInput(cellWpx, cellHpx) {
                    detectPageHold({ itemsNow }, cellWpx, cellHpx) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        holdPage?.invoke()
                    }
                }
            )
        }

        // While a tile is dragged / resized, [preview] holds the cell rectangle
        // (x, y, w, h) it will snap to, drawn as a highlighted ghost.
        var preview by remember { mutableStateOf<GridPreview?>(null) }
        // Safety net: never leave the ghost stranded once a move/resize commits
        // (pageItems changes) or edit mode is toggled.
        LaunchedEffect(pageItems, editing) { preview = null }

        if (editing) {
            // Grid guide-lines while arranging. Under the tiles, so a tap here
            // is one between them.
            // The coarse cells' lines as before; the finer cells between them faint, so the page stays readable.
            Canvas(modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onTapEmpty() } }) {
                val line = DashColors.TextSecondary.copy(alpha = 0.28f)
                val fine = DashColors.TextSecondary.copy(alpha = 0.1f)
                for (c in 0..GRID_COLS) {
                    val x = (cellWpx * c).coerceAtMost(size.width - 0.5f)
                    val coarse = c % GRID_SCALE == 0
                    drawLine(if (coarse) line else fine, Offset(x, 0f), Offset(x, size.height), if (coarse) 2f else 1f)
                }
                for (r in 0..GRID_ROWS) {
                    val y = (cellHpx * r).coerceAtMost(size.height - 0.5f)
                    val coarse = r % GRID_SCALE == 0
                    drawLine(if (coarse) line else fine, Offset(0f, y), Offset(size.width, y), if (coarse) 2f else 1f)
                }
            }

            // Snap-target ghost, above the resting tiles but below the dragged one.
            preview?.let { p ->
                val previewColor = if (p.isValid) DashColors.Accent else DashColors.Critical
                Box(
                    modifier = Modifier
                        .zIndex(0.5f)
                        .offset(cellW * p.x, cellH * p.y)
                        .size(cellW * p.w, cellH * p.h)
                        .padding(3.dp)
                        .clip(DashShape.Medium)
                        .background(previewColor.copy(alpha = 0.22f))
                        .border(2.dp, previewColor, DashShape.Medium)
                ) {
                    if (p.resizing) {
                        // The span the corner will leave, in cells.
                        Text(
                            "${p.w}×${p.h}",
                            color = DashColors.OnAccent.takeIf { p.isValid } ?: Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(10.dp)
                                .clip(DashShape.Pill)
                                .background(previewColor)
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        val keys = remember(pageItems) { tileKeys(pageItems) }
        pageItems.forEachIndexed { index, item ->
            // Keyed by what the tile *is*, not its list position, so removing or
            // reordering another tile never re-creates this one (which would
            // rebuild a hosted map / widget view) or leaves it with stale state.
            key(keys[index]) {
                GridTile(
                    index = index,
                    item = item,
                    cellW = cellW,
                    cellH = cellH,
                    cellWpx = cellWpx,
                    cellHpx = cellHpx,
                    editing = editing,
                    onModelTouch = onModelTouch,
                    onMoveCell = onMoveCell,
                    onResizeCell = onResizeCell,
                    canPlace = canPlace,
                    canMove = canMove,
                    selected = editing && index == selectedIndex,
                    onSelectedBounds = onSelectedBounds,
                    onTileOptions = onTileOptions,
                    onPreview = { x, y, w, h, isValid, resizing -> preview = GridPreview(x, y, w, h, isValid, resizing) },
                    onPreviewClear = { preview = null },
                    content = {
                        val cellWpxF = with(density) { cellW.toPx() }
                        val cellHpxF = with(density) { cellH.toPx() }
                        // The tile's inner padding eats a few dp of every span.
                        val padPx = with(density) { 12.dp.toPx() }
                        tileContent(index, item) { wPx, hPx ->
                            val cw = ceil((wPx + padPx) / cellWpxF).toInt().coerceIn(item.w, GRID_COLS - item.x)
                            val ch = ceil((hPx + padPx) / cellHpxF).toInt().coerceIn(item.h, GRID_ROWS - item.y)
                            // Grow silently, and only when nothing is in the way: a
                            // notice here would loop, since dismissing it re-measures.
                            if ((cw > item.w || ch > item.h) && canPlace(index, item.x, item.y, cw, ch)) onResizeCell(index, cw, ch)
                        }
                    }
                )
            }
        }

        // An empty page says so and offers both ways to fill it. Only there:
        // while arranging, the edit bar has the same two, so nothing ever
        // sits on top of a tile's corner.
        if (pageItems.isEmpty() && emptyPrompt) {
            EmptyPage(onFill = onFillPage, onAdd = onAdd, onTemplates = onTemplates, modifier = Modifier.align(Alignment.Center))
        }
    }
}

/**
 * A long press on the page while not arranging: between tiles, or on a
 * tile's own body, never on one whose app takes its touches (a map, an app
 * in a window or inside its tile, a system widget). Watched in the initial
 * pass, before the tiles' own buttons see the touch; a finger that moves or
 * lifts first, or a touch a tile consumes, is left alone. Once the hold
 * fires, the rest of the touch is swallowed so no button under the finger
 * goes off on release.
 */
private suspend fun PointerInputScope.detectPageHold(items: () -> List<DashboardItem>, cellW: Float, cellH: Float, onHold: () -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val col = (down.position.x / cellW).toInt()
        val row = (down.position.y / cellH).toInt()
        // The tile on top: the last of those over the others, else the grid's.
        val hits = items().filter { col in it.x until it.x + it.w && row in it.y until it.y + it.h }
        val under = hits.lastOrNull { !it.inGrid() } ?: hits.firstOrNull()
        if (under != null && under.takesEveryTouch()) return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var over = false
            while (!over) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                over = change == null || !change.pressed || change.isConsumed || event.changes.size > 1 ||
                    (change.position - down.position).getDistance() > slop
            }
            true
        }
        if (ended != null) return@awaitEachGesture
        onHold()
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
            if (event.changes.none { it.pressed }) break
        }
    }
}

/** A tile whose content takes every touch (an app, a map, a system widget): a hold on it is the app's. */
private fun DashboardItem.takesEveryTouch(): Boolean = when (this) {
    is DashboardItem.AppWindow, is DashboardItem.SystemWidget -> true
    is DashboardItem.BuiltinWidget -> kind == BuiltinKind.NAVMAP || kind == BuiltinKind.MAPS_INSIDE || kind == BuiltinKind.PIP_ANCHOR
    else -> false
}

/**
 * One tile placed on the dashboard grid. Fixed at its cell rectangle normally;
 * in edit mode it can be long-press-dragged to another cell (snapping on drop),
 * resized by the bracket on its bottom-right corner, or tapped for its panel.
 */
@Composable
internal fun GridTile(
    index: Int,
    item: DashboardItem,
    cellW: Dp,
    cellH: Dp,
    cellWpx: Float,
    cellHpx: Float,
    editing: Boolean,
    onModelTouch: (Boolean) -> Unit,
    onMoveCell: (Int, Int, Int) -> Unit,
    onResizeCell: (Int, Int, Int) -> Unit,
    canPlace: (Int, Int, Int, Int, Int) -> Boolean,
    canMove: (Int, Int, Int) -> Boolean,
    /** Its panel is open: outlined, and its bracket in the accent. */
    selected: Boolean,
    onSelectedBounds: (Rect) -> Unit,
    onTileOptions: (Int) -> Unit,
    onPreview: (Int, Int, Int, Int, Boolean, Boolean) -> Unit,
    onPreviewClear: () -> Unit,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    // The gesture detectors below restart only when the tile moves or resizes;
    // these always call the latest callbacks (a layout switch replaces them).
    val onMoveCell by rememberUpdatedState(onMoveCell)
    val onResizeCell by rememberUpdatedState(onResizeCell)
    val canPlace by rememberUpdatedState(canPlace)
    val canMove by rememberUpdatedState(canMove)
    val onPreview by rememberUpdatedState(onPreview)
    val onPreviewClear by rememberUpdatedState(onPreviewClear)
    val onModelTouch by rememberUpdatedState(onModelTouch)
    val onSelectedBounds by rememberUpdatedState(onSelectedBounds)
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var resizeExtra by remember { mutableStateOf(Offset.Zero) }
    var active by remember { mutableStateOf(false) }
    // The long press that lifts a tile, and the grab of its handle, are felt as well as seen.
    val haptics = LocalHapticFeedback.current

    val basePxX = with(density) { (cellW * item.x).toPx() }
    val basePxY = with(density) { (cellH * item.y).toPx() }
    val basePxW = with(density) { (cellW * item.w).toPx() }
    val basePxH = with(density) { (cellH * item.h).toPx() }

    // Snapped cell the tile will land on — kept identical to moveCell / resizeCell
    // so the highlighted ghost matches the committed result exactly.
    fun snapX() = ((basePxX + dragOffset.x) / cellWpx).roundToInt().coerceIn(0, GRID_COLS - item.w)
    fun snapY() = ((basePxY + dragOffset.y) / cellHpx).roundToInt().coerceIn(0, GRID_ROWS - item.h)
    fun snapW() = ((basePxW + resizeExtra.x) / cellWpx).roundToInt().coerceIn(item.minW(), GRID_COLS - item.x)
    fun snapH() = ((basePxH + resizeExtra.y) / cellHpx).roundToInt().coerceIn(item.minH(), GRID_ROWS - item.y)

    // The drag and resize offsets are read in the layout phase only, so a
    // finger moving a tile re-lays it out each frame without recomposing it.
    // A tile over the others stands above the grid's tiles (the ghost, at
    // 0.5, above it, the tile in hand above all); two such tiles keep the
    // page's order, the later on top.
    Box(
        modifier = Modifier
            .offset { IntOffset((basePxX + dragOffset.x).roundToInt(), (basePxY + dragOffset.y).roundToInt()) }
            .layout { measurable, _ ->
                val w = (basePxW + resizeExtra.x).coerceAtLeast(cellWpx).roundToInt()
                val h = (basePxH + resizeExtra.y).coerceAtLeast(cellHpx).roundToInt()
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) { placeable.place(0, 0) }
            }
            .zIndex(if (active) 1f else if (item.inGrid()) 0f else OVER_Z)
            .padding(3.dp)
            .then(if (index == 0) Modifier.tourTarget(TourTarget.TILE) else Modifier)
            .graphicsLayer {
                if (active) { scaleX = 1.03f; scaleY = 1.03f; shadowElevation = 20f }
            }
    ) {
        val frameShape = skinChrome().shapes.large
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Bare themes draw no card, so outline each tile while arranging.
                .then(
                    when {
                        selected -> Modifier
                            .onGloballyPositioned { onSelectedBounds(it.boundsInRoot()) }
                            .border(2.dp, DashColors.Accent, frameShape)
                        editing && DashColors.Bare -> Modifier.border(1.dp, DashColors.TextSecondary.copy(alpha = 0.35f), DashShape.Large)
                        else -> Modifier
                    }
                )
                // The tile's own border and opacity (TileFrame), under the arranging outline.
                .tileFrame(item.frame)
                // Dimmed while arranging: the tile is the thing being moved, not
                // read. Not the one whose panel is open: its new design shows as is.
                .graphicsLayer { alpha = if (editing && !selected) 0.7f else 1f }
        ) { content() }

        if (editing) {
            // Transparent scrim over the content captures the long-press drag so
            // even map / widget tiles (whose content eats touches) can be moved,
            // and taps don't reach the content: a tap opens the tile's options
            // (text size, design, move, remove). The buttons below sit above it.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(index) {
                        detectTapGestures(onTap = { onTileOptions(index) })
                    }
                    .pointerInput(index, item.x, item.y, item.w, item.h, cellWpx, cellHpx) {
                        // The ghost, and the collision check behind it, only
                        // when the finger crosses into another cell, not every frame.
                        var shownX = -1
                        var shownY = -1
                        fun previewMove() {
                            val sx = snapX(); val sy = snapY()
                            if (sx == shownX && sy == shownY) return
                            shownX = sx; shownY = sy
                            onPreview(sx, sy, item.w, item.h, canMove(index, sx, sy), false)
                        }
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                active = true; onModelTouch(true)
                                shownX = -1; shownY = -1
                                previewMove()
                            },
                            onDrag = { change, delta ->
                                change.consume(); dragOffset += delta
                                previewMove()
                            },
                            onDragEnd = {
                                onMoveCell(index, snapX(), snapY())
                                dragOffset = Offset.Zero; active = false; onModelTouch(false); onPreviewClear()
                            },
                            onDragCancel = {
                                dragOffset = Offset.Zero; active = false; onModelTouch(false); onPreviewClear()
                            }
                        )
                    }
            )

            // The bracket on the bottom-right corner: drag it to change the
            // cell span. It follows the tile's own corner, so it reads as part
            // of the frame and hides nothing of the tile; the target around it
            // reaches past the corner. Grey on every tile, the accent (as its
            // outline) on the one being worked on. Nothing else sits on a tile:
            // a tap opens its panel (TileOptions.kt), which has the rest.
            val corner = skinChrome().shapes.large
            val bracket = if (selected || active) DashColors.Accent else DashColors.TextSecondary.copy(alpha = 0.75f)
            val resizeLabel = stringResource(R.string.dash_resize_tile, item.describe())
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = HANDLE_REACH, y = HANDLE_REACH)
                    .size(DashSize.TouchPrimary)
                    .semantics { contentDescription = resizeLabel }
                    .pointerInput(index, item.x, item.y, item.w, item.h, cellWpx, cellHpx) {
                        var shownW = -1
                        var shownH = -1
                        fun previewResize() {
                            val sw = snapW(); val sh = snapH()
                            if (sw == shownW && sh == shownH) return
                            shownW = sw; shownH = sh
                            onPreview(item.x, item.y, sw, sh, canPlace(index, item.x, item.y, sw, sh), true)
                        }
                        detectDragGestures(
                            onDragStart = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                active = true; onModelTouch(true)
                                shownW = -1; shownH = -1
                                previewResize()
                            },
                            onDrag = { change, delta ->
                                change.consume(); resizeExtra += delta
                                previewResize()
                            },
                            onDragEnd = {
                                onResizeCell(index, snapW(), snapH())
                                resizeExtra = Offset.Zero; active = false; onModelTouch(false); onPreviewClear()
                            },
                            onDragCancel = {
                                resizeExtra = Offset.Zero; active = false; onModelTouch(false); onPreviewClear()
                            }
                        )
                    }
                    .drawBehind {
                        val stroke = HANDLE_STROKE.toPx()
                        // The tile's corner, inset half a stroke so the line lies on its edge.
                        val cx = size.width - HANDLE_REACH.toPx() - stroke / 2
                        val cy = size.height - HANDLE_REACH.toPx() - stroke / 2
                        val r = (corner.bottomEnd.toPx(size, this) - stroke / 2).coerceIn(0f, HANDLE_ARM.toPx() - stroke)
                        val arm = HANDLE_ARM.toPx()
                        val path = Path().apply {
                            moveTo(cx, cy - arm)
                            lineTo(cx, cy - r)
                            arcTo(Rect(cx - 2 * r, cy - 2 * r, cx, cy), 0f, 90f, false)
                            lineTo(cx - arm, cy)
                        }
                        drawPath(path, bracket, style = Stroke(width = stroke, cap = StrokeCap.Round))
                    }
            )
        }
    }
}

/** Where the tiles over the others stand: above the grid's (0), under the snap ghost (0.5) and the tile in hand (1). */
private const val OVER_Z = 0.25f

/**
 * A tile's frame ([TileFrame]) on its box: its border around what the widget
 * draws ([widgetOutline]) and its opacity, which fades the border with the
 * content. The opacity is always applied, at 1 too, so changing it never
 * rebuilds the tile (and its map or widget).
 */
@Composable
private fun Modifier.tileFrame(frame: TileFrame): Modifier {
    val base = graphicsLayer { alpha = frame.opacity }
    if (frame.border <= 0) return base
    val colour = frame.borderColor.color()
    val silhouette = rememberGraphicsLayer()
    return base.widgetOutline(silhouette, frame.border.dp, colour)
}

/**
 * A line of [width] in [colour] around whatever the content draws, not
 * around its box: a round music tile gets a ring, a card its rounded edge, a
 * bare widget's words and dial their own outline. The content is recorded
 * into [silhouette], drawn in the colour alone (its alpha kept) a few times
 * around itself, then drawn as it is on top.
 */
private fun Modifier.widgetOutline(silhouette: GraphicsLayer, width: Dp, colour: Color): Modifier = drawWithContent {
    // The draw scope's own record: it points the content's drawing into the
    // layer for the block. The layer's plain record would not, and the
    // content would go straight to the screen, leaving the layer empty.
    silhouette.record { this@drawWithContent.drawContent() }
    silhouette.colorFilter = ColorFilter.tint(colour, BlendMode.SrcIn)
    val w = width.toPx()
    // A ring of copies at the line's width; wide lines get an inner ring too, so the corners fill in.
    for (i in 0 until OUTLINE_STEPS) {
        val a = i * 2.0 * PI / OUTLINE_STEPS
        translate((cos(a) * w).toFloat(), (sin(a) * w).toFloat()) { drawLayer(silhouette) }
    }
    if (w > OUTLINE_INNER_RING_PX) {
        for (i in 0 until OUTLINE_STEPS) {
            val a = (i + 0.5) * 2.0 * PI / OUTLINE_STEPS
            translate((cos(a) * w / 2).toFloat(), (sin(a) * w / 2).toFloat()) { drawLayer(silhouette) }
        }
    }
    drawContent()
}

/** How many copies make the ring around the content, and the width from which a second ring is needed. */
private const val OUTLINE_STEPS = 16
private const val OUTLINE_INNER_RING_PX = 6f

/** The colour a border is drawn in, from the palette on screen where it has one. */
@Composable
internal fun TileBorderColor.color(): Color = when (this) {
    TileBorderColor.ACCENT -> DashColors.Accent
    TileBorderColor.TEXT -> DashColors.TextPrimary
    TileBorderColor.WHITE -> Color.White
    TileBorderColor.BLACK -> Color.Black
    TileBorderColor.RED -> Color(0xFFE53935)
    TileBorderColor.YELLOW -> Color(0xFFFFC107)
    TileBorderColor.GREEN -> Color(0xFF43A047)
    TileBorderColor.BLUE -> Color(0xFF1E88E5)
}

/** How far the resize target reaches past the tile's corner. */
private val HANDLE_REACH = 12.dp
/** The bracket's arms, from the corner, and its line. */
private val HANDLE_ARM = 30.dp
private val HANDLE_STROKE = 5.dp

/**
 * Renders the inner content of one dashboard tile (widget card, app shortcut,
 * split pair, or hosted system widget). Shared by the horizontal row layout and
 * the vertical split-screen stack so both look identical.
 */
@Composable
internal fun TileContent(
    item: DashboardItem,
    editing: Boolean,
    appsByPackage: Map<String, AppEntry>,
    media: State<MediaState>,
    mediaController: CarMediaController,
    hasMediaAccess: Boolean,
    context: android.content.Context,
    obdConnection: State<ObdConnectionState>,
    onConnectObd: () -> Unit,
    onPickDevice: () -> Unit,
    onLaunchApp: (String) -> Unit,
    onLaunchSplitPair: (String, String) -> Unit,
    onEditLaunchBar: () -> Unit,
    onModelTouch: (Boolean) -> Unit,
    /** Grid only: grow this tile to at least the given pixel size (a docked window's minimum). */
    onFitToWindow: ((Int, Int) -> Unit)? = null
) {
    // The callbacks the dashboard hands down are new objects at every root recomposition (once a
    // minute, every swipe), which made this env new for every tile on every composed page. Built
    // over holders that follow the latest callbacks, it stays the same object while nothing a tile
    // reads has changed, so the tiles can skip.
    val latestConnect = rememberUpdatedState(onConnectObd)
    val latestPick = rememberUpdatedState(onPickDevice)
    val latestLaunch = rememberUpdatedState(onLaunchApp)
    val latestEditBar = rememberUpdatedState(onEditLaunchBar)
    val env = remember(editing, appsByPackage, media, mediaController, hasMediaAccess, context, obdConnection) {
        SkinTileEnv(
            editing, appsByPackage, media, mediaController, hasMediaAccess, context,
            obdConnection, { latestConnect.value() }, { latestPick.value() }, { latestLaunch.value(it) }, { latestEditBar.value() }
        )
    }
    // A tile's own design beats the skin; its standard look is this same
    // routing with the design cleared (the skin's tile under a skin).
    // The Open look wears its design on every widget that has a face (framed ones and the bar have none, so no loop back here).
    val open = item is DashboardItem.BuiltinWidget && item.design == WidgetDesign.STANDARD && DashColors.Open &&
        item.kind !in FRAMED_KINDS && item.kind != BuiltinKind.DASH_BAR
    if (item is DashboardItem.BuiltinWidget && (open || item.design != WidgetDesign.STANDARD)) {
        DesignedTile(if (open) item.copy(design = WidgetDesign.OPEN) else item, env) {
            TileContent(
                item.copy(design = WidgetDesign.STANDARD), editing, appsByPackage, media, mediaController, hasMediaAccess,
                context, obdConnection, onConnectObd, onPickDevice, onLaunchApp, onLaunchSplitPair, onEditLaunchBar,
                onModelTouch, onFitToWindow
            )
        }
        return
    }
    if (skinHandles(item)) {
        SkinTile(item, env)
        return
    }
    when (item) {
        // The tiles a skin can redraw have one standard renderer, shared with the
        // skins' own fallback, so the two never drift apart.
        is DashboardItem.LaunchBar, is DashboardItem.AppShortcut -> StandardSkinnedTile(item, env)

        is DashboardItem.AppWindow -> {
            val label = appsByPackage[item.packageName]?.label ?: item.packageName.substringAfterLast('.')
            val inside = item.runsInside(rememberInsideAllowed())
            when {
                // While arranging, the window would cover its own tile's handles.
                editing -> EditPlaceholder(
                    icon = Icons.Filled.OpenInNew,
                    label = stringResource(if (inside) R.string.dash_app_inside else R.string.dash_app_window, label)
                )
                inside && underDashboard(item.packageName) -> EditPlaceholder(
                    icon = Icons.Filled.OpenInNew,
                    label = label,
                    hint = stringResource(R.string.apps_embed_under_dashboard)
                )
                inside -> EmbeddedAppCard(item.packageName, label, modifier = Modifier.fillMaxSize())
                else -> PipAnchorCard(modifier = Modifier.fillMaxSize(), packageName = item.packageName, appLabel = label, onWindowBiggerThanTile = onFitToWindow)
            }
        }

        is DashboardItem.SplitPair -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            SplitPairTile(
                primaryApp = appsByPackage[item.primaryPackage],
                secondaryApp = appsByPackage[item.secondaryPackage],
                primaryPackage = item.primaryPackage,
                secondaryPackage = item.secondaryPackage,
                editing = editing,
                onClick = { onLaunchSplitPair(item.primaryPackage, item.secondaryPackage) }
            )
        }

        is DashboardItem.BuiltinWidget -> SkinFallback(item.kind) { when (item.kind) {
            BuiltinKind.NAVMAP -> if (editing) {
                EditPlaceholder(icon = Icons.Filled.Navigation, label = BuiltinKind.NAVMAP.label)
            } else if (isEmulator) {
                // MapLibre's native renderer segfaults on the emulator's software GL.
                EditPlaceholder(icon = Icons.Filled.Navigation, label = stringResource(R.string.dash_map_needs_gpu), hint = stringResource(R.string.dash_not_on_emulator))
            } else Box(
                modifier = Modifier.fillMaxSize().background(DashColors.Card)
            ) {
                MapLibrePanel(modifier = Modifier.fillMaxSize())
                // Google Maps / Waze next turn, floated over the MapLibre map.
                val nav by NavDirections.state.collectAsState()
                if (nav.active) {
                    DirectionsBanner(
                        nav = nav,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                    )
                }
            }
            BuiltinKind.NAVIGATION, BuiltinKind.MEDIA, BuiltinKind.TELEMETRY,
            BuiltinKind.RANGE, BuiltinKind.SPEED_HUD, BuiltinKind.CLOCK, BuiltinKind.WEATHER ->
                StandardSkinnedTile(item, env)
            BuiltinKind.OBD_DTC -> ObdDtcCard(
                connection = obdConnection.value,
                onConnect = onConnectObd,
                modifier = Modifier.fillMaxSize(),
                onPickDevice = onPickDevice
            )
            BuiltinKind.OBD_ALL -> ObdAllCard(
                connection = obdConnection.value,
                onConnect = onConnectObd,
                modifier = Modifier.fillMaxSize(),
                onPickDevice = onPickDevice
            )
            BuiltinKind.DOORS -> DoorsCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.CAN_MON -> CanMonitorCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.COMPASS -> CompassCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.PIP_ANCHOR -> if (editing) {
                EditPlaceholder(icon = Icons.Filled.Map, label = BuiltinKind.PIP_ANCHOR.label)
            } else PipAnchorCard(modifier = Modifier.fillMaxSize(), onWindowBiggerThanTile = onFitToWindow)
            BuiltinKind.MAPS_INSIDE -> if (editing) {
                EditPlaceholder(icon = Icons.Filled.Map, label = BuiltinKind.MAPS_INSIDE.label)
            } else if (underDashboard(EmbeddedApp.MAPS_PACKAGE)) {
                EditPlaceholder(
                    icon = Icons.Filled.Map,
                    label = BuiltinKind.MAPS_INSIDE.label,
                    hint = stringResource(R.string.apps_embed_under_dashboard)
                )
            } else EmbeddedMapsCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.TRIP -> TripCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.DASH_BAR -> DashBarTile()
            BuiltinKind.HEAD_UNIT -> HeadUnitCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.GEAR -> GearCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.GFORCE -> GForceCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.PARKING -> ParkingCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.CALENDAR -> CalendarCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.QUICK_DIAL -> QuickDialCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.NOTIFICATIONS -> NotificationsCard(hasAccess = hasMediaAccess, modifier = Modifier.fillMaxSize())
            BuiltinKind.AUDIO -> AudioCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.SCREEN_LIGHT -> ScreenLightCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.RADIOS -> RadioSwitchesCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.UNIT_LIGHT -> UnitLightCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.SPEED_CAMERAS -> SpeedCamerasCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.RADIO_PRESETS -> RadioPresetsCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.HOME_WORK -> HomeWorkCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.SHARE_ETA -> ShareEtaCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.VOICE_NOTES -> VoiceNotesCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.QUICK_SWITCHES -> QuickSwitchesCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.GPS_STATUS -> GpsStatusCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.PERF_TIMER -> PerfTimerCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.WIFI_NETWORKS -> WifiNetworksCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.BT_DEVICES -> BluetoothDevicesCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.SPEED_LIMIT -> SpeedLimitCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.PARKING_TIMER -> ParkingTimerCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.FUEL_LOG -> FuelLogCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.CONSUMPTION -> ConsumptionCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.WEATHER_ALERTS -> WeatherAlertsCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.ENGINE_TEMPS -> EngineTempsCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.COMMUTE -> CommuteCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.DRIVE_HISTORY -> DriveHistoryCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.NOW -> NowCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.LPG_TANK -> LpgTankCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.MY_READINGS -> MyReadingsCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.FILTER_CARE -> FilterCareCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.WARMUP -> WarmupCard(Modifier.fillMaxSize())
            BuiltinKind.BATTERY -> BatteryCard(Modifier.fillMaxSize())
            BuiltinKind.MY_CAR -> MyCarCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.ECO_DRIVE -> EcoDriveCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.BREAK_TIMER -> BreakCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.FUEL_TO_DEST -> FuelToDestCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.SERVICE -> ServiceCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.FUEL_PRICES -> FuelPricesCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.CAR_STATUS -> CarStatusCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.TYRES -> TyresCard(modifier = Modifier.fillMaxSize())
        } }

        is DashboardItem.SystemWidget -> if (editing) {
            EditPlaceholder(icon = Icons.Filled.Widgets, label = stringResource(R.string.dash_app_widget))
        } else Card(
            modifier = Modifier.fillMaxSize()
        ) {
            HostedSystemWidget(appWidgetId = item.appWidgetId, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * A widget the skin does not redraw, on the skin's panel ([SkinFallbackPanel]).
 * Live views (the map, a docked window) and the bar of dashboards stay as they
 * are: they fill their tile edge to edge.
 */
@Composable
private fun SkinFallback(kind: BuiltinKind, content: @Composable () -> Unit) {
    if (DashColors.Skin == DashSkin.STANDARD || !DashColors.Bare || kind in FRAMED_KINDS || kind == BuiltinKind.DASH_BAR || kind in SKINNED_KINDS) {
        content()
        return
    }
    SkinFallbackPanel(content)
}

/** What an empty dashboard shows: one line, then filling it from a template first, a tile at a time, or every dashboard. */
@Composable
internal fun EmptyPage(onFill: () -> Unit, onAdd: () -> Unit, onTemplates: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.dash_empty_title),
            color = DashColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.dash_empty_body),
            color = DashColors.TextSecondary,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onFill,
                colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.OnAccent),
                shape = DashShape.Small
            ) {
                Text(stringResource(R.string.templates_fill_this))
            }
            OutlinedButton(
                onClick = onAdd,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DashColors.TextPrimary),
                border = BorderStroke(1.dp, DashColors.Line),
                shape = DashShape.Small
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.dash_add_tile))
            }
            TextButton(onClick = onTemplates) {
                Text(stringResource(R.string.templates_all_dashboards), color = DashColors.TextSecondary)
            }
        }
    }
}

/** Short spoken name for a tile, for the edit controls' accessibility labels. */
@Composable
internal fun DashboardItem.describe(): String = when (this) {
    is DashboardItem.BuiltinWidget -> kind.label
    is DashboardItem.AppShortcut -> packageName.substringAfterLast('.')
    is DashboardItem.SplitPair -> stringResource(R.string.dash_describe_split_pair)
    is DashboardItem.LaunchBar -> stringResource(R.string.dash_describe_launch_bar)
    is DashboardItem.SystemWidget -> stringResource(R.string.dash_describe_widget)
    is DashboardItem.AppWindow ->
        stringResource(if (runsInside(rememberInsideAllowed())) R.string.dash_app_inside else R.string.dash_app_window, packageName.substringAfterLast('.'))
}

/** Whether apps can run inside their tile on this unit (EmbeddedApp.allowed); it only changes with a restart. */
@Composable
internal fun rememberInsideAllowed(): Boolean {
    val context = LocalContext.current
    return remember(context) { EmbeddedApp.allowed(context) }
}

/**
 * Draws [content] as if the screen were [zoom] times denser: every dp and sp
 * inside, so the tile's text, icons and spacing, grows or shrinks while the
 * tile keeps its cells, and the layout re-flows to fit. Always provided, even
 * at 1, so changing the zoom never rebuilds the tile (and its map or widget).
 */
@Composable
internal fun TileZoom(zoom: Float, content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val zoomed = remember(base, zoom) { Density(base.density * zoom, base.fontScale) }
    CompositionLocalProvider(LocalDensity provides zoomed, content = content)
}

/**
 * Identity of a tile for Compose keys: what it is plus which occurrence it is,
 * so two shortcuts to the same app still get distinct keys.
 */
internal fun tileKeys(items: List<DashboardItem>): List<String> {
    fun id(item: DashboardItem) = when (item) {
        is DashboardItem.AppShortcut -> "app:${item.packageName}"
        is DashboardItem.SplitPair -> "split:${item.primaryPackage}|${item.secondaryPackage}"
        is DashboardItem.LaunchBar -> "bar"
        is DashboardItem.BuiltinWidget -> "builtin:${item.kind.name}"
        is DashboardItem.SystemWidget -> "widget:${item.appWidgetId}"
        is DashboardItem.AppWindow -> (if (item.inside) "appin:" else "appwin:") + item.packageName
    }
    val seen = HashMap<String, Int>()
    return items.map { item ->
        val me = id(item)
        val occurrence = seen[me] ?: 0
        seen[me] = occurrence + 1
        "$me#$occurrence"
    }
}

/** Static stand-in for a view-hosting tile while the dashboard is being arranged. */
@Composable
internal fun EditPlaceholder(
    icon: ImageVector,
    label: String,
    hint: String = stringResource(R.string.dash_shown_while_arranging)
) {
    Card(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, color = DashColors.TextSecondary, fontWeight = FontWeight.SemiBold)
            Text(hint, color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}
