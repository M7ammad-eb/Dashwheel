package com.openauto.dash

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/*
 * Tile designs: routing a built-in tile to its design, and the shelf that
 * changes it (in the tile's panel while arranging, TileOptions.kt).
 */

/**
 * A built-in tile drawn in its non-standard design. Framed kinds (map, Maps
 * window, 3D car, My car) keep their [standard] content inside the design's
 * frame; every other widget draws its live face, which covers the states
 * without a reading too (see WidgetFaceData.kt).
 */
@Composable
internal fun DesignedTile(item: DashboardItem.BuiltinWidget, env: SkinTileEnv, standard: @Composable () -> Unit) {
    if (item.kind in FRAMED_KINDS) {
        DesignFrame(item.design, kindIcon(item.kind), item.kind.label, Modifier.fillMaxSize()) { standard() }
        return
    }
    // A design no longer offered for this widget (it could not show what the widget needs): its own look.
    if (!item.design.appliesTo(item.kind)) return standard()
    // A row that calls or re-routes waits in this strip first, as on the standard tiles (PendingAction.kt).
    val pending = rememberPendingAction()
    CompositionLocalProvider(LocalPendingAction provides pending) {
        val face = rememberWidgetFace(item.kind, env)
        if (face == null) {
            standard()
        } else {
            Box(Modifier.fillMaxSize()) {
                DesignedFace(face, item.design, Modifier.fillMaxSize())
                PendingActionStrip(pending, Modifier.align(Alignment.BottomCenter).padding(DashSpace.Sm))
                face.edit?.let { EditCornerButton(it.reveal, it.onEdit, Modifier.align(Alignment.TopEnd)) }
            }
        }
    }
}

/**
 * Every design for [kind], in the tile's panel: a chip per family (the first,
 * named after the widget, holds Standard and the designs made for it), that
 * family's designs as live previews at the tile's own proportions [aspect],
 * and the name and description of the one in use. A tap puts a design on the
 * tile at once; the panel stays, so several can be tried in a row.
 * [standardPreview] draws the tile as it is without a design.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesignShelf(
    kind: BuiltinKind,
    current: WidgetDesign,
    aspect: Float,
    env: SkinTileEnv,
    standardPreview: @Composable () -> Unit,
    onPick: (WidgetDesign) -> Unit,
    modifier: Modifier = Modifier
) {
    val frozen = rememberFaceSnapshot(kind, env)
    val sample = sampleFace(kind)
    val framed = kind in FRAMED_KINDS
    val offered = remember(kind) { WidgetDesign.offeredFor(kind, framed) }
    // The widget's own shelf (null), then each family that has something for it.
    val shelves = remember(offered) {
        listOf<DesignFamily?>(null) + DesignFamily.entries.filter { f -> offered.any { DesignFamily.of(it) == f } }
    }
    // A saved design this widget no longer offers is drawn as Standard (DesignedTile): the shelf says so too.
    val worn = if (current.appliesTo(kind)) current else WidgetDesign.STANDARD
    var shelf by remember(kind) { mutableStateOf(DesignFamily.of(worn)) }
    val shown = offered.filter { DesignFamily.of(it) == shelf }
    // For what is read at the wheel, the looks read in a glance come first; the
    // pretty-but-slow ones follow under their own heading. Nothing is taken away.
    val atTheWheel = kind.category == WidgetCategory.DRIVING || kind.category == WidgetCategory.NAVIGATION
    val quick = if (atTheWheel) shown.filter { it.glanceable } else shown
    val parked = if (atTheWheel) shown.filter { !it.glanceable } else emptyList()
    val chips: @Composable () -> Unit = {
        // Wrapped, not scrolled: a family cut at the panel's edge went unseen.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            shelves.forEach { f ->
                CategoryChip(if (f == null) kind.label else stringResource(f.titleRes), shelf == f) { shelf = f }
            }
        }
    }
    // The previews carry names only; the one in use says what it is.
    val inUse: @Composable () -> Unit = {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold)) { append(worn.title) }
                append("  ")
                append(worn.description)
            },
            color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
    }
    BoxWithConstraints(modifier = modifier, propagateMinConstraints = true) {
        // A low panel (a wide, short screen): the chips and the chips' wrap took
        // all its height and left the previews none. There, everything scrolls
        // as one, and the previews are smaller, two or more to a row.
        val short = maxHeight < SHELF_SHORT_HEIGHT
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!short) chips()
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (short) DESIGN_THUMB_SHORT_MIN_WIDTH else DESIGN_THUMB_MIN_WIDTH),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(if (short) 8.dp else 10.dp)
            ) {
                if (short) {
                    item(key = "chips", span = { GridItemSpan(maxLineSpan) }) { chips() }
                    item(key = "in-use", span = { GridItemSpan(maxLineSpan) }) { inUse() }
                }
                val thumbs: (List<WidgetDesign>) -> Unit = { list ->
                    items(list, key = { it.name }) { design ->
                        DesignThumb(design, design == worn, aspect, onClick = { onPick(design) }) {
                            when {
                                design == WidgetDesign.STANDARD -> standardPreview()
                                framed -> DesignFrame(design, kindIcon(kind), kind.label, Modifier.fillMaxSize()) { FramedPlaceholder(kind) }
                                else -> DesignedFace(frozen.value ?: sample, design, Modifier.fillMaxSize())
                            }
                        }
                    }
                }
                thumbs(quick)
                if (parked.isNotEmpty()) {
                    item(key = "parked", span = { GridItemSpan(maxLineSpan) }) {
                        Column(modifier = Modifier.fillMaxWidth().padding(start = 2.dp, top = 6.dp)) {
                            Text(
                                stringResource(R.string.design_parked_looks).uppercase(),
                                color = DashColors.Muted, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
                                style = MaterialTheme.typography.labelMedium, maxLines = 1
                            )
                            Text(
                                stringResource(R.string.design_parked_looks_note),
                                color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    thumbs(parked)
                }
            }
            if (!short) inUse()
        }
    }
}

/** Below this height the shelf scrolls as one: chips, the design in use, then the previews. */
private val SHELF_SHORT_HEIGHT = 300.dp

/** The short shelf's smaller previews, so more than one shows at once. */
private val DESIGN_THUMB_SHORT_MIN_WIDTH = 130.dp

/** Below this width the shelf gives up a column: two big ones to a row in the side panel. */
private val DESIGN_THUMB_MIN_WIDTH = 190.dp

/**
 * The widget's live reading for the shelf's previews, refreshed at most once
 * a second: some twenty previews at feed rate (15 Hz g-force, OBD) would bog
 * the unit down while the driver just picks a look. Null for framed kinds.
 */
@Composable
private fun rememberFaceSnapshot(kind: BuiltinKind, env: SkinTileEnv): State<WidgetFace?> {
    val live = if (kind in FRAMED_KINDS) null else rememberWidgetFace(kind, env)
    val latest = rememberUpdatedState(live)
    val frozen = remember { mutableStateOf(live) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            frozen.value = latest.value
        }
    }
    return frozen
}

/**
 * One design: its preview over the page colour, framed in the accent when it
 * is the tile's, and its name. The whole card is one button; taps never reach
 * the preview's own controls.
 */
@Composable
private fun DesignThumb(
    design: WidgetDesign,
    selected: Boolean,
    aspect: Float,
    onClick: () -> Unit,
    preview: @Composable () -> Unit
) {
    val tap = rememberTapFeedback()
    Column(
        modifier = Modifier
            .clip(DashShape.Small)
            .clickable(role = Role.Button, onClickLabel = design.title) { tap(); onClick() },
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect.coerceIn(0.8f, 3f))
                .clip(DashShape.Small)
                .background(dashBackgroundBrush())
                .border(if (selected) 2.dp else 1.dp, if (selected) DashColors.Accent else DashColors.Line, DashShape.Small)
                .padding(3.dp)
        ) {
            preview()
            // Sees every touch without consuming it, so the card's click gets the tap.
            Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp)) {
            Text(
                design.title,
                color = if (selected) DashColors.Accent else DashColors.TextPrimary,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (selected) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.dash_selected), tint = DashColors.Accent, modifier = Modifier.size(16.dp))
        }
    }
}

/** The page colour behind a preview, so light and bare designs show as they would on the dashboard. */
private fun dashBackgroundBrush(): Brush = Brush.linearGradient(DashColors.BackgroundStops)

/** What a framed live view stands in for on the shelf (the real map or model is too heavy to run sixteen times). */
@Composable
private fun FramedPlaceholder(kind: BuiltinKind) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(DashColors.CardHi, DashColors.Card))),
        contentAlignment = Alignment.Center
    ) {
        Icon(kindIcon(kind), contentDescription = null, tint = DashColors.Accent.copy(alpha = 0.7f), modifier = Modifier.size(36.dp))
    }
}

/**
 * The generic designs by family, one chip each on the shelf: forty cards
 * were a wall, a family at a time is a choice.
 */
internal enum class DesignFamily(@StringRes val titleRes: Int) {
    THEME(R.string.design_family_theme),
    MATERIALS(R.string.design_family_materials),
    COPPER(R.string.design_family_copper),
    CABINS(R.string.design_family_cabins);

    companion object {
        private val CABIN_LOOKS = setOf(FaceLookKind.NAVY_GOLD, FaceLookKind.SAGE, FaceLookKind.CRIMSON, FaceLookKind.GRAPHITE)

        /** The family of a generic design; null for Standard and the widget-specific ones. */
        fun of(design: WidgetDesign): DesignFamily? = when {
            design == WidgetDesign.STANDARD || design.isSignature -> null
            design.look == FaceLookKind.COPPER || design.look == FaceLookKind.PETROL -> COPPER
            design.look in CABIN_LOOKS -> CABINS
            design.look == FaceLookKind.THEME || design.look == FaceLookKind.OPEN -> THEME
            else -> MATERIALS
        }
    }
}
