package com.openauto.dash

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.filled.Image
import androidx.compose.ui.viewinterop.AndroidView

/*
 * Settings, Look, rebuilt for thirty and more looks. On top, the look in use
 * drawn large, the Home it brings beside it and the switch that decides
 * whether choosing a look arranges Home. Below, the looks by family: one row
 * of big family chips and the looks of the open family as cards, so the
 * driver sees a dozen at most and never scrolls past thirty. A card shows the
 * look's stamp, its name and, when the look brings its own Home, the same
 * Home icon as the switch.
 */

/** The families the looks are sorted into on the Look page, in the order of their chips. */
internal enum class LookFamily(@StringRes val titleRes: Int) {
    /** The latest looks. A look leaves for its own family once the next ones arrive. */
    NEW(R.string.look_family_new),
    /** A world behind the dashboard: a city, a sky, a neon night, a comic. */
    SCENES(R.string.look_family_scenes),
    /** Dials and gauges up front, as in a car's cluster. */
    DIALS(R.string.look_family_dials),
    /** Little on screen: the road, the map, big figures. */
    CALM(R.string.look_family_calm),
    /** Colour-only themes over the standard tiles; they never change Home. */
    COLOURS(R.string.look_family_colours)
}

/**
 * Every look in its family, in the order shown. A new look goes into
 * [LookFamily.NEW]; LookPageTest fails while a look is missing here or sits
 * in two families, and while a colour-only look is outside [LookFamily.COLOURS].
 */
internal val LookFamilies: Map<LookFamily, List<DashThemeMode>> = linkedMapOf(
    LookFamily.NEW to listOf(
        DashThemeMode.LIQUID, DashThemeMode.EXPRESSIVE, DashThemeMode.KINETIC,
        DashThemeMode.GLYPH, DashThemeMode.WINDSHIELD, DashThemeMode.TERRAIN,
        DashThemeMode.AMBIENT, DashThemeMode.BRUTAL, DashThemeMode.CLAY,
        DashThemeMode.CHROME, DashThemeMode.SPATIAL, DashThemeMode.PAPER
    ),
    LookFamily.SCENES to listOf(
        DashThemeMode.MODERN, DashThemeMode.FUTURISTIC, DashThemeMode.RACING,
        DashThemeMode.TAPE_DECK, DashThemeMode.ANIME
    ),
    LookFamily.DIALS to listOf(
        DashThemeMode.COCKPIT, DashThemeMode.ORBIT, DashThemeMode.MOTORSPORT, DashThemeMode.PANORAMA
    ),
    LookFamily.CALM to listOf(
        DashThemeMode.SIMPLE, DashThemeMode.HORIZON, DashThemeMode.CANVAS
    ),
    LookFamily.COLOURS to listOf(
        DashThemeMode.AUTO, DashThemeMode.ORIGINAL, DashThemeMode.AURORA, DashThemeMode.DARK_GLASS,
        DashThemeMode.NEON_DARK, DashThemeMode.SPORTY, DashThemeMode.CYBER_SPORT, DashThemeMode.FLOATING, DashThemeMode.OPEN,
        DashThemeMode.CLEAN_LIGHT, DashThemeMode.NORDIC, DashThemeMode.ZENITH, DashThemeMode.MISTRAL,
        DashThemeMode.LUXURY, DashThemeMode.ECO_LEAF
    )
)

/** The family [mode] is shown in; a look the table forgot shows with the colours rather than nowhere. */
internal fun lookFamily(mode: DashThemeMode): LookFamily =
    LookFamilies.entries.firstOrNull { mode in it.value }?.key ?: LookFamily.COLOURS

/** The looks of [family]; a forgotten look joins the colours so it can still be chosen. */
internal fun looksIn(family: LookFamily): List<DashThemeMode> {
    val listed = LookFamilies[family].orEmpty()
    if (family != LookFamily.COLOURS) return listed
    val everywhere = LookFamilies.values.flatten().toSet()
    return listed + DashThemeMode.entries.filter { it !in everywhere }
}

/** Below this the page is laid out for an upright screen: the preview stacked, two or three cards a row. */
private val LOOK_WIDE = 640.dp

/** The narrowest a look's card gets; the cards of a row share what is left. */
private val LOOK_CARD_MIN = 200.dp

private val LOOK_GAP = 12.dp

/**
 * The look chooser: the look in use with its Home and the "Arrange Home like
 * the look" switch, then the family chips and the open family's looks.
 */
@Composable
internal fun LookPicker(theme: ThemeState) {
    val context = LocalContext.current
    var arrange by remember { mutableStateOf(DashThemeStore.loadLookLayout(context)) }
    // Opens on the family of the look in use; the driver's pick holds while the page is open.
    var family by rememberSaveable { mutableStateOf(lookFamily(theme.mode)) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= LOOK_WIDE
        val columns = ((maxWidth + LOOK_GAP) / (LOOK_CARD_MIN + LOOK_GAP)).toInt().coerceIn(2, 5)
        Column(Modifier.fillMaxWidth()) {
            SettingsSection(stringResource(R.string.dash_theme_picker_title))
            LookHero(theme.mode, arrange, wide)
            Spacer(Modifier.height(8.dp))
            SettingsToggle(
                icon = Icons.Filled.Dashboard,
                title = stringResource(R.string.look_arrange_home),
                detail = stringResource(R.string.look_arrange_home_detail),
                checked = arrange
            ) {
                arrange = it
                DashThemeStore.saveLookLayout(context, it)
                // Switched on: the look in use arranges Home now, with Undo.
                if (it) theme.onArrangeHome()
            }
            if (theme.mode == DashThemeMode.CANVAS) {
                Spacer(Modifier.height(16.dp))
                CanvasMapSetting()
            }
            Spacer(Modifier.height(16.dp))
            FamilyChips(family, lookFamily(theme.mode)) { family = it }
            Spacer(Modifier.height(LOOK_GAP))
            looksIn(family).chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(LOOK_GAP), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { mode ->
                        LookCard(mode, mode == theme.mode, Modifier.weight(1f)) { theme.onMode(mode) }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(LOOK_GAP))
            }
        }
    }
}

/**
 * The look in use, large: its stamp, its name and what it is, and the Home it
 * brings as a small picture of its tiles (or that Home stays as it is).
 * Side by side on a wide screen, stacked upright.
 */
@Composable
private fun LookHero(mode: DashThemeMode, arrange: Boolean, wide: Boolean) {
    val palette = paletteFor(mode, light = DashColors.Light)
    val stamp = Modifier
        .clip(DashShape.Medium)
        .border(1.dp, DashColors.Line, DashShape.Medium)
    if (wide) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            ThemeStamp(palette, stamp.width(360.dp).aspectRatio(2f))
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                LookName(mode)
                Spacer(Modifier.height(14.dp))
                LookHomeLine(mode, arrange)
            }
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            ThemeStamp(palette, stamp.fillMaxWidth().aspectRatio(2f))
            Spacer(Modifier.height(12.dp))
            LookName(mode)
            Spacer(Modifier.height(12.dp))
            LookHomeLine(mode, arrange)
        }
    }
}

@Composable
private fun LookName(mode: DashThemeMode) {
    Text(
        stringResource(mode.titleRes),
        color = DashColors.TextPrimary,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.headlineSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(mode.descriptionRes),
        color = DashColors.TextSecondary,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
    )
}

/** The Home [mode] brings, drawn as its tiles, and a line saying whether choosing it arranges Home. */
@Composable
private fun LookHomeLine(mode: DashThemeMode, arrange: Boolean) {
    val home = rememberLookHome(mode)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (home != null) {
            val (items, aspect) = home
            val colors = categoryColors()
            val page = DashColors.CardHi
            Canvas(
                Modifier
                    .width(if (aspect >= 1f) 150.dp else 84.dp)
                    .aspectRatio(aspect)
                    .clip(DashShape.Small)
            ) {
                drawRoundRect(page, cornerRadius = CornerRadius(6f))
                items.forEach { drawTile(it, Offset.Zero, size.width, size.height, colors) }
            }
        } else {
            Box(
                Modifier
                    .size(DashSize.TouchPrimary)
                    .clip(DashShape.Small)
                    .background(DashColors.CardHi),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Palette, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(
            stringResource(
                when {
                    home == null -> R.string.look_home_keeps
                    arrange -> R.string.look_home_own
                    else -> R.string.look_home_own_off
                }
            ),
            color = if (home != null && arrange) DashColors.TextPrimary else DashColors.TextSecondary,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The tiles [mode]'s Home puts on Home here, as the dashboard would place
 * them (SkinLayouts.kt), with the page's shape; null for a look that leaves
 * Home alone or one that would place nothing.
 */
@Composable
private fun rememberLookHome(mode: DashThemeMode): Pair<List<DashboardItem>, Float>? {
    val layout = skinLayout(mode) ?: return null
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val adapter by ObdBluetoothManager.adapter.collectAsState()
    val access by PrivilegedShell.access.collectAsState()
    val vertical = ScreenShape.vertical
    val canDial = adapter.canDial
    val root = access.root
    val carKeys by UnitPlatform.supplied.collectAsState(initial = UnitPlatform.suppliedKeys())
    return remember(mode, config.screenWidthDp, config.screenHeightDp, vertical, canDial, root, carKeys) {
        // As the dashboard measures it (templateScreen): the Maps dock takes about half the width, or half the height upright.
        val half = DashLayoutStore.load(context) != DashLayout.GRID
        val dock = if (half) 0.5f else 1f
        val screen = TemplateScreen.of(
            pageWidthDp = config.screenWidthDp * if (vertical) 1f else dock,
            pageHeightDp = config.screenHeightDp * (if (vertical) 0.9f else 0.8f) * if (vertical) dock else 1f,
            obdPaired = canDial,
            carKeys = carKeys,
            driverOnRight = CarProfileStore.current.driverOnRight,
            mapsDocked = half,
            // The driver's own apps fill the dock row; one stands for them in the picture.
            dockApps = listOf(DOCK_STAND_IN),
            canbox = root,
            tyres = UnitPlatform.delivers(Cap.TYRES)
        )
        val items = runCatching { skinHome(layout, screen) }.getOrDefault(emptyList())
        val aspect = (screen.cellAspect * GRID_COLS / GRID_ROWS).toFloat().coerceIn(0.4f, 4f)
        if (items.isEmpty()) null else items to aspect
    }
}

/** Never launched: it only makes the dock row show in a Home's picture. */
private const val DOCK_STAND_IN = "dock"

/**
 * The families as big chips in one row (sliding sideways when the screen is
 * narrow), each with how many looks it holds. A dot marks the family of the
 * look in use while another one is open.
 */
@Composable
private fun FamilyChips(open: LookFamily, inUse: LookFamily, onOpen: (LookFamily) -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
    ) {
        LookFamily.entries.forEach { family ->
            val count = looksIn(family).size
            if (count == 0) return@forEach
            val picked = family == open
            val ink = if (picked) DashColors.OnAccent else DashColors.TextPrimary
            Row(
                modifier = Modifier
                    .heightIn(min = DashSize.TouchPrimary)
                    .clip(DashShape.Pill)
                    .then(
                        if (picked) Modifier.background(DashColors.AccentBrush, DashShape.Pill)
                        else Modifier.border(1.dp, DashColors.Line, DashShape.Pill)
                    )
                    .selectable(selected = picked, role = Role.Tab) { tap(); onOpen(family) }
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (family == inUse && !picked) {
                    Box(Modifier.size(8.dp).clip(DashShape.Pill).background(DashColors.Accent))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    stringResource(family.titleRes),
                    color = ink,
                    fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    count.toString(),
                    color = if (picked) DashColors.OnAccent.copy(alpha = 0.75f) else DashColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * One look in the grid: its stamp with the Home icon when it brings its own
 * Home, and its name. The description waits in the preview on top, so a card
 * stays small enough for four in a row.
 */
@Composable
private fun LookCard(mode: DashThemeMode, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = DashShape.Medium
    val tap = rememberTapFeedback()
    val palette = paletteFor(mode, light = DashColors.Light)
    Column(
        modifier = modifier
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) DashColors.Accent else DashColors.Line, shape)
            .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * if (selected) 0.65f else 0.3f))
            .selectable(selected = selected, role = Role.RadioButton) { tap(); onClick() }
            .padding(8.dp)
    ) {
        Box {
            ThemeStamp(palette, Modifier.fillMaxWidth().aspectRatio(2f).clip(DashShape.Small))
            if (skinLayout(mode) != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(28.dp)
                        .clip(DashShape.Small)
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Dashboard, contentDescription = stringResource(R.string.look_home_own), tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 4.dp, top = 6.dp)
        ) {
            Text(
                stringResource(mode.titleRes),
                color = DashColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (selected) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.dash_selected), tint = DashColors.Accent, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * Day and night beside the effects on a wide screen, one under the other
 * upright, then the font: the settings that dress every look.
 */
@Composable
internal fun LookLightAndEffects(theme: ThemeState) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= LOOK_WIDE) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f)) { AppearanceSetting(theme) }
                Column(Modifier.weight(1f)) { EffectsSetting(theme) }
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                AppearanceSetting(theme)
                Spacer(Modifier.height(20.dp))
                EffectsSetting(theme)
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    FontSetting()
}

/**
 * The font of every text ([DashThemeStore.font]): each look's own face, or
 * one of the system's for the whole app. Each chip is set in its own face.
 */
@Composable
private fun FontSetting() {
    val context = LocalContext.current
    val chosen = DashThemeStore.font
    val tap = rememberTapFeedback()
    SettingsSection(stringResource(R.string.look_font_title))
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
    ) {
        (listOf(null) + DashFont.entries).forEach { font ->
            val picked = font == chosen
            Box(
                modifier = Modifier
                    .heightIn(min = DashSize.TouchPrimary)
                    .clip(DashShape.Pill)
                    .then(
                        if (picked) Modifier.background(DashColors.AccentBrush, DashShape.Pill)
                        else Modifier.border(1.dp, DashColors.Line, DashShape.Pill)
                    )
                    .selectable(selected = picked, role = Role.RadioButton) { tap(); DashThemeStore.saveFont(context, font) }
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (font == null) stringResource(R.string.look_font_own) else stringResource(font.titleRes),
                    color = if (picked) DashColors.OnAccent else DashColors.TextPrimary,
                    fontFamily = font?.family ?: FontFamily.Default,
                    fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1
                )
            }
        }
    }
}

/** The moment each start-up animation is drawn at on its card: where it looks most like itself. */
private fun StartAnimation.cardMoment(): Float = when (this) {
    StartAnimation.IGNITION -> 1.1f
    StartAnimation.ASSEMBLE, StartAnimation.BLUEPRINT -> 3.5f
    StartAnimation.WELCOME -> 4f
    else -> 3f
}

/**
 * The animation Dashwheel starts with (StartSplash): a card each, drawn at
 * its best moment. Choosing one plays it full screen, as the next start will.
 */
@Composable
internal fun StartAnimationSetting() {
    val context = LocalContext.current
    val chosen by StartSplash.chosen.collectAsState()
    SettingsSection(stringResource(R.string.start_anim_title))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StartAnimation.entries.forEach { animation ->
            StartAnimationCard(animation, animation == chosen) {
                StartSplash.save(context, animation)
                context.findActivity()?.let { MainScreenCover.tryOut(it, animation) }
            }
        }
    }
    SwitchHint(stringResource(R.string.start_anim_detail))
}

@Composable
private fun StartAnimationCard(animation: StartAnimation, selected: Boolean, onClick: () -> Unit) {
    val shape = DashShape.Medium
    val tap = rememberTapFeedback()
    Column(
        modifier = Modifier
            .width(210.dp)
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) DashColors.Accent else DashColors.Line, shape)
            .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * if (selected) 0.65f else 0.3f))
            .selectable(selected = selected, role = Role.RadioButton) { tap(); onClick() }
            .padding(8.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(DashShape.Small).background(Color(0xFF0B0D12)),
            contentAlignment = Alignment.Center
        ) {
            if (animation == StartAnimation.STILL) {
                Icon(Icons.Filled.Image, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(36.dp))
            } else {
                AndroidView(
                    factory = { StartSplashView(it, animation, frozenAt = animation.cardMoment(), sample = true) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 4.dp, top = 6.dp)
        ) {
            Text(
                stringResource(animation.titleRes),
                color = DashColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (selected) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.dash_selected), tint = DashColors.Accent, modifier = Modifier.size(22.dp))
        }
    }
}
