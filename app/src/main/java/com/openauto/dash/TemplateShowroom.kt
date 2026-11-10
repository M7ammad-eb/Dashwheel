package com.openauto.dash

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import kotlin.math.abs

/*
 * Templates as a showroom: the set picked is shown on this screen as it will
 * really be, its tiles drawn by the dashboard's own renderers, Home in the
 * middle and the dashboards beside it turned away on either side. Swipe (or
 * the chevrons, or a card) to try another; say how far it reaches; one button
 * puts it on, and the dashboard then offers Undo for a while instead of a
 * "replace?" question first.
 */

/** How far a template reaches: Home alone, Home and the two beside it, or the whole cross. */
internal enum class TemplateReach(@StringRes val labelRes: Int, val pages: Set<Int>) {
    HOME(R.string.templates_reach_home, setOf(DashboardStore.CENTER)),
    ROW(R.string.templates_reach_row, TemplatePlacer.ROW_PAGES),
    ALL(R.string.templates_reach_all, TemplatePlacer.ALL_PAGES)
}

/** The pages [template] lays out within [reach]: never one it has nothing for (Calm leaves the column as it is). */
internal fun templateTargets(template: DashTemplate, reach: Set<Int>): Set<Int> = reach.filterTo(HashSet()) { it in template.pages }

/**
 * @param screen the car and screen the templates are placed for
 * @param pageSize one dashboard's size on this screen: the previews are that page, scaled down
 * @param onlyPage an empty page's own Fill: the template's page for that spot goes there alone
 * @param tile draws one tile as the dashboard does, while arranging (maps and windows as their stand-ins)
 * @param onMoments opens Moments; null leaves the button out
 */
@Composable
internal fun TemplateShowroom(
    screen: TemplateScreen,
    pageSize: DpSize,
    onlyPage: Int?,
    tile: @Composable (DashboardItem) -> Unit,
    onApply: (TemplateChoice) -> Unit,
    onMoments: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val templates = DashTemplate.entries
    var index by remember { mutableStateOf(0) }
    val chosen = templates[index]
    var reach by remember { mutableStateOf(TemplateReach.ROW) }
    val tap = rememberTapFeedback()
    fun step(by: Int) {
        index = (index + by + templates.size) % templates.size
    }
    BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .keepClearOfWindows()
                .background(showroomBackground())
                .padding(horizontal = 28.dp, vertical = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.templates_pick_title),
                        color = DashColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1
                    )
                    Text(
                        if (onlyPage != null) stringResource(R.string.templates_fill_this_hint, pageName(onlyPage))
                        else stringResource(R.string.templates_pick_hint),
                        color = DashColors.TextSecondary,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (onlyPage == null) {
                    ReachSwitch(reach) { reach = it }
                    if (onMoments != null) {
                        Spacer(Modifier.width(12.dp))
                        PillButton(stringResource(R.string.moments_title), Icons.Filled.Schedule) { tap(); onMoments() }
                    }
                }
            }
            // The stage: Home in the middle, the dashboards beside it turned away.
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        var dragged = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dragged = 0f },
                            onHorizontalDrag = { change, dx -> change.consume(); dragged += dx },
                            onDragEnd = { if (abs(dragged) > SWIPE_PX) step(if (dragged < 0) 1 else -1) }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                val aspect = (pageSize.width / pageSize.height).coerceIn(0.4f, 3f)
                val homeW = min(maxWidth * 0.52f, (maxHeight - 40.dp) * aspect)
                val homeH = homeW / aspect
                val sideW = homeW * 0.62f
                val built = remember(chosen, screen, onlyPage) {
                    TemplatePlacer.pages(chosen, screen, onlyPage?.let { setOf(it) } ?: TemplatePlacer.ALL_PAGES)
                }
                val centrePage = onlyPage ?: DashboardStore.CENTER
                val sides = onlyPage == null && reach != TemplateReach.HOME
                Crossfade(targetState = chosen, label = "template") { shown ->
                    // Remembered: the outgoing template was placed again on every frame of the fade.
                    val pages = if (shown == chosen) built else remember(shown, screen) { TemplatePlacer.pages(shown, screen) }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (sides) {
                            SidePage(pages[0], pageName(0), pageSize, sideW, Modifier.align(Alignment.CenterStart).padding(start = 40.dp), tile, turn = 30f)
                            SidePage(pages[2], pageName(2), pageSize, sideW, Modifier.align(Alignment.CenterEnd).padding(end = 40.dp), tile, turn = -30f)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            PagePreview(
                                items = pages[centrePage],
                                pageSize = pageSize,
                                width = homeW,
                                tile = tile,
                                modifier = Modifier
                                    .border(2.dp, DashColors.Accent, DashShape.Medium)
                            )
                            Text(
                                if (reach == TemplateReach.ALL && onlyPage == null) stringResource(R.string.templates_reach_all_note)
                                else pageName(centrePage),
                                color = DashColors.TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
                Chevron(Icons.Filled.ChevronLeft, stringResource(R.string.templates_previous), Modifier.align(Alignment.CenterStart)) { tap(); step(-1) }
                Chevron(Icons.Filled.ChevronRight, stringResource(R.string.templates_next), Modifier.align(Alignment.CenterEnd)) { tap(); step(1) }
                Spacer(Modifier.size(1.dp, homeH))
            }
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                    templates.forEachIndexed { i, template ->
                        TemplateCard(template, screen, onlyPage ?: DashboardStore.CENTER, i == index, Modifier.weight(1f)) { tap(); index = i }
                    }
                }
                Spacer(Modifier.width(18.dp))
                Column(horizontalAlignment = Alignment.End) {
                    if (TemplatePlacer.leavesOutCarData(chosen, screen, onlyPage?.let { setOf(it) })) {
                        Text(
                            stringResource(R.string.templates_no_car_data),
                            color = DashColors.Warning,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            modifier = Modifier.width(260.dp).padding(bottom = 6.dp)
                        )
                    }
                    Button(
                        onClick = {
                            tap()
                            val pages = onlyPage?.let { setOf(it) } ?: templateTargets(chosen, reach.pages)
                            onApply(TemplateChoice(chosen, pages, replace = true))
                        },
                        modifier = Modifier.heightIn(min = 64.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.OnAccent),
                        shape = RoundedCornerShape(32.dp),
                        contentPadding = PaddingValues(horizontal = 36.dp, vertical = 8.dp)
                    ) {
                        Text(
                            if (onlyPage != null) stringResource(R.string.templates_fill)
                            else stringResource(R.string.templates_use, stringResource(chosen.titleRes)),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.templates_undo_note),
                            color = DashColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                        TextButton(onClick = { tap(); onDismiss() }, modifier = Modifier.heightIn(min = DashSize.Touch)) {
                            Text(stringResource(R.string.dash_cancel), color = DashColors.TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

/** A drag further than this, sideways, tries the next template. */
private const val SWIPE_PX = 90f

/** The showroom's floor: the page colour, lit from the middle. */
@Composable
private fun showroomBackground(): Brush {
    // Opaque: some looks have a see-through page colour over their scenery.
    val floor = DashColors.Background.copy(alpha = 1f)
    return Brush.radialGradient(listOf(DashColors.Accent.copy(alpha = 0.16f).compositeOver(floor), floor, floor), radius = 1400f)
}

/** Home, Home and beside it, All seven. */
@Composable
private fun ReachSwitch(reach: TemplateReach, onPick: (TemplateReach) -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(30.dp))
            .background(DashColors.Card)
            .border(1.dp, DashColors.Line, RoundedCornerShape(30.dp))
            .padding(4.dp)
    ) {
        TemplateReach.entries.forEach { r ->
            val on = r == reach
            Box(
                modifier = Modifier
                    .heightIn(min = DashSize.Touch)
                    .clip(RoundedCornerShape(26.dp))
                    .background(if (on) DashColors.Accent.copy(alpha = 0.22f) else Color.Transparent)
                    .then(if (on) Modifier.border(1.5.dp, DashColors.Accent, RoundedCornerShape(26.dp)) else Modifier)
                    .clickable(role = Role.RadioButton) { tap(); onPick(r) }
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(r.labelRes),
                    color = if (on) DashColors.TextPrimary else DashColors.TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1
                )
            }
        }
    }
}

/** A secondary action in the header: outlined, round, an icon and a word. */
@Composable
internal fun PillButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .heightIn(min = DashSize.TouchPrimary)
            .clip(RoundedCornerShape(30.dp))
            .border(1.5.dp, DashColors.Line, RoundedCornerShape(30.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(22.dp))
        Text(label, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, maxLines = 1)
    }
}

@Composable
private fun Chevron(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .size(DashSize.TouchPrimary)
            .clip(CircleShape)
            .background(DashColors.Card.copy(alpha = 0.85f))
            .border(1.dp, DashColors.Line, CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = DashColors.TextSecondary, modifier = Modifier.size(32.dp))
    }
}

/** A dashboard beside Home, turned away from the middle and dimmed, with its name. */
@Composable
private fun SidePage(
    items: List<DashboardItem>,
    name: String,
    pageSize: DpSize,
    width: Dp,
    modifier: Modifier,
    tile: @Composable (DashboardItem) -> Unit,
    turn: Float
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.graphicsLayer {
            rotationY = turn
            cameraDistance = 14f * density
            alpha = 0.55f
        }
    ) {
        PagePreview(items, pageSize, width, tile)
        Text(
            name,
            color = DashColors.TextSecondary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/**
 * One dashboard drawn at [width]: laid out at its real [pageSize] with the
 * dashboard's own tiles, then scaled down, so what is shown is what comes.
 * Takes no touches: the tiles inside are pictures here.
 */
@Composable
internal fun PagePreview(
    items: List<DashboardItem>,
    pageSize: DpSize,
    width: Dp,
    tile: @Composable (DashboardItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val scale = (width / pageSize.width).coerceIn(0.05f, 1f)
    Box(
        modifier = modifier
            .size(width, width * (pageSize.height / pageSize.width))
            .clip(DashShape.Medium)
            .background(DashColors.Background.copy(alpha = 1f))
    ) {
        Box(
            modifier = Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .requiredSize(pageSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                }
                .padding(6.dp)
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val cellW = maxWidth / GRID_COLS
                val cellH = maxHeight / GRID_ROWS
                items.forEach { item ->
                    Box(
                        modifier = Modifier
                            .offset(cellW * item.x, cellH * item.y)
                            .size(cellW * item.w, cellH * item.h)
                            .padding(3.dp)
                    ) {
                        if (item is DashboardItem.BuiltinWidget && item.kind in MAP_KINDS) MapPicture() else TileZoom(item.zoom) { tile(item) }
                    }
                }
            }
        }
        // Nothing inside is pressed: a preview is a picture. The touches are
        // only kept from the tiles under it, not used, so a swipe across still swipes.
        Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
    }
}

private val MAP_KINDS = setOf(BuiltinKind.NAVMAP, BuiltinKind.MAPS_INSIDE, BuiltinKind.PIP_ANCHOR)

/** A tile a preview shows by its stand-in: it would open an app, host a widget or start a 3D view. */
internal fun DashboardItem.standsIn(): Boolean = when (this) {
    is DashboardItem.AppWindow, is DashboardItem.SystemWidget -> true
    is DashboardItem.BuiltinWidget -> kind == BuiltinKind.MY_CAR || kind in MAP_KINDS
    else -> false
}

/** The map tile in a preview: streets and a lit route, not a second live map. */
@Composable
private fun MapPicture() {
    val accent = DashColors.Accent
    val street = DashColors.TextSecondary.copy(alpha = 0.16f)
    val lane = DashColors.TextSecondary.copy(alpha = 0.08f)
    Canvas(
        Modifier
            .fillMaxSize()
            .clip(DashShape.Large)
            .background(Brush.verticalGradient(listOf(DashColors.Card, DashColors.Background)))
    ) {
        val w = size.width
        val h = size.height
        val s = (w + h) / 2f
        for (i in 1..4) drawLine(street, Offset(-10f, h * i / 5f + 10f), Offset(w + 10f, h * i / 5f - 20f), s * 0.025f)
        for (i in 1..4) drawLine(street, Offset(w * i / 5f + 15f, -10f), Offset(w * i / 5f - 15f, h + 10f), s * 0.025f)
        for (i in 1..6) drawLine(lane, Offset(w * i / 7f, -10f), Offset(w * i / 7f + 8f, h + 10f), s * 0.01f)
        val route = Path().apply {
            moveTo(w * 0.5f, h)
            lineTo(w * 0.49f, h * 0.6f)
            lineTo(w * 0.78f, h * 0.56f)
            lineTo(w * 0.75f, h * 0.1f)
        }
        drawPath(route, accent.copy(alpha = 0.35f), style = Stroke(width = s * 0.07f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(route, accent, style = Stroke(width = s * 0.025f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(accent.copy(alpha = 0.3f), radius = s * 0.05f, center = Offset(w * 0.5f, h * 0.88f))
        drawCircle(Color.White, radius = s * 0.018f, center = Offset(w * 0.5f, h * 0.88f))
    }
}

/** A template's card in the strip: its Home as coloured blocks, its name and what it is for. */
@Composable
private fun TemplateCard(
    template: DashTemplate,
    screen: TemplateScreen,
    page: Int,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    val home = remember(template, screen, page) { TemplatePlacer.pages(template, screen, setOf(page))[page] }
    val colors = categoryColors()
    val pageBg = DashColors.CardHi
    val pageAspect = (screen.cellAspect * GRID_COLS / GRID_ROWS).toFloat().coerceIn(0.4f, 4f)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (selected) DashColors.Accent.copy(alpha = 0.14f) else DashColors.Card)
            .border(if (selected) 2.dp else 1.dp, if (selected) DashColors.Accent else DashColors.Line, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Canvas(Modifier.width(76.dp).aspectRatio(pageAspect)) {
                drawRoundRect(pageBg, cornerRadius = CornerRadius(4f))
                home.forEach { drawTile(it, Offset.Zero, size.width, size.height, colors) }
            }
            Text(
                stringResource(template.titleRes),
                color = DashColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            stringResource(template.blurbRes),
            color = DashColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(40.dp)
        )
    }
}
