package com.openauto.dash

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * The dashboard design. Every theme comes in a dark and a light version; which
 * one shows is picked by [DashAppearance]. Enum names are persisted, so the old
 * names (AUTO, NEON_DARK, CLEAN_LIGHT, DARK_GLASS) stay even where the title moved on.
 */
enum class DashThemeMode(@StringRes val titleRes: Int, @StringRes val descriptionRes: Int) {
    AUTO(R.string.dash_theme_standard, R.string.dash_theme_standard_desc),
    ORIGINAL(R.string.dash_theme_original, R.string.dash_theme_original_desc),
    AURORA(R.string.dash_theme_aurora, R.string.dash_theme_aurora_desc),
    NEON_DARK(R.string.dash_theme_neon, R.string.dash_theme_neon_desc),
    CLEAN_LIGHT(R.string.dash_theme_clean, R.string.dash_theme_clean_desc),
    DARK_GLASS(R.string.dash_theme_premium_glass, R.string.dash_theme_premium_glass_desc),
    SPORTY(R.string.dash_theme_sporty, R.string.dash_theme_sporty_desc),
    FLOATING(R.string.dash_theme_floating, R.string.dash_theme_floating_desc),
    ORBIT(R.string.dash_theme_orbit, R.string.dash_theme_orbit_desc),
    COCKPIT(R.string.dash_theme_cockpit, R.string.dash_theme_cockpit_desc),
    HORIZON(R.string.dash_theme_horizon, R.string.dash_theme_horizon_desc),
    TAPE_DECK(R.string.dash_theme_tape_deck, R.string.dash_theme_tape_deck_desc),
    MISTRAL(R.string.dash_theme_mistral, R.string.dash_theme_mistral_desc),
    ZENITH(R.string.dash_theme_zenith, R.string.dash_theme_zenith_desc),
    LUXURY(R.string.dash_theme_luxury, R.string.dash_theme_luxury_desc),
    ECO_LEAF(R.string.dash_theme_eco_leaf, R.string.dash_theme_eco_leaf_desc),
    CYBER_SPORT(R.string.dash_theme_cyber, R.string.dash_theme_cyber_desc),
    NORDIC(R.string.dash_theme_nordic, R.string.dash_theme_nordic_desc),
    CANVAS(R.string.dash_theme_canvas, R.string.dash_theme_canvas_desc),
    FUTURISTIC(R.string.dash_theme_futuristic, R.string.dash_theme_futuristic_desc),
    SIMPLE(R.string.dash_theme_simple, R.string.dash_theme_simple_desc),
    MODERN(R.string.dash_theme_modern, R.string.dash_theme_modern_desc),
    MOTORSPORT(R.string.dash_theme_motorsport, R.string.dash_theme_motorsport_desc),
    ANIME(R.string.dash_theme_anime, R.string.dash_theme_anime_desc),
    RACING(R.string.dash_theme_racing, R.string.dash_theme_racing_desc),
    PANORAMA(R.string.dash_theme_panorama, R.string.dash_theme_panorama_desc),
    // The 2026-10-13 looks, after that year's car and phone design (docs: D:/dashwheel-design/v16-trends).
    LIQUID(R.string.dash_theme_liquid, R.string.dash_theme_liquid_desc),
    EXPRESSIVE(R.string.dash_theme_expressive, R.string.dash_theme_expressive_desc),
    KINETIC(R.string.dash_theme_kinetic, R.string.dash_theme_kinetic_desc),
    GLYPH(R.string.dash_theme_glyph, R.string.dash_theme_glyph_desc),
    WINDSHIELD(R.string.dash_theme_windshield, R.string.dash_theme_windshield_desc),
    TERRAIN(R.string.dash_theme_terrain, R.string.dash_theme_terrain_desc),
    // The 2026-10-13 looks that need no car picture (D:/dashwheel-design/v17-no-car).
    AMBIENT(R.string.dash_theme_ambient, R.string.dash_theme_ambient_desc),
    BRUTAL(R.string.dash_theme_brutal, R.string.dash_theme_brutal_desc),
    CLAY(R.string.dash_theme_clay, R.string.dash_theme_clay_desc),
    CHROME(R.string.dash_theme_chrome, R.string.dash_theme_chrome_desc),
    SPATIAL(R.string.dash_theme_spatial, R.string.dash_theme_spatial_desc),
    PAPER(R.string.dash_theme_paper, R.string.dash_theme_paper_desc),
    OPEN(R.string.dash_theme_open, R.string.dash_theme_open_desc)
}

/** Dark or light version of the theme; [AUTO] follows the car's day/night mode. */
enum class DashAppearance(@StringRes val titleRes: Int) {
    AUTO(R.string.dash_appearance_auto),
    DARK(R.string.dash_appearance_dark),
    LIGHT(R.string.dash_appearance_light)
}

/**
 * How much of a theme's decoration is drawn. Every theme keeps its colours;
 * [NONE] is the high-legibility setting for a dim screen in full sun: opaque
 * cards, plain numerals, no halos. [scale] multiplies the theme's glow.
 */
enum class DashEffects(@StringRes val titleRes: Int, @StringRes val hintRes: Int, val scale: Float) {
    NONE(R.string.dash_effects_none, R.string.dash_effects_none_hint, 0f),
    REDUCED(R.string.dash_effects_reduced, R.string.dash_effects_reduced_hint, 0.5f),
    FULL(R.string.dash_effects_full, R.string.dash_effects_full_hint, 1f)
}

/**
 * Whole-design variants. A skin swaps more than colours: its own page
 * background, top bar and renderers for the main widgets (see Skins.kt).
 * [STANDARD] is every colour-only theme.
 */
enum class DashSkin { STANDARD, ORBIT, COCKPIT, HORIZON, TAPE_DECK, CANVAS, FUTURISTIC, SIMPLE, MODERN, MOTORSPORT, ANIME, RACING, PANORAMA, LIQUID, EXPRESSIVE, KINETIC, GLYPH, WINDSHIELD, TERRAIN, AMBIENT, BRUTAL, CLAY, CHROME, SPATIAL, PAPER }

/**
 * Colours plus a few style knobs for one dashboard theme.
 *
 * Roles: [Accent] is the theme's ink for anything live (speed, controls),
 * [Accent2] the far end of its gradient, [Secondary] its second colour (the
 * media and info tiles' contrast colour). [Tacho] is amber in every theme: the
 * tachometer, fuel and coolant running low or hot; [Warning] (amber, [Tacho]
 * unless a theme says otherwise) marks a reading out of range and [Critical]
 * (red) one that needs the car stopped. [Good] is green. Keeping the alert
 * colours fixed across themes is what lets the driver read them at a glance.
 *
 * [Glass] switches cards to translucent gradient panels with a hairline border
 * and a specular top edge over a gradient background. [Glow] (0..1) scales the
 * halo drawn behind gauges, readouts and primary controls; light themes keep it
 * low so nothing smears in sunlight. [Original] swaps the
 * media, telemetry, gauge and meter-chip widgets back to their first designs
 * (see OriginalTiles.kt). [Bare] drops the tile cards and the fills
 * behind icons, chips and list rows (see itemFill), so content sits straight on
 * the page background; Card / CardHi still colour dialogs and buttons. [Skin]
 * picks a whole-design variant; skins are bare, their widgets draw their own shapes.
 * [Open] redraws every built-in widget in [WidgetDesign.OPEN] unless the tile picked a design of its own.
 * [Light] marks the day version of a theme: dark text on a pale page, so
 * anything that brightens with white (glass haze, highlights) must darken instead.
 * Immutable: every field is a val, and [AccentInk] is worked out once from them.
 */
@androidx.compose.runtime.Immutable
data class DashPalette(
    val Background: Color, val Bar: Color, val Card: Color, val CardHi: Color,
    val Accent: Color, val Secondary: Color, val Critical: Color,
    val Good: Color, val Muted: Color, val TextPrimary: Color, val TextSecondary: Color,
    val Accent2: Color = Accent,
    val Line: Color = Color.White.copy(alpha = 0.10f),
    val Glass: Boolean = false,
    val Glow: Float = 0f,
    val Original: Boolean = false,
    val Bare: Boolean = false,
    val BackgroundStops: List<Color> = listOf(Background, Background),
    val Skin: DashSkin = DashSkin.STANDARD,
    val Light: Boolean = false,
    val Tacho: Color = if (Light) AmberDay else AmberNight,
    val Warning: Color = Tacho,
    /** The face of the hero numerals (speed, clock) and how heavy they are. */
    val Font: DashFont = DashFont.SANS,
    val HeroWeight: FontWeight = FontWeight.ExtraBold,
    /** What the middle of the standard bar shows. */
    val BarStyle: DashBarStyle = DashBarStyle.STANDARD,
    val Open: Boolean = false
) {
    /**
     * [Accent] for words (hints, links, a tile's "tap to…"): the accent itself
     * where it reads at 4.5:1 on every colour of the page, else the accent
     * moved toward [TextPrimary] until it does. Bright accents fill and glow
     * well but wash out as text on a pale page in the sun.
     */
    val AccentInk: Color by lazy {
        val pages = listOf(Background) + BackgroundStops
        var t = 0f
        var ink = Accent
        while (t < 1f && pages.any { contrastRatio(ink.compositeOver(it), it) < TEXT_CONTRAST }) {
            t += 0.05f
            ink = lerp(Accent, TextPrimary, t.coerceAtMost(1f))
        }
        ink
    }

    /**
     * Text on [Accent] → [Accent2] (buttons, picked options): white or near
     * black, whichever reads better on the worse end of the gradient. A bright
     * accent (cyan, sky blue, yellow) needs dark words, by day and by night.
     */
    val OnAccentInk: Color by lazy {
        val ends = listOf(Accent, Accent2)
        listOf(Color.White, NearBlack).maxBy { ink -> ends.minOf { contrastRatio(ink, it.copy(alpha = 1f)) } }
    }
}

private val NearBlack = Color(0xFF0B0F14)

/** WCAG AA for body text. */
internal const val TEXT_CONTRAST = 4.5f

/** WCAG contrast ratio of [a] against [b], 1 to 21. */
internal fun contrastRatio(a: Color, b: Color): Float {
    val hi = maxOf(a.luminance(), b.luminance())
    val lo = minOf(a.luminance(), b.luminance())
    return (hi + 0.05f) / (lo + 0.05f)
}

/**
 * A theme's hero face: the system sans, or its condensed cut (a cluster's
 * numerals). The rest are the driver's own picks for every text (Settings →
 * Look, [DashThemeStore.font]); each is a face the system ships, no asset bundled.
 */
enum class DashFont(@StringRes val titleRes: Int, private val system: String?) {
    SANS(R.string.look_font_sans, null),
    CONDENSED(R.string.look_font_condensed, "sans-serif-condensed"),
    SERIF(R.string.look_font_serif, "serif"),
    MONO(R.string.look_font_mono, "monospace"),
    CASUAL(R.string.look_font_casual, "casual"),
    LIGHT(R.string.look_font_light, "sans-serif-light");

    /** The face as a font family, built once; a unit without it draws the default sans. */
    val family: FontFamily by lazy {
        when {
            system == null -> FontFamily.Default
            this == CONDENSED -> CondensedFamily
            else -> FontFamily(android.graphics.Typeface.create(system, android.graphics.Typeface.NORMAL))
        }
    }
}

/** The standard bar's centre: the clock, or a car-style cluster (speed, revs, fuel and temperature). */
enum class DashBarStyle { STANDARD, CLUSTER }

/** The one amber: deep enough to read on a pale page by day, bright by night. */
internal val AmberNight = Color(0xFFF2A33A)
internal val AmberDay = Color(0xFFB86E0E)

private val AutoDarkPalette = DashPalette(
    Background = Color(0xFF0B0C0F), Bar = Color(0xFF141518), Card = Color(0xFF1E2024), CardHi = Color(0xFF2A2D33),
    Accent = Color(0xFF8AB4F8), Secondary = Color(0xFFF6AD7B), Critical = Color(0xFFF28B82),
    Good = Color(0xFF81C995), Muted = Color(0xFF9AA0A6), TextPrimary = Color(0xFFE8EAED), TextSecondary = Color(0xFF9AA0A6),
    Accent2 = Color(0xFFC58AF9), Glow = 0.35f
)
private val AutoLightPalette = DashPalette(
    Background = Color(0xFFF1F3F4), Bar = Color.White, Card = Color.White, CardHi = Color(0xFFE3E6EA),
    Accent = Color(0xFF1A73E8), Secondary = Color(0xFFE8710A), Critical = Color(0xFFD93025),
    Good = Color(0xFF188038), Muted = Color(0xFF5F6368), TextPrimary = Color(0xFF202124), TextSecondary = Color(0xFF5F6368),
    Accent2 = Color(0xFF7B4DFF), Line = Color.Black.copy(alpha = 0.08f), Light = true
)
// Original: the pre-Aurora day/night palettes with no glow, no accent gradient
// and no rim lines, so shared components render exactly as they first did.
private val OriginalDarkPalette = AutoDarkPalette.copy(
    Accent2 = AutoDarkPalette.Accent, Line = Color.Transparent, Glow = 0f, Original = true
)
private val OriginalLightPalette = AutoLightPalette.copy(
    Accent2 = AutoLightPalette.Accent, Line = Color.Transparent, Glow = 0f, Original = true
)
private val AuroraPalette = DashPalette(
    Background = Color(0xFF080D1C), Bar = Color(0xCC0B1226), Card = Color(0xE60E1730), CardHi = Color(0x24FFFFFF),
    Accent = Color(0xFF5AD0FF), Secondary = Color(0xFF4EE3A5), Critical = Color(0xFFFF5D7A),
    Good = Color(0xFF4EE3A5), Muted = Color(0xFF8593B3), TextPrimary = Color(0xFFF2F7FF), TextSecondary = Color(0xFFAEBBD6),
    Accent2 = Color(0xFF9B7BFF), Line = Color.White.copy(alpha = 0.12f), Glass = true, Glow = 1f,
    BackgroundStops = listOf(Color(0xFF0E1730), Color(0xFF080D1C), Color(0xFF130F2C))
)
// Light glass: frosted white panels over a pale blue -> lilac wash.
private val AuroraLightPalette = DashPalette(
    Background = Color(0xFFF3F6FC), Bar = Color(0xCCFFFFFF), Card = Color(0xF2FFFFFF), CardHi = Color(0x140E1630),
    Accent = Color(0xFF0092D6), Secondary = Color(0xFF0E9F6A), Critical = Color(0xFFE0344F),
    Good = Color(0xFF0E9F6A), Muted = Color(0xFF626D8A), TextPrimary = Color(0xFF0E1630), TextSecondary = Color(0xFF4D5B7A),
    Accent2 = Color(0xFF7B5CF0), Line = Color(0x1A0E1630), Glass = true, Glow = 0.3f,
    BackgroundStops = listOf(Color(0xFFE6F0FF), Color(0xFFF6F8FC), Color(0xFFEFE9FF)), Light = true
)
private val NeonDarkPalette = DashPalette(
    Background = Color(0xFF030817), Bar = Color(0xFF071126), Card = Color(0xFF0A1935), CardHi = Color(0xFF13294A),
    Accent = Color(0xFF4B9BFF), Secondary = Color(0xFFA46BFF), Critical = Color(0xFFFF5F72),
    Good = Color(0xFF36E0A0), Muted = Color(0xFF8292B0), TextPrimary = Color(0xFFF4F7FF), TextSecondary = Color(0xFFB5C0D6),
    Accent2 = Color(0xFFA46BFF), Glow = 0.8f,
    BackgroundStops = listOf(Color(0xFF050C22), Color(0xFF030817))
)
private val NeonLightPalette = DashPalette(
    Background = Color(0xFFF4F6FF), Bar = Color.White, Card = Color.White, CardHi = Color(0xFFE6EBFA),
    Accent = Color(0xFF2F6BFF), Secondary = Color(0xFF8A4DFF), Critical = Color(0xFFE5364D),
    Good = Color(0xFF0FA36B), Muted = Color(0xFF656E8B), TextPrimary = Color(0xFF0A1330), TextSecondary = Color(0xFF4A5577),
    Accent2 = Color(0xFF8A4DFF), Line = Color.Black.copy(alpha = 0.08f), Glow = 0.2f,
    BackgroundStops = listOf(Color(0xFFEBF0FF), Color(0xFFF7F8FF)), Light = true
)
private val CleanLightPalette = DashPalette(
    Background = Color(0xFFF5F7FA), Bar = Color.White, Card = Color.White, CardHi = Color(0xFFEAF0F7),
    Accent = Color(0xFF246BCE), Secondary = Color(0xFF8A5A00), Critical = Color(0xFFC62828),
    Good = Color(0xFF177245), Muted = Color(0xFF667085), TextPrimary = Color(0xFF101828), TextSecondary = Color(0xFF667085),
    Accent2 = Color(0xFF6A4FD8), Line = Color.Black.copy(alpha = 0.08f), Light = true
)
private val CleanDarkPalette = DashPalette(
    Background = Color(0xFF0F1115), Bar = Color(0xFF16191E), Card = Color(0xFF1B1F25), CardHi = Color(0xFF262B33),
    Accent = Color(0xFF6AA3F0), Secondary = Color(0xFFE0A33A), Critical = Color(0xFFFF6B6B),
    Good = Color(0xFF4CC38A), Muted = Color(0xFF8A93A3), TextPrimary = Color(0xFFF2F4F7), TextSecondary = Color(0xFFA3ABB9),
    Accent2 = Color(0xFF9A86F0), Line = Color.White.copy(alpha = 0.08f)
)
private val DarkGlassPalette = DashPalette(
    Background = Color(0xFF05070B), Bar = Color(0xCC101722), Card = Color(0xCC101A2A), CardHi = Color(0xCC1B2A42),
    Accent = Color(0xFF68A8FF), Secondary = Color(0xFF9C7BFF), Critical = Color(0xFFFF6B7A),
    Good = Color(0xFF55D6A5), Muted = Color(0xFF8B98AD), TextPrimary = Color(0xFFF7F9FC), TextSecondary = Color(0xFFB6C0D0),
    Accent2 = Color(0xFF9C7BFF), Glass = true, Glow = 0.6f,
    BackgroundStops = listOf(Color(0xFF0A1220), Color(0xFF05070B), Color(0xFF120D24))
)
private val FrostedGlassPalette = DashPalette(
    Background = Color(0xFFF2F4F8), Bar = Color(0xCCFFFFFF), Card = Color(0xE6FFFFFF), CardHi = Color(0x140B1220),
    Accent = Color(0xFF2F72D6), Secondary = Color(0xFF7652E0), Critical = Color(0xFFD93A4C),
    Good = Color(0xFF15A06E), Muted = Color(0xFF646F84), TextPrimary = Color(0xFF0B1220), TextSecondary = Color(0xFF4B5567),
    Accent2 = Color(0xFF7652E0), Line = Color(0x1A0B1220), Glass = true, Glow = 0.2f,
    BackgroundStops = listOf(Color(0xFFE8EEF7), Color(0xFFF5F7FA), Color(0xFFEEEAF7)), Light = true
)
private val SportyPalette = DashPalette(
    Background = Color(0xFF07080A), Bar = Color(0xFF0D0F12), Card = Color(0xFF12161B), CardHi = Color(0xFF20262D),
    Accent = Color(0xFFFF334A), Secondary = Color(0xFFFF8A3D), Critical = Color(0xFFFF334A),
    Good = Color(0xFF4DDC7A), Muted = Color(0xFF8B929B), TextPrimary = Color(0xFFF6F7F9), TextSecondary = Color(0xFFB3B8C0),
    Accent2 = Color(0xFFFF8A3D), Glow = 0.6f,
    BackgroundStops = listOf(Color(0xFF0D0F12), Color(0xFF07080A))
)
private val SportyLightPalette = DashPalette(
    Background = Color(0xFFF5F5F6), Bar = Color.White, Card = Color.White, CardHi = Color(0xFFEBECEE),
    Accent = Color(0xFFE0162E), Secondary = Color(0xFFE8650F), Critical = Color(0xFFE0162E),
    Good = Color(0xFF1E9E4A), Muted = Color(0xFF6B7078), TextPrimary = Color(0xFF111317), TextSecondary = Color(0xFF555A63),
    Accent2 = Color(0xFFE8650F), Line = Color.Black.copy(alpha = 0.08f),
    BackgroundStops = listOf(Color.White, Color(0xFFECEDEF)), Light = true
)
// Floating: no cards, so the bar is transparent too and the backdrop is a calm
// gradient that text and gauges read on directly.
private val FloatingPalette = DashPalette(
    Background = Color(0xFF06080D), Bar = Color.Transparent, Card = Color(0xFF141A24), CardHi = Color(0xFF1F2733),
    Accent = Color(0xFF7CC4FF), Secondary = Color(0xFFFFB86B), Critical = Color(0xFFFF6B6B),
    Good = Color(0xFF5EE3A1), Muted = Color(0xFF8A94A6), TextPrimary = Color(0xFFF5F7FA), TextSecondary = Color(0xFFB4BCC8),
    Accent2 = Color(0xFFB38CFF), Glow = 0.5f, Bare = true,
    BackgroundStops = listOf(Color(0xFF0C1424), Color(0xFF06080D), Color(0xFF0E0B1C))
)
private val FloatingLightPalette = DashPalette(
    Background = Color(0xFFF4F6FA), Bar = Color.Transparent, Card = Color.White, CardHi = Color(0xFFE8ECF2),
    Accent = Color(0xFF1C7FD6), Secondary = Color(0xFFD9791A), Critical = Color(0xFFE04848),
    Good = Color(0xFF17A165), Muted = Color(0xFF646E80), TextPrimary = Color(0xFF0E141F), TextSecondary = Color(0xFF4E5868),
    Accent2 = Color(0xFF7B55E0), Line = Color.Black.copy(alpha = 0.08f), Glow = 0.15f, Bare = true,
    BackgroundStops = listOf(Color(0xFFE6EEFA), Color(0xFFF6F8FB), Color(0xFFEFEAFA)), Light = true
)
// Skins: Card / CardHi only colour dialogs, menus and buttons; tiles are bare.
private val OrbitPalette = DashPalette(
    Background = Color(0xFF0A0E1C), Bar = Color.Transparent, Card = Color(0xFF151B30), CardHi = Color(0x17FFFFFF),
    Accent = Color(0xFFFF7A59), Secondary = Color(0xFF3DDBC3), Critical = Color(0xFFFF5D7A),
    Good = Color(0xFF3DDBC3), Muted = Color(0xFF8A92B6), TextPrimary = Color(0xFFEEF1FF), TextSecondary = Color(0xFFAEB5D3),
    Accent2 = Color(0xFF8A7BFF), Line = Color.White.copy(alpha = 0.10f), Glow = 0.8f, Bare = true,
    Skin = DashSkin.ORBIT, Tacho = Color(0xFFFFB25A)
)
private val OrbitLightPalette = DashPalette(
    Background = Color(0xFFF4F1FA), Bar = Color.Transparent, Card = Color.White, CardHi = Color(0x14161A33),
    Accent = Color(0xFFF0603F), Secondary = Color(0xFF0E8F7C), Critical = Color(0xFFE0405F),
    Good = Color(0xFF0E8F7C), Muted = Color(0xFF666C8C), TextPrimary = Color(0xFF161A33), TextSecondary = Color(0xFF4A5075),
    Accent2 = Color(0xFF6B5CF0), Line = Color(0x1A161A33), Glow = 0.3f, Bare = true,
    Skin = DashSkin.ORBIT, Light = true
)
private val CockpitPalette = DashPalette(
    Background = Color(0xFF17130F), Bar = Color.Transparent, Card = Color(0xFF211B16), CardHi = Color(0xFF2E2620),
    Accent = Color(0xFFFF8A1F), Secondary = Color(0xFFFFB347), Critical = Color(0xFFFF4A1C),
    Good = Color(0xFF39D353), Muted = Color(0xFF918579), TextPrimary = Color(0xFFE9E1D3), TextSecondary = Color(0xFFCBBFAE),
    Accent2 = Color(0xFFFFB347), Line = Color.White.copy(alpha = 0.08f), Glow = 0.5f, Bare = true,
    Skin = DashSkin.COCKPIT, Tacho = Color(0xFFFFB347)
)
// Day cockpit: tan leather, ivory dial faces with black ink, the same chrome.
private val CockpitLightPalette = DashPalette(
    Background = Color(0xFFE3D5C1), Bar = Color.Transparent, Card = Color(0xFFF3EADC), CardHi = Color(0xFFE2D4BF),
    Accent = Color(0xFFD9660A), Secondary = Color(0xFFB9770E), Critical = Color(0xFFD23A12),
    Good = Color(0xFF1F9A3A), Muted = Color(0xFF685B4D), TextPrimary = Color(0xFF2A2119), TextSecondary = Color(0xFF5C4E40),
    Accent2 = Color(0xFFB9770E), Line = Color(0x1A2A2119), Glow = 0.1f, Bare = true,
    Skin = DashSkin.COCKPIT, Light = true, Tacho = Color(0xFF8F5A10)
)
private val HorizonPalette = DashPalette(
    Background = Color(0xFF0A0F2C), Bar = Color.Transparent, Card = Color(0xFF1B1537), CardHi = Color(0x24FFF3E6),
    Accent = Color(0xFFFFD6A0), Secondary = Color(0xFF9CF0C0), Critical = Color(0xFFFF5C48),
    Good = Color(0xFF9CF0C0), Muted = Color(0x99FFF3E6), TextPrimary = Color(0xFFFFF3E6), TextSecondary = Color(0xC7FFF3E6),
    Accent2 = Color(0xFFFF8F6B), Line = Color(0x33FFF3E6), Glow = 0.4f, Bare = true,
    Skin = DashSkin.HORIZON
)
// Day horizon: a bright noon scene, navy ink with a pale halo instead of a shadow.
private val HorizonLightPalette = DashPalette(
    Background = Color(0xFFCFE3F5), Bar = Color.Transparent, Card = Color(0xFFF8F4EC), CardHi = Color(0x1A1B2440),
    Accent = Color(0xFFB9531A), Secondary = Color(0xFF1E9C6A), Critical = Color(0xFFD9472B),
    Good = Color(0xFF1E9C6A), Muted = Color(0xAD1B2440), TextPrimary = Color(0xFF1B2440), TextSecondary = Color(0xC71B2440),
    Accent2 = Color(0xFFD9472B), Line = Color(0x331B2440), Glow = 0.2f, Bare = true,
    Skin = DashSkin.HORIZON, Light = true, Tacho = Color(0xFF9A5A08)
)
private val TapeDeckPalette = DashPalette(
    Background = Color(0xFF0D0221), Bar = Color.Transparent, Card = Color(0xFF1B1230), CardHi = Color(0xFF2A1F44),
    Accent = Color(0xFF05D9E8), Secondary = Color(0xFF3CFF8F), Critical = Color(0xFFFF2A6D),
    Good = Color(0xFF3CFF8F), Muted = Color(0xFF8A7FA8), TextPrimary = Color(0xFFEDEDF5), TextSecondary = Color(0xFFB9B3CF),
    Accent2 = Color(0xFFFF2A6D), Line = Color(0x59FF2A6D), Glow = 1f, Bare = true,
    Skin = DashSkin.TAPE_DECK, Tacho = Color(0xFFFFC233)
)
// Day tape deck: a pastel Miami-morning sky and a brushed-silver head unit.
private val TapeDeckLightPalette = DashPalette(
    Background = Color(0xFFFDEFF6), Bar = Color.Transparent, Card = Color.White, CardHi = Color(0xFFF1E4F0),
    Accent = Color(0xFF00A0B4), Secondary = Color(0xFF12B368), Critical = Color(0xFFE8175D),
    Good = Color(0xFF12B368), Muted = Color(0xFF72658A), TextPrimary = Color(0xFF2A0F45), TextSecondary = Color(0xFF5E4C78),
    Accent2 = Color(0xFFE8175D), Line = Color(0x40E8175D), Glow = 0.4f, Bare = true,
    Skin = DashSkin.TAPE_DECK, Light = true
)

// Mistral: the C4 Picasso's translucent central cluster. Smoked graphite,
// cold-white numerals under a blue backlight, amber for anything warming up,
// chevron red only for the critical. The day version is the same cluster in
// full sun, ink and page swapped.
private val MistralPalette = DashPalette(
    Background = Color(0xFF0C0F13), Bar = Color(0xFF13171C), Card = Color(0xE0171C22), CardHi = Color(0xFF1F252C),
    Accent = Color(0xFFDCE9F7), Secondary = Color(0xFF8FC3F0), Critical = Color(0xFFE1252B),
    Good = Color(0xFF6FD39A), Muted = Color(0xFF7C8794), TextPrimary = Color(0xFFF2F6FA), TextSecondary = Color(0xFFAEB8C4),
    Accent2 = Color(0xFF8FC3F0), Line = Color.White.copy(alpha = 0.10f), Glow = 0.45f,
    BackgroundStops = listOf(Color(0xFF12161B), Color(0xFF0A0D11)),
    Font = DashFont.CONDENSED, HeroWeight = FontWeight.SemiBold, BarStyle = DashBarStyle.CLUSTER
)
private val MistralLightPalette = DashPalette(
    Background = Color(0xFFE9EDF1), Bar = Color(0xFFF6F8FA), Card = Color.White, CardHi = Color(0xFFDDE3E9),
    Accent = Color(0xFF1F5F8F), Secondary = Color(0xFF4F9AD1), Critical = Color(0xFFB8161C),
    Good = Color(0xFF1E8A55), Muted = Color(0xFF5E6973), TextPrimary = Color(0xFF14181D), TextSecondary = Color(0xFF48525C),
    Accent2 = Color(0xFF4F9AD1), Line = Color.Black.copy(alpha = 0.08f),
    BackgroundStops = listOf(Color(0xFFF3F5F8), Color(0xFFE4E8EC)), Light = true,
    Font = DashFont.CONDENSED, HeroWeight = FontWeight.SemiBold, BarStyle = DashBarStyle.CLUSTER
)
// Zénith: the lounge cabin under the panoramic windscreen. Pearl grey page lit
// from above, white panels with an aluminium hairline, the cluster's deep blue
// as the only accent. The night version is the same cabin under its ambient light.
private val ZenithLightPalette = DashPalette(
    Background = Color(0xFFF1F3F5), Bar = Color(0xCCFFFFFF), Card = Color.White, CardHi = Color(0xFFE6EAEF),
    Accent = Color(0xFF2A6FB0), Secondary = Color(0xFF4FB3E8), Critical = Color(0xFFC8102E),
    Good = Color(0xFF2E8B57), Muted = Color(0xFF67707B), TextPrimary = Color(0xFF1B1F24), TextSecondary = Color(0xFF4A525C),
    Accent2 = Color(0xFF4FB3E8), Line = Color(0x1A1B1F24),
    BackgroundStops = listOf(Color(0xFFF8FAFC), Color(0xFFEEF1F4), Color(0xFFE4E9EE)), Light = true,
    HeroWeight = FontWeight.Medium
)
private val ZenithDarkPalette = DashPalette(
    Background = Color(0xFF15181C), Bar = Color(0xFF1B1F24), Card = Color(0xFF20252B), CardHi = Color(0xFF2A3037),
    Accent = Color(0xFF6FB2E8), Secondary = Color(0xFFA9D6F5), Critical = Color(0xFFFF4D55),
    Good = Color(0xFF7ED6A3), Muted = Color(0xFF8A939E), TextPrimary = Color(0xFFF3F5F7), TextSecondary = Color(0xFFB4BCC5),
    Accent2 = Color(0xFFA9D6F5), Line = Color.White.copy(alpha = 0.08f), Glow = 0.25f,
    BackgroundStops = listOf(Color(0xFF1A1E23), Color(0xFF121517)),
    HeroWeight = FontWeight.Medium
)

// The four cabins (their tile designs are in WidgetFacesCabins.kt).
// Luxury classic: midnight navy glass with champagne gold hairlines and thin
// numerals. By day, ivory panels, navy ink and a deeper, readable gold.
private val LuxuryPalette = DashPalette(
    Background = Color(0xFF0B1426), Bar = Color(0xCC0F1A30), Card = Color(0xE6122038), CardHi = Color(0x1FF7DEAA),
    Accent = Color(0xFFE3B96C), Secondary = Color(0xFF9FB8E0), Critical = Color(0xFFFF5A5F),
    Good = Color(0xFF6FD3A0), Muted = Color(0xFF8792AA), TextPrimary = Color(0xFFF7F2E7), TextSecondary = Color(0xFFBFC4D2),
    Accent2 = Color(0xFFF7DEAA), Line = Color(0x40E3B96C), Glass = true, Glow = 0.35f,
    BackgroundStops = listOf(Color(0xFF16254A), Color(0xFF0B1426), Color(0xFF0A1020)),
    HeroWeight = FontWeight.Light
)
private val LuxuryLightPalette = DashPalette(
    Background = Color(0xFFF6F2EA), Bar = Color(0xCCFFFFFF), Card = Color(0xF2FFFFFF), CardHi = Color(0x141B2A4A),
    Accent = Color(0xFF946415), Secondary = Color(0xFF1F3A68), Critical = Color(0xFFC62828),
    Good = Color(0xFF1E7F4F), Muted = Color(0xFF6E6A62), TextPrimary = Color(0xFF14203A), TextSecondary = Color(0xFF4A5268),
    Accent2 = Color(0xFFC4923E), Line = Color(0x40946415), Glass = true, Glow = 0.15f,
    BackgroundStops = listOf(Color(0xFFFBF8F2), Color(0xFFF3EEE4), Color(0xFFEDE6D8)), Light = true,
    HeroWeight = FontWeight.Light
)
// Eco leaf: frosted sage glass over a deep green page, mint and pale lime
// for anything live. By day, a morning-garden white with forest ink.
private val EcoLeafPalette = DashPalette(
    Background = Color(0xFF1B2A22), Bar = Color(0xCC22342A), Card = Color(0xCC34503F), CardHi = Color(0x24FFFFFF),
    Accent = Color(0xFF9FE3B8), Secondary = Color(0xFF7FC8D8), Critical = Color(0xFFFF6B5E),
    Good = Color(0xFF9FE3B8), Muted = Color(0xFFA2B6A9), TextPrimary = Color(0xFFF1F7F2), TextSecondary = Color(0xFFBFD0C4),
    Accent2 = Color(0xFFD7F2C4), Line = Color(0x33D7F2C4), Glass = true, Glow = 0.3f,
    BackgroundStops = listOf(Color(0xFF2A3F33), Color(0xFF1B2A22), Color(0xFF15221B))
)
private val EcoLeafLightPalette = DashPalette(
    Background = Color(0xFFEEF3EC), Bar = Color(0xCCFFFFFF), Card = Color(0xF2FFFFFF), CardHi = Color(0x1416261C),
    Accent = Color(0xFF2E7D4F), Secondary = Color(0xFF2C7A8C), Critical = Color(0xFFC62828),
    Good = Color(0xFF2E7D4F), Muted = Color(0xFF5E7063), TextPrimary = Color(0xFF16261C), TextSecondary = Color(0xFF46584B),
    Accent2 = Color(0xFF6BAF5A), Line = Color(0x1A16261C), Glass = true, Glow = 0.15f,
    BackgroundStops = listOf(Color(0xFFF4F8F1), Color(0xFFEAF1E7), Color(0xFFE2ECDF)), Light = true
)
// Cyber sport: carbon black, crimson running into magenta, condensed
// numerals and the cluster in the bar. By day, bright paddock white.
private val CyberSportPalette = DashPalette(
    Background = Color(0xFF08070A), Bar = Color(0xFF0E0B10), Card = Color(0xFF141117), CardHi = Color(0xFF221B24),
    Accent = Color(0xFFFF2D55), Secondary = Color(0xFFFF7A45), Critical = Color(0xFFFF3B3B),
    Good = Color(0xFF3DDC84), Muted = Color(0xFF8E8791), TextPrimary = Color(0xFFF7F3F6), TextSecondary = Color(0xFFBDB3BC),
    Accent2 = Color(0xFFFF4FD8), Line = Color(0x40FF2D55), Glow = 0.9f,
    BackgroundStops = listOf(Color(0xFF16080F), Color(0xFF08070A), Color(0xFF12060E)),
    Font = DashFont.CONDENSED, HeroWeight = FontWeight.Bold, BarStyle = DashBarStyle.CLUSTER
)
private val CyberSportLightPalette = DashPalette(
    Background = Color(0xFFF6F3F5), Bar = Color.White, Card = Color.White, CardHi = Color(0xFFECE5EA),
    Accent = Color(0xFFD1123F), Secondary = Color(0xFFD9591A), Critical = Color(0xFFC8102E),
    Good = Color(0xFF178A48), Muted = Color(0xFF6F6670), TextPrimary = Color(0xFF140E13), TextSecondary = Color(0xFF4F4650),
    Accent2 = Color(0xFFC21E9C), Line = Color.Black.copy(alpha = 0.08f), Glow = 0.2f,
    BackgroundStops = listOf(Color.White, Color(0xFFEDE8EC)), Light = true,
    Font = DashFont.CONDENSED, HeroWeight = FontWeight.Bold, BarStyle = DashBarStyle.CLUSTER
)
// Nordic minimal: graphite and ice blue, thin numerals and hairlines, no glow.
// By day, the same calm in pale grey and slate ink.
private val NordicPalette = DashPalette(
    Background = Color(0xFF1E2024), Bar = Color(0xFF232529), Card = Color(0xFF2A2D32), CardHi = Color(0xFF34383E),
    Accent = Color(0xFFB8CCDD), Secondary = Color(0xFF9FB7A8), Critical = Color(0xFFFF5C5C),
    Good = Color(0xFF8FCFA6), Muted = Color(0xFF90959C), TextPrimary = Color(0xFFF0F2F4), TextSecondary = Color(0xFFAEB3BA),
    Accent2 = Color(0xFFDDE6EE), Line = Color.White.copy(alpha = 0.08f),
    BackgroundStops = listOf(Color(0xFF24272C), Color(0xFF1C1E22)),
    HeroWeight = FontWeight.Light
)
private val NordicLightPalette = DashPalette(
    Background = Color(0xFFF3F4F5), Bar = Color.White, Card = Color.White, CardHi = Color(0xFFE6E8EB),
    Accent = Color(0xFF3E5F7A), Secondary = Color(0xFF4F7A62), Critical = Color(0xFFC62828),
    Good = Color(0xFF2F7A4F), Muted = Color(0xFF686D74), TextPrimary = Color(0xFF1B1D20), TextSecondary = Color(0xFF50555C),
    Accent2 = Color(0xFF7B97AE), Line = Color.Black.copy(alpha = 0.08f),
    BackgroundStops = listOf(Color(0xFFF8F9FA), Color(0xFFEDEFF1)), Light = true,
    HeroWeight = FontWeight.Light
)

// Canvas: the live map is the page (CanvasSkin.kt); everything floats over it
// on smoked glass. No glow: one cyan accent, the fixed amber and red for
// alerts. By day, a light map under white glass and a deeper cyan.
private val CanvasPalette = DashPalette(
    Background = Color(0xFF07090C), Bar = Color(0xBD0C0F14), Card = Color(0xBD0C0F14), CardHi = Color(0x17FFFFFF),
    Accent = Color(0xFF3DD9FF), Secondary = Color(0xFF34D399), Critical = Color(0xFFFF4D4F),
    Good = Color(0xFF34D399), Muted = Color(0xFF8C939C), TextPrimary = Color(0xFFF4F6F8), TextSecondary = Color(0xFFA9B0B8),
    Accent2 = Color(0xFF8BE9FF), Line = Color(0x1FFFFFFF), Skin = DashSkin.CANVAS
)
private val CanvasLightPalette = DashPalette(
    Background = Color(0xFFEEF1F4), Bar = Color(0xDBFFFFFF), Card = Color(0xDBFFFFFF), CardHi = Color(0x0F0E141B),
    Accent = Color(0xFF0088C2), Secondary = Color(0xFF0F8A5F), Critical = Color(0xFFC8261B),
    Good = Color(0xFF0F8A5F), Muted = Color(0xFF666E78), TextPrimary = Color(0xFF0E141B), TextSecondary = Color(0xFF545C66),
    Accent2 = Color(0xFF34A9DC), Line = Color(0x1A0E141B), Light = true, Skin = DashSkin.CANVAS
)

/** How the screen is divided: pages only, or a permanent Google Maps dock beside them. */
enum class DashLayout(
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    /** The name on an upright screen, where the left dock sits on top and the right one below. */
    @StringRes val verticalTitleRes: Int
) {
    GRID(R.string.dash_layout_grid, R.string.dash_layout_grid_desc, R.string.dash_layout_grid),
    MAPS_LEFT(R.string.dash_layout_maps_left, R.string.dash_layout_maps_left_desc, R.string.dash_layout_maps_top),
    MAPS_RIGHT(R.string.dash_layout_maps_right, R.string.dash_layout_maps_right_desc, R.string.dash_layout_maps_bottom)
}

/** The layout's display name in the current language (the enum name is what gets saved). */
internal val DashLayout.title: String
    @Composable get() = stringResource(if (ScreenShape.vertical) verticalTitleRes else titleRes)

object DashLayoutStore {
    private const val PREFS = "dashboard_layout"
    private const val KEY = "mode"

    fun load(context: Context): DashLayout = runCatching {
        DashLayout.valueOf(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, DashLayout.GRID.name) ?: DashLayout.GRID.name
        )
    }.getOrDefault(DashLayout.GRID)

    fun save(context: Context, layout: DashLayout) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, layout.name).apply()
    }

    /** Share of the width the Maps dock takes in the docked layouts (the divider is draggable). */
    const val MIN_DOCK_FRACTION = 0.25f
    const val MAX_DOCK_FRACTION = 0.75f
    private const val KEY_DOCK_FRACTION = "dock_fraction"

    fun loadDockFraction(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_DOCK_FRACTION, 0.5f).coerceIn(MIN_DOCK_FRACTION, MAX_DOCK_FRACTION)

    fun saveDockFraction(context: Context, fraction: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_DOCK_FRACTION, fraction.coerceIn(MIN_DOCK_FRACTION, MAX_DOCK_FRACTION)).apply()
    }
}

object DashThemeStore {
    private const val PREFS = "dashboard_theme"
    private const val KEY = "mode"
    private const val KEY_APPEARANCE = "appearance"
    private const val KEY_EFFECTS = "effects"
    private const val KEY_FONT = "font"
    private const val KEY_BAR_AUTO_HIDE = "bar_auto_hide"
    private const val KEY_BAR_HIDE_SECONDS = "bar_hide_seconds"
    private const val KEY_RAIL = "side_rail"
    private const val KEY_LOOK_LAYOUT = "look_layout"
    private const val KEY_TABS = "tabs_instead_of_swiping"

    /** Seconds the bottom bar waits before hiding, until the driver picks another delay. */
    const val DEFAULT_BAR_HIDE_SECONDS = 5

    fun load(context: Context): DashThemeMode = runCatching {
        DashThemeMode.valueOf(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, DashThemeMode.AUTO.name) ?: DashThemeMode.AUTO.name
        )
    }.getOrDefault(DashThemeMode.AUTO)

    fun save(context: Context, mode: DashThemeMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, mode.name).apply()
    }

    fun loadAppearance(context: Context): DashAppearance = runCatching {
        DashAppearance.valueOf(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_APPEARANCE, DashAppearance.AUTO.name) ?: DashAppearance.AUTO.name
        )
    }.getOrDefault(DashAppearance.AUTO)

    fun saveAppearance(context: Context, appearance: DashAppearance) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_APPEARANCE, appearance.name).apply()
    }

    private val _appearanceAsked = kotlinx.coroutines.flow.MutableSharedFlow<DashAppearance>(extraBufferCapacity = 1)
    /** A look asked for from outside Settings (the Quick switches tile): the dashboard takes it up. */
    val appearanceAsked: kotlinx.coroutines.flow.SharedFlow<DashAppearance> = _appearanceAsked

    fun askAppearance(context: Context, appearance: DashAppearance) {
        saveAppearance(context, appearance)
        _appearanceAsked.tryEmit(appearance)
    }

    private val _modeAsked = kotlinx.coroutines.flow.MutableSharedFlow<DashThemeMode>(extraBufferCapacity = 1)
    /** A skin asked for from outside Settings (an automation app, [Automation]): the dashboard takes it up. */
    val modeAsked: kotlinx.coroutines.flow.SharedFlow<DashThemeMode> = _modeAsked

    fun askMode(context: Context, mode: DashThemeMode) {
        save(context, mode)
        _modeAsked.tryEmit(mode)
    }

    fun loadEffects(context: Context): DashEffects = runCatching {
        DashEffects.valueOf(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_EFFECTS, DashEffects.FULL.name) ?: DashEffects.FULL.name
        )
    }.getOrDefault(DashEffects.FULL)

    fun saveEffects(context: Context, effects: DashEffects) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_EFFECTS, effects.name).apply()
    }

    /** The driver's font for every text, or null to leave each look its own face; state, so the app follows at once. */
    var font: DashFont? by mutableStateOf(null)
        private set

    fun loadFont(context: Context): DashFont? = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FONT, null)?.let { DashFont.valueOf(it) }
    }.getOrNull().also { font = it }

    fun saveFont(context: Context, font: DashFont?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_FONT, font?.name).apply()
        this.font = font
    }

    /** Whether the bottom bar hides itself when unused (BarAutoHide.kt); off by default. */
    fun loadBarAutoHide(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BAR_AUTO_HIDE, false)

    fun saveBarAutoHide(context: Context, autoHide: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_BAR_AUTO_HIDE, autoHide).apply()
    }

    /** Seconds, 0 to [MAX_BAR_HIDE_SECONDS], the bar waits unused before it hides. */
    fun loadBarHideSeconds(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_BAR_HIDE_SECONDS, DEFAULT_BAR_HIDE_SECONDS).coerceIn(0, MAX_BAR_HIDE_SECONDS)

    fun saveBarHideSeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_BAR_HIDE_SECONDS, seconds.coerceIn(0, MAX_BAR_HIDE_SECONDS)).apply()
    }

    /**
     * Whether choosing a look also arranges Home the way the look was drawn
     * ([skinLayout]), with Undo; on by default. Colour-only looks leave Home alone.
     */
    fun loadLookLayout(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LOOK_LAYOUT, true)

    fun saveLookLayout(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LOOK_LAYOUT, on).apply()
    }

    /**
     * The side rail in the bottom bar's place (Settings, Display, Navigation):
     * the driver's choice, or null while they have not made one, when it
     * follows the theme (on with Canvas, as it always was there), see [railOn].
     */
    fun loadRail(context: Context): Boolean? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_RAIL)) prefs.getBoolean(KEY_RAIL, false) else null
    }

    fun saveRail(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_RAIL, on).apply()
    }

    private val _railAsked = kotlinx.coroutines.flow.MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    /** The rail turned on or off from outside Settings (the phone's Rescue card): the dashboard takes it up. */
    val railAsked: kotlinx.coroutines.flow.SharedFlow<Boolean> = _railAsked

    fun askRail(context: Context, on: Boolean) {
        saveRail(context, on)
        _railAsked.tryEmit(on)
    }

    /** Whether the rail shows: the choice made, else the theme's own habit. */
    fun railOn(choice: Boolean?, mode: DashThemeMode): Boolean = choice ?: (mode == DashThemeMode.CANVAS || mode == DashThemeMode.TERRAIN)

    /** Tabs in the rail instead of swiping, asked for; off by default (a map app under the dashboards turns them on by itself). */
    fun loadTabs(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TABS, false)

    fun saveTabs(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_TABS, on).apply()
    }
}

/** The palette [mode] uses in its dark or [light] version. */
internal fun paletteFor(mode: DashThemeMode, light: Boolean): DashPalette = when (mode) {
    DashThemeMode.AUTO -> if (light) AutoLightPalette else AutoDarkPalette
    DashThemeMode.ORIGINAL -> if (light) OriginalLightPalette else OriginalDarkPalette
    DashThemeMode.AURORA -> if (light) AuroraLightPalette else AuroraPalette
    DashThemeMode.NEON_DARK -> if (light) NeonLightPalette else NeonDarkPalette
    DashThemeMode.CLEAN_LIGHT -> if (light) CleanLightPalette else CleanDarkPalette
    DashThemeMode.DARK_GLASS -> if (light) FrostedGlassPalette else DarkGlassPalette
    DashThemeMode.SPORTY -> if (light) SportyLightPalette else SportyPalette
    DashThemeMode.FLOATING -> if (light) FloatingLightPalette else FloatingPalette
    DashThemeMode.ORBIT -> if (light) OrbitLightPalette else OrbitPalette
    DashThemeMode.COCKPIT -> if (light) CockpitLightPalette else CockpitPalette
    DashThemeMode.HORIZON -> if (light) HorizonLightPalette else HorizonPalette
    DashThemeMode.TAPE_DECK -> if (light) TapeDeckLightPalette else TapeDeckPalette
    DashThemeMode.MISTRAL -> if (light) MistralLightPalette else MistralPalette
    DashThemeMode.ZENITH -> if (light) ZenithLightPalette else ZenithDarkPalette
    DashThemeMode.LUXURY -> if (light) LuxuryLightPalette else LuxuryPalette
    DashThemeMode.ECO_LEAF -> if (light) EcoLeafLightPalette else EcoLeafPalette
    DashThemeMode.CYBER_SPORT -> if (light) CyberSportLightPalette else CyberSportPalette
    DashThemeMode.NORDIC -> if (light) NordicLightPalette else NordicPalette
    DashThemeMode.CANVAS -> if (light) CanvasLightPalette else CanvasPalette
    // The 2026-10 skins keep their palettes in their own files.
    DashThemeMode.FUTURISTIC -> if (light) FuturisticLightPalette else FuturisticPalette
    DashThemeMode.SIMPLE -> if (light) SimpleLightPalette else SimplePalette
    DashThemeMode.MODERN -> if (light) ModernLightPalette else ModernPalette
    DashThemeMode.MOTORSPORT -> if (light) MotorsportLightPalette else MotorsportPalette
    DashThemeMode.ANIME -> if (light) AnimeLightPalette else AnimePalette
    DashThemeMode.RACING -> if (light) RacingLightPalette else RacingPalette
    DashThemeMode.PANORAMA -> if (light) PanoramaLightPalette else PanoramaPalette
    DashThemeMode.LIQUID -> if (light) LiquidLightPalette else LiquidPalette
    DashThemeMode.EXPRESSIVE -> if (light) ExpressiveLightPalette else ExpressivePalette
    DashThemeMode.KINETIC -> if (light) KineticLightPalette else KineticPalette
    DashThemeMode.GLYPH -> if (light) GlyphLightPalette else GlyphPalette
    DashThemeMode.WINDSHIELD -> if (light) WindshieldLightPalette else WindshieldPalette
    DashThemeMode.TERRAIN -> if (light) TerrainLightPalette else TerrainPalette
    DashThemeMode.AMBIENT -> if (light) AmbientLightPalette else AmbientPalette
    DashThemeMode.BRUTAL -> if (light) BrutalLightPalette else BrutalPalette
    DashThemeMode.CLAY -> if (light) ClayLightPalette else ClayPalette
    DashThemeMode.CHROME -> if (light) ChromeLightPalette else ChromePalette
    DashThemeMode.SPATIAL -> if (light) SpatialLightPalette else SpatialPalette
    DashThemeMode.PAPER -> if (light) PaperLightPalette else PaperPalette
    // Open: Floating's page, with every widget redrawn frameless (TileContent).
    DashThemeMode.OPEN -> (if (light) FloatingLightPalette else FloatingPalette).copy(Open = true)
}

/**
 * True when [this] appearance shows the light version right now. Auto goes by
 * the driver's choice ([AutoLight]). By the car it is day only while the
 * system is in day mode (the head unit's headlight signal on most units), the
 * headlights are off (the QF firmware says so directly, [UnitSignals]) and the
 * sun is up where the car is (DayNight.kt): a unit whose night mode never
 * fires still goes dark at dusk, and headlights in a tunnel or rain still win
 * by day. A unit whose night mode or headlight signal is stuck on stays dark
 * all day that way, hence the sun alone or two set times.
 */
@Composable
internal fun DashAppearance.isLight(): Boolean = when (this) {
    DashAppearance.AUTO -> rememberAutoDay()
    DashAppearance.DARK -> false
    DashAppearance.LIGHT -> true
}

object DashColors {
    /** What is drawn now: the target palette, or a blend on the way to it. */
    private var current by mutableStateOf(AutoDarkPalette)
    // Not "effects": its getter would clash with the public Effects accessor on the JVM.
    private var effectsLevel by mutableStateOf(DashEffects.FULL)

    /**
     * Follows the chosen theme, fading the colours over [FADE_MS] so a day /
     * night switch or a new theme eases in instead of flashing. The design
     * knobs (glass, skin, bare) change at once; only colours and the glow blend.
     */
    @Composable
    fun Sync(mode: DashThemeMode, appearance: DashAppearance, effects: DashEffects = DashEffects.FULL) {
        val context = LocalContext.current
        remember { DashThemeStore.loadFont(context) }
        val target = paletteFor(mode, appearance.isLight())
        if (effectsLevel != effects) effectsLevel = effects
        val fade = remember { Animatable(1f) }
        var from by remember { mutableStateOf(target) }
        var to by remember { mutableStateOf(target) }
        // A new target keeps showing the old colours until its fade has started,
        // so it never flashes in for the one frame before the effect runs.
        var pending by remember { mutableStateOf(false) }
        if (to != target) {
            from = current
            to = target
            pending = true
        }
        LaunchedEffect(to) {
            if (!pending) return@LaunchedEffect
            fade.snapTo(0f)
            pending = false
            fade.animateTo(1f, tween(FADE_MS))
        }
        val shown = when {
            pending -> from
            fade.value >= 1f -> to
            else -> blend(from, to, fade.value)
        }
        if (current != shown) current = shown
    }

    private const val FADE_MS = 400

    /** [a] towards [b] by [t]: colours and glow blend, everything else is [b]'s. */
    private fun blend(a: DashPalette, b: DashPalette, t: Float): DashPalette = b.copy(
        Background = lerp(a.Background, b.Background, t),
        Bar = lerp(a.Bar, b.Bar, t),
        Card = lerp(a.Card, b.Card, t),
        CardHi = lerp(a.CardHi, b.CardHi, t),
        Accent = lerp(a.Accent, b.Accent, t),
        Secondary = lerp(a.Secondary, b.Secondary, t),
        Critical = lerp(a.Critical, b.Critical, t),
        Good = lerp(a.Good, b.Good, t),
        Muted = lerp(a.Muted, b.Muted, t),
        TextPrimary = lerp(a.TextPrimary, b.TextPrimary, t),
        TextSecondary = lerp(a.TextSecondary, b.TextSecondary, t),
        Accent2 = lerp(a.Accent2, b.Accent2, t),
        Line = lerp(a.Line, b.Line, t),
        Glow = a.Glow + (b.Glow - a.Glow) * t,
        Tacho = lerp(a.Tacho, b.Tacho, t),
        Warning = lerp(a.Warning, b.Warning, t),
        BackgroundStops = if (a.BackgroundStops.size == b.BackgroundStops.size) {
            a.BackgroundStops.zip(b.BackgroundStops) { x, y -> lerp(x, y, t) }
        } else b.BackgroundStops
    )

    val Background get() = current.Background
    val Bar get() = current.Bar
    val Card get() = current.Card
    val CardHi get() = current.CardHi
    val Accent get() = current.Accent
    /** [Accent] for words: reads at 4.5:1 on the page ([DashPalette.AccentInk]). */
    val AccentInk get() = current.AccentInk
    val Accent2 get() = current.Accent2
    val Secondary get() = current.Secondary
    /** Amber, in every theme: the tachometer, and readings warming up or running low. */
    val Tacho get() = current.Tacho
    /** Amber: out of range, worth a look. */
    val Warning get() = current.Warning
    /** Red: stop the car. */
    val Critical get() = current.Critical
    val Good get() = current.Good
    val Muted get() = current.Muted
    val TextPrimary get() = current.TextPrimary
    val TextSecondary get() = current.TextSecondary
    val Line get() = current.Line
    /** Translucent panels, unless the effects are off: then every theme gets opaque cards. */
    val Glass get() = current.Glass && effectsLevel != DashEffects.NONE
    /** The theme's halo strength, scaled by the effects setting (0 with effects off). */
    val Glow get() = current.Glow * effectsLevel.scale
    val Effects get() = effectsLevel
    val Original get() = current.Original
    val Bare get() = current.Bare
    val Open get() = current.Open
    val BackgroundStops get() = current.BackgroundStops
    val Skin get() = current.Skin
    val Light get() = current.Light
    /** The driver's font (Settings → Look), else the look's hero face. */
    val Font get() = DashThemeStore.font ?: current.Font
    val HeroWeight get() = current.HeroWeight
    val BarStyle get() = current.BarStyle

    /** The hero face as a font family (the condensed cut comes from the system). */
    fun heroFamily(): FontFamily = Font.family

    /** The driving type scale ([DashTypography]) in the driver's font, so every text without a family of its own follows it. */
    val Typography: Typography get() {
        val font = DashThemeStore.font ?: return DashTypography
        return typographies.getOrPut(font) { DashTypography.inFamily(font.family) }
    }
    private val typographies = HashMap<DashFont, Typography>()

    private fun Typography.inFamily(f: FontFamily) = copy(
        displayLarge = displayLarge.copy(fontFamily = f), displayMedium = displayMedium.copy(fontFamily = f), displaySmall = displaySmall.copy(fontFamily = f),
        headlineLarge = headlineLarge.copy(fontFamily = f), headlineMedium = headlineMedium.copy(fontFamily = f), headlineSmall = headlineSmall.copy(fontFamily = f),
        titleLarge = titleLarge.copy(fontFamily = f), titleMedium = titleMedium.copy(fontFamily = f), titleSmall = titleSmall.copy(fontFamily = f),
        bodyLarge = bodyLarge.copy(fontFamily = f), bodyMedium = bodyMedium.copy(fontFamily = f), bodySmall = bodySmall.copy(fontFamily = f),
        labelLarge = labelLarge.copy(fontFamily = f), labelMedium = labelMedium.copy(fontFamily = f), labelSmall = labelSmall.copy(fontFamily = f)
    )

    /** Diagonal accent → accent2 gradient for primary controls. */
    val AccentBrush: Brush get() {
        val p = current
        accentBrush?.takeIf { it.first === p }?.let { return it.second }
        return Brush.linearGradient(listOf(p.Accent, p.Accent2)).also { accentBrush = p to it }
    }
    private var accentBrush: Pair<Any, Brush>? = null

    /** Text colour that reads on top of [AccentBrush] ([DashPalette.OnAccentInk]). */
    val OnAccent: Color get() = current.OnAccentInk

    /** Brightest ink, for glowing numerals: white at night, the text colour by day. */
    val Bright: Color get() = if (current.Light) current.TextPrimary else Color.White

    /** Translucent lift behind an item on glass (chip, row, gauge track): a white haze at night, a faint ink tint by day. */
    fun haze(alpha: Float): Color =
        if (current.Light) Color.Black.copy(alpha = alpha * 0.7f) else Color.White.copy(alpha = alpha)

    /** Translucent well sunk into glass (progress and slider tracks): deep at night, faint by day. */
    fun well(alpha: Float): Color = Color.Black.copy(alpha = if (current.Light) alpha * 0.25f else alpha)
}
