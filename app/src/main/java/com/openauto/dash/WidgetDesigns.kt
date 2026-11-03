package com.openauto.dash

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Per-tile designs. Every built-in widget can be drawn in any [WidgetDesign]:
 * [WidgetDesign.STANDARD] is the widget's own renderer, every other design
 * draws the widget's live [WidgetFace] with one [FaceLayout] in one
 * [FaceLookKind] material. The Map, Maps window and 3D car keep their live
 * view and get the design's frame around it instead.
 */

/** How a design arranges a widget's reading. */
internal enum class FaceLayout { HERO, ARC, RING, BARS, STATS, TERMINAL, DIAL, FLAP, ORB, LIQUID, DOTS, POSTER, DUO, ISLAND, COCKPIT, TRIANGLES, BLADE, LIGHT_BAR, LANE, TREND, PULSE, CONTOUR }

/** The material a design is drawn in. THEME follows the dashboard theme; the rest bring their own colours. */
internal enum class FaceLookKind { THEME, OPEN, LCD, AMBER, NEON, PAPER, GLASS, CARBON, CHROME, FLAP, DOTS, COPPER, PETROL, NAVY_GOLD, SAGE, CRIMSON, GRAPHITE }

/**
 * A tile's design. Names are persisted with the tile, so never rename an
 * entry; a removed one goes in [RETIRED] so saved tiles keep a close look. [layout] is null for [STANDARD] and for
 * the widget-specific designs, which draw their own picture of the reading
 * (WidgetSignatures.kt) and exist only for the [kinds] they suit.
 */
enum class WidgetDesign(
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    internal val layout: FaceLayout?,
    internal val look: FaceLookKind,
    /** The widgets a widget-specific design is made for; null for the generic ones, which suit every widget. */
    internal val kinds: Set<BuiltinKind>? = null,
    /**
     * Read in a glance at the wheel. False for the looks that are pretty but
     * slow to read (flipping cards, waves, dots, drums, no digits): the shelf
     * lists them last for the driving and navigation widgets.
     */
    internal val glanceable: Boolean = true
) {
    STANDARD(R.string.design_standard, R.string.design_standard_desc, null, FaceLookKind.THEME),
    HERO(R.string.design_hero, R.string.design_hero_desc, FaceLayout.HERO, FaceLookKind.THEME),
    LCD(R.string.design_lcd, R.string.design_lcd_desc, FaceLayout.HERO, FaceLookKind.LCD),
    AMBER(R.string.design_amber, R.string.design_amber_desc, FaceLayout.TERMINAL, FaceLookKind.AMBER, glanceable = false),
    NEON(R.string.design_neon, R.string.design_neon_desc, FaceLayout.ARC, FaceLookKind.NEON),
    PAPER(R.string.design_paper, R.string.design_paper_desc, FaceLayout.STATS, FaceLookKind.PAPER),
    GLASS(R.string.design_glass, R.string.design_glass_desc, FaceLayout.RING, FaceLookKind.GLASS),
    CARBON(R.string.design_carbon, R.string.design_carbon_desc, FaceLayout.BARS, FaceLookKind.CARBON),
    CHRONO(R.string.design_chrono, R.string.design_chrono_desc, FaceLayout.DIAL, FaceLookKind.CHROME),
    FLAP(R.string.design_flap, R.string.design_flap_desc, FaceLayout.FLAP, FaceLookKind.FLAP, glanceable = false),
    ORB(R.string.design_orb, R.string.design_orb_desc, FaceLayout.ORB, FaceLookKind.THEME),
    LIQUID(R.string.design_liquid, R.string.design_liquid_desc, FaceLayout.LIQUID, FaceLookKind.THEME, glanceable = false),
    DOTS(R.string.design_dots, R.string.design_dots_desc, FaceLayout.DOTS, FaceLookKind.DOTS, glanceable = false),
    POSTER(R.string.design_poster, R.string.design_poster_desc, FaceLayout.POSTER, FaceLookKind.THEME, glanceable = false),
    DUO(R.string.design_duo, R.string.design_duo_desc, FaceLayout.DUO, FaceLookKind.THEME),
    ISLAND(R.string.design_island, R.string.design_island_desc, FaceLayout.ISLAND, FaceLookKind.THEME),
    // No panel and no rim: the reading sits on the page, what the Open look puts on every widget.
    OPEN(R.string.design_open, R.string.design_open_desc, FaceLayout.HERO, FaceLookKind.OPEN),
    // The copper set, after the latest Cupra cars (WidgetFacesCopper.kt).
    COPPER_COCKPIT(R.string.design_copper_cockpit, R.string.design_copper_cockpit_desc, FaceLayout.COCKPIT, FaceLookKind.COPPER),
    TRI_LED(R.string.design_tri_led, R.string.design_tri_led_desc, FaceLayout.TRIANGLES, FaceLookKind.PETROL),
    COPPER_BLADE(R.string.design_copper_blade, R.string.design_copper_blade_desc, FaceLayout.BLADE, FaceLookKind.PETROL),
    LIGHT_BAR(R.string.design_light_bar, R.string.design_light_bar_desc, FaceLayout.LIGHT_BAR, FaceLookKind.COPPER),
    // The four cabins: luxury, eco, sport and Nordic interiors (WidgetFacesCabins.kt).
    LANE_VIEW(R.string.design_lane, R.string.design_lane_desc, FaceLayout.LANE, FaceLookKind.NAVY_GOLD),
    TREND(R.string.design_trend, R.string.design_trend_desc, FaceLayout.TREND, FaceLookKind.SAGE),
    PULSE(R.string.design_pulse, R.string.design_pulse_desc, FaceLayout.PULSE, FaceLookKind.CRIMSON),
    CONTOUR(R.string.design_contour, R.string.design_contour_desc, FaceLayout.CONTOUR, FaceLookKind.GRAPHITE),

    // Made for particular widgets: the shape comes from what the widget shows.
    THERMOMETER(R.string.design_thermometer, R.string.design_thermometer_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.WARMUP, BuiltinKind.WEATHER, BuiltinKind.ENGINE_TEMPS)),
    FUEL_TANK(R.string.design_fuel_tank, R.string.design_fuel_tank_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.RANGE, BuiltinKind.FUEL_TO_DEST, BuiltinKind.FUEL_PRICES, BuiltinKind.FUEL_LOG, BuiltinKind.LPG_TANK, BuiltinKind.CONSUMPTION)),
    BATTERY_CELL(R.string.design_battery_cell, R.string.design_battery_cell_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.BATTERY)),
    FADER(R.string.design_fader, R.string.design_fader_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.AUDIO, BuiltinKind.BATTERY, BuiltinKind.WARMUP, BuiltinKind.RANGE, BuiltinKind.BREAK_TIMER, BuiltinKind.SCREEN_LIGHT, BuiltinKind.UNIT_LIGHT)),
    SPEED_TAPE(R.string.design_speed_tape, R.string.design_speed_tape_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.SPEED_HUD, BuiltinKind.TELEMETRY, BuiltinKind.SPEED_LIMIT)),
    // Not the speed: a number in a red ring reads as the speed limit.
    ROAD_SIGN(R.string.design_road_sign, R.string.design_road_sign_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.PARKING, BuiltinKind.NAVIGATION, BuiltinKind.BREAK_TIMER, BuiltinKind.FUEL_TO_DEST, BuiltinKind.RANGE, BuiltinKind.FUEL_PRICES, BuiltinKind.SPEED_LIMIT, BuiltinKind.PARKING_TIMER)),
    TWIN_DIALS(R.string.design_twin_dials, R.string.design_twin_dials_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.TELEMETRY, BuiltinKind.OBD_ALL)),
    SHIFT_LIGHTS(R.string.design_shift_lights, R.string.design_shift_lights_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.TELEMETRY, BuiltinKind.OBD_ALL, BuiltinKind.GEAR)),
    HEADING_TAPE(R.string.design_heading_tape, R.string.design_heading_tape_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.COMPASS)),
    COMPASS_ROSE(R.string.design_compass_rose, R.string.design_compass_rose_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.COMPASS, BuiltinKind.PARKING)),
    POINTER(R.string.design_pointer, R.string.design_pointer_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.COMPASS, BuiltinKind.PARKING)),
    FRICTION_CIRCLE(R.string.design_friction_circle, R.string.design_friction_circle_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.GFORCE)),
    G_BARS(R.string.design_g_bars, R.string.design_g_bars_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.GFORCE)),
    SPIRIT_LEVEL(R.string.design_spirit_level, R.string.design_spirit_level_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.GFORCE)),
    TURN_CARD(R.string.design_turn_card, R.string.design_turn_card_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.NAVIGATION)),
    ROAD_AHEAD(R.string.design_road_ahead, R.string.design_road_ahead_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.NAVIGATION, BuiltinKind.TRIP, BuiltinKind.FUEL_TO_DEST)),
    RADAR(R.string.design_radar, R.string.design_radar_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.PARKING, BuiltinKind.GPS_STATUS)),
    ODOMETER(R.string.design_odometer, R.string.design_odometer_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.TRIP, BuiltinKind.RANGE, BuiltinKind.SERVICE, BuiltinKind.CAR_STATUS, BuiltinKind.PERF_TIMER, BuiltinKind.COMMUTE, BuiltinKind.DRIVE_HISTORY), glanceable = false),
    PRINTOUT(R.string.design_printout, R.string.design_printout_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.TRIP, BuiltinKind.ECO_DRIVE, BuiltinKind.OBD_DTC, BuiltinKind.CAN_MON, BuiltinKind.SERVICE, BuiltinKind.FUEL_PRICES, BuiltinKind.CAR_STATUS, BuiltinKind.TYRES, BuiltinKind.HEAD_UNIT, BuiltinKind.RADIOS, BuiltinKind.PERF_TIMER, BuiltinKind.FUEL_LOG, BuiltinKind.COMMUTE, BuiltinKind.LPG_TANK, BuiltinKind.DRIVE_HISTORY, BuiltinKind.MY_READINGS, BuiltinKind.CONSUMPTION)),
    WARNING_LAMP(R.string.design_warning_lamp, R.string.design_warning_lamp_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.OBD_DTC, BuiltinKind.FILTER_CARE, BuiltinKind.BATTERY, BuiltinKind.WARMUP, BuiltinKind.DOORS, BuiltinKind.RANGE, BuiltinKind.FUEL_TO_DEST, BuiltinKind.SERVICE, BuiltinKind.TYRES, BuiltinKind.SPEED_CAMERAS, BuiltinKind.WEATHER_ALERTS)),
    TRAFFIC_LIGHT(R.string.design_traffic_light, R.string.design_traffic_light_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.OBD_DTC, BuiltinKind.BATTERY, BuiltinKind.ECO_DRIVE, BuiltinKind.BREAK_TIMER, BuiltinKind.FILTER_CARE, BuiltinKind.DOORS, BuiltinKind.FUEL_TO_DEST, BuiltinKind.HEAD_UNIT, BuiltinKind.QUICK_SWITCHES, BuiltinKind.WEATHER_ALERTS, BuiltinKind.NOW)),
    GAUGE_BANK(R.string.design_gauge_bank, R.string.design_gauge_bank_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.OBD_ALL, BuiltinKind.TELEMETRY, BuiltinKind.HEAD_UNIT, BuiltinKind.ENGINE_TEMPS, BuiltinKind.MY_READINGS)),
    CAR_TOP(R.string.design_car_top, R.string.design_car_top_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.DOORS)),
    DATA_RAIN(R.string.design_data_rain, R.string.design_data_rain_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.CAN_MON)),
    SKY(R.string.design_sky, R.string.design_sky_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.WEATHER, BuiltinKind.CLOCK, BuiltinKind.WEATHER_ALERTS)),
    SUN_PATH(R.string.design_sun_path, R.string.design_sun_path_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.CLOCK, BuiltinKind.WEATHER)),
    BINARY_CLOCK(R.string.design_binary_clock, R.string.design_binary_clock_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.CLOCK), glanceable = false),
    TIMELINE(R.string.design_timeline, R.string.design_timeline_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.CALENDAR, BuiltinKind.NOTIFICATIONS, BuiltinKind.COMMUTE)),
    DESK_CALENDAR(R.string.design_desk_calendar, R.string.design_desk_calendar_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.CALENDAR)),
    CARD_STACK(R.string.design_card_stack, R.string.design_card_stack_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.NOTIFICATIONS, BuiltinKind.CALENDAR, BuiltinKind.QUICK_DIAL, BuiltinKind.HOME_WORK, BuiltinKind.SHARE_ETA, BuiltinKind.BT_DEVICES, BuiltinKind.NOW)),
    FACES(R.string.design_faces, R.string.design_faces_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.QUICK_DIAL)),
    BADGE(R.string.design_badge, R.string.design_badge_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.NOTIFICATIONS, BuiltinKind.RADIOS, BuiltinKind.SPEED_CAMERAS, BuiltinKind.RADIO_PRESETS, BuiltinKind.HOME_WORK, BuiltinKind.SHARE_ETA, BuiltinKind.VOICE_NOTES, BuiltinKind.QUICK_SWITCHES, BuiltinKind.WIFI_NETWORKS, BuiltinKind.BT_DEVICES, BuiltinKind.PARKING_TIMER)),
    VINYL(R.string.design_vinyl, R.string.design_vinyl_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.MEDIA)),
    CASSETTE(R.string.design_cassette, R.string.design_cassette_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.MEDIA, BuiltinKind.RADIO_PRESETS, BuiltinKind.VOICE_NOTES)),
    COVER_ART(R.string.design_cover_art, R.string.design_cover_art_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.MEDIA)),
    VOLUME_KNOB(R.string.design_volume_knob, R.string.design_volume_knob_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.AUDIO, BuiltinKind.SCREEN_LIGHT, BuiltinKind.UNIT_LIGHT)),
    LEVEL_METER(R.string.design_level_meter, R.string.design_level_meter_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.AUDIO, BuiltinKind.GPS_STATUS, BuiltinKind.WIFI_NETWORKS)),
    FILTER_CELLS(R.string.design_filter_cells, R.string.design_filter_cells_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.FILTER_CARE)),
    HOURGLASS(R.string.design_hourglass, R.string.design_hourglass_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.BREAK_TIMER, BuiltinKind.PARKING_TIMER)),
    LEAF(R.string.design_leaf, R.string.design_leaf_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.ECO_DRIVE)),
    TYRE_MAP(R.string.design_tyre_map, R.string.design_tyre_map_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.TYRES)),
    CAR_OUTLINE(R.string.design_car_outline, R.string.design_car_outline_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.CAR_STATUS, BuiltinKind.OBD_ALL, BuiltinKind.TELEMETRY, BuiltinKind.DOORS)),
    GEAR_GATE(R.string.design_gear_gate, R.string.design_gear_gate_desc, null, FaceLookKind.THEME, setOf(BuiltinKind.GEAR));

    /** Drawn by its own renderer for the widgets in [kinds] (see WidgetSignatures.kt). */
    internal val isSignature: Boolean get() = kinds != null

    /**
     * True when [kind] can wear this design: Standard always, the widget-specific
     * ones on theirs, the generic ones on every redrawn widget they can show.
     */
    internal fun appliesTo(kind: BuiltinKind): Boolean = when {
        this == STANDARD -> true
        kinds != null -> kind in kinds
        layout == FaceLayout.TREND -> kind !in LIST_KINDS && kind !in UNCHARTED_KINDS
        layout == FaceLayout.PULSE || layout == FaceLayout.CONTOUR -> kind !in LIST_KINDS
        else -> true
    }

    companion object {
        /**
         * Widgets that are a list to read or tap (contacts, messages, events,
         * codes, stations). Trend, Pulse and Contour draw a headline over a
         * chart and never the rows, so the list would be lost in them.
         */
        private val LIST_KINDS = setOf(
            BuiltinKind.QUICK_DIAL, BuiltinKind.NOTIFICATIONS, BuiltinKind.CALENDAR, BuiltinKind.OBD_DTC, BuiltinKind.FUEL_PRICES,
            BuiltinKind.DRIVE_HISTORY
        )

        /** Time only goes up: charted over the last minutes it is a straight line. */
        private val UNCHARTED_KINDS = setOf(BuiltinKind.CLOCK, BuiltinKind.BREAK_TIMER)

        /** The designs [kind] offers, in picker order: Standard, the ones made for it, then the generic ones. */
        internal fun offeredFor(kind: BuiltinKind, framed: Boolean): List<WidgetDesign> {
            val all = entries.filter { it.appliesTo(kind) && !(framed && it.isSignature) }
            return listOf(STANDARD) + all.filter { it.isSignature } + all.filter { !it.isSignature && it != STANDARD }
        }

        /**
         * Designs dropped, for looking too much like another or for hiding
         * what the widget is for (the rotary phone's favourites could not be
         * called), and the one their tiles now wear.
         */
        private val RETIRED = mapOf(
            "MINIMAL" to HERO, "GAUGE" to NEON, "RING" to GLASS,
            "BARS" to CARBON, "STATS" to PAPER, "BLUEPRINT" to CHRONO,
            "ROTARY_PHONE" to FACES
        )

        /** The saved design, its successor if it was retired, or [STANDARD] for a blank or unknown name (a newer build's design after a downgrade). */
        fun fromName(name: String?): WidgetDesign =
            entries.firstOrNull { it.name == name } ?: RETIRED[name] ?: STANDARD
    }
}

/** The design's display name in the current language (the enum name is what gets saved). */
internal val WidgetDesign.title: String
    @Composable get() = stringResource(titleRes)

internal val WidgetDesign.description: String
    @Composable get() = stringResource(descriptionRes)

// --- What a design draws -----------------------------------------------------

@Immutable
internal data class FaceStat(val label: String, val value: String)

@Immutable
internal data class FaceRow(
    val title: String,
    val detail: String,
    val alert: Boolean = false,
    /** Initials drawn in a round badge instead of the row's dot (contacts). */
    val badge: String? = null,
    val onClick: (() -> Unit)? = null,
    /** Set when the row can be swiped away (a notification); [key] then tells rows apart. */
    val onDismiss: (() -> Unit)? = null,
    val key: Any? = null,
    /** A tyre's sensor battery, as the TPMS app shows it: true fine, false low, null not known (the Tyre map draws it). */
    val battery: Boolean? = null
)

/** A small gauge: label, value, unit and how far round it goes (0..1). */
@Immutable
internal data class FaceGauge(val label: String, val value: String, val unit: String, val fraction: Float)

/** The headline of a widget that has no reading yet. */
internal const val NO_READING = "--"

/** Something at a time of day: an agenda event ([endMs] set) or a notification (a moment). */
@Immutable
internal data class FaceEvent(val startMs: Long, val endMs: Long?, val title: String)

/** Which road sign a widget reads as. */
internal enum class SignKind { SPEED, PARKING, DIRECTIONS, FUEL, REST }

@Immutable
/** A tile's tap-to-show edit button: [reveal] says when it is up, [onEdit] runs when it is pressed. */
internal class FaceEdit(val reveal: EditReveal, val onEdit: () -> Unit)

internal data class FaceAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val primary: Boolean = false,
    val enabled: Boolean = true
)

/**
 * One widget's reading, in the shape every design can draw: a headline
 * [value] (+ [unit]) with a [caption], an optional 0..1 [fraction] for
 * gauges and bars, secondary [stats], list [rows] and [actions].
 *
 * [textValue] marks a word rather than a number (a song, a contact), which
 * designs set smaller. [fullCircle] makes gauges run the whole way round
 * (compass, clock seconds). [clock] (h, m, s) and [compass] let the dial
 * designs draw hands or cardinal letters.
 *
 * A value type: a face equal to the last one lets its design skip redrawing,
 * so a feed that repeats itself costs nothing on screen.
 */
@Immutable
internal data class WidgetFace(
    val icon: ImageVector,
    val title: String,
    val value: String,
    val unit: String = "",
    val caption: String = "",
    val fraction: Float? = null,
    val stats: List<FaceStat> = emptyList(),
    val rows: List<FaceRow> = emptyList(),
    val actions: List<FaceAction> = emptyList(),
    val alert: Boolean = false,
    /** The word for [alert] ("Hot", "Low", "Open"), drawn beside the headline so red alone never has to say it. */
    val alertWord: String = "",
    val textValue: Boolean = false,
    val fullCircle: Boolean = false,
    val art: ImageBitmap? = null,
    val clock: Triple<Int, Int, Int>? = null,
    val compass: Boolean = false,
    val onClick: (() -> Unit)? = null,
    // Readings only the widget-specific designs draw.
    /** Low and high ends of the scale (thermometer, tank), e.g. "0°" / "90°". */
    val scale: Pair<String, String>? = null,
    /** The headline as a number (speed tape). */
    val number: Float? = null,
    /** A direction in degrees clockwise: the heading, or the way to the parked car relative to it. */
    val angle: Float? = null,
    /** Lateral and longitudinal g, and the peak. */
    val point: Pair<Float, Float>? = null,
    val peak: Float? = null,
    /** 0 fine, 1 needs attention, 2 act now; null follows [alert]. */
    val severity: Int? = null,
    /** Front left, front right, rear left, rear right, tailgate, bonnet: open or not. */
    val doors: List<Boolean>? = null,
    val events: List<FaceEvent> = emptyList(),
    val weatherCode: Int? = null,
    /** Playing (the record and reels turn). */
    val active: Boolean = false,
    val gauges: List<FaceGauge> = emptyList(),
    val sign: SignKind? = null,
    /** Label at the far end of a road or scale (range, distance so far). */
    val reach: String? = null,
    /** Label on the marker along a road (the destination). */
    val marker: String? = null,
    /** Front left, front right, rear left, rear right: each wheel's row, null where it has no sensor (tyre map). */
    val wheels: List<FaceRow?>? = null,
    /** The gearbox's forward gears (the gear gate). */
    val gears: Int? = null,
    /** An edit button the tile puts over its corner after a tap ([EditReveal]); [onClick] then only shows it. */
    val edit: FaceEdit? = null
) {
    /** [severity], or 2 / 0 from [alert]. */
    val level: Int get() = severity ?: if (alert) 2 else 0

    /** Still waiting for its reading: a design then draws no needle, marker or chart point, only "--" and why. */
    val idle: Boolean get() = value == NO_READING
}

// --- Materials ----------------------------------------------------------------

/** Extra drawing a material adds on top of its colours. */
internal enum class LookDecoration { NONE, SCANLINES, CARBON, CHROME, NEON, GLASS, DOTS, TRIANGLES, PINSTRIPE, LEAF, EDGE_LIGHT }

/**
 * Colours, type and shape of one material. [background] null means the
 * theme's own [Card].
 */
internal data class FaceLook(
    val kind: FaceLookKind,
    val background: Brush?,
    val ink: Color,
    val dim: Color,
    val accent: Color,
    val accent2: Color,
    val warn: Color,
    val track: Color,
    /** Fill behind rows, chips, cells and buttons. */
    val fill: Color,
    val onAccent: Color,
    val border: Color?,
    val borderWidth: Dp = 1.dp,
    val radius: Dp,
    val font: FontFamily,
    val numFont: FontFamily,
    val numWeight: FontWeight,
    val numItalic: Boolean = false,
    val labelWeight: FontWeight = FontWeight.Bold,
    /** Halo behind numerals and lit segments, or null for none. */
    val glow: Color? = null,
    /** Unlit segments behind LCD digits ("88:88"). */
    val ghost: Color? = null,
    val decoration: LookDecoration = LookDecoration.NONE,
    /** Square-ish buttons and chips instead of pills. */
    val squareControls: Boolean = false,
    /** Corners cut on the diagonal (top-start and bottom-end) instead of rounded, card and controls alike. */
    val angular: Boolean = false
)

private val Mono = FontFamily.Monospace
private val Serif = FontFamily.Serif
private val Sans = FontFamily.SansSerif

/** The material for [kind]; THEME reads the live dashboard palette. */
/**
 * The theme look's stand-in where a screen keeps its own colours (the second
 * screen's cluster, ClusterLook): the dashboard's day / night look then stays off it.
 */
internal val LocalThemeFaceLook = androidx.compose.runtime.staticCompositionLocalOf<FaceLook?> { null }

internal fun faceLook(kind: FaceLookKind): FaceLook = when (kind) {
    FaceLookKind.THEME -> FaceLook(
        kind, background = null,
        ink = DashColors.TextPrimary, dim = DashColors.TextSecondary, accent = DashColors.Accent, accent2 = DashColors.Accent2,
        warn = DashColors.Warning, track = DashColors.haze(0.10f),
        fill = if (DashColors.Glass) DashColors.haze(0.07f) else DashColors.CardHi, onAccent = DashColors.OnAccent,
        border = null, radius = 24.dp, font = Sans, numFont = Sans, numWeight = FontWeight.Bold,
        glow = if (DashColors.Glow > 0f) DashColors.Accent.copy(alpha = 0.35f * DashColors.Glow) else null
    )
    FaceLookKind.OPEN -> faceLook(FaceLookKind.THEME).copy(
        kind = kind, background = SolidColor(Color.Transparent), fill = Color.Transparent, border = null
    )
    FaceLookKind.LCD -> FaceLook(
        kind, background = Brush.verticalGradient(listOf(Color(0xFF122010), Color(0xFF0B1509))),
        ink = Color(0xFFA6FF6E), dim = Color(0xFF5E9A3C), accent = Color(0xFFA6FF6E), accent2 = Color(0xFFD8FF9E),
        warn = Color(0xFFFFD84A), track = Color(0x1AA6FF6E), fill = Color(0x14A6FF6E), onAccent = Color(0xFF0E1A0B),
        border = Color(0xFF1F2B1A), borderWidth = 3.dp, radius = 10.dp, font = Mono, numFont = Mono,
        numWeight = FontWeight.Normal, labelWeight = FontWeight.Normal, glow = Color(0x99A6FF6E),
        ghost = Color(0x14A6FF6E), decoration = LookDecoration.SCANLINES, squareControls = true
    )
    FaceLookKind.AMBER -> FaceLook(
        kind, background = Brush.radialGradient(listOf(Color(0xFF1A1206), Color(0xFF070503))),
        ink = Color(0xFFFFB23F), dim = Color(0xFF9A6A22), accent = Color(0xFFFFB23F), accent2 = Color(0xFFFFD27F),
        warn = Color(0xFFFF5A36), track = Color(0x1FFFB23F), fill = Color.Transparent, onAccent = Color(0xFF070503),
        border = Color(0xFF3A2A12), radius = 8.dp, font = Mono, numFont = Mono, numWeight = FontWeight.SemiBold,
        glow = Color(0x88FFB23F), decoration = LookDecoration.SCANLINES, squareControls = true
    )
    FaceLookKind.NEON -> FaceLook(
        kind, background = Brush.radialGradient(listOf(Color(0xFF2A0A3A), Color(0xFF07030F))),
        ink = Color(0xFFF6F0FF), dim = Color(0xFFA99BC9), accent = Color(0xFFFF2BD6), accent2 = Color(0xFF25F4EE),
        warn = Color(0xFFFF4D6D), track = Color(0x14FFFFFF), fill = Color(0x0AFFFFFF), onAccent = Color(0xFF07030F),
        border = Color(0xFFFF2BD6), borderWidth = 1.5.dp, radius = 20.dp, font = Sans, numFont = CondensedFamily,
        numWeight = FontWeight.Bold, glow = Color(0xFFFF2BD6), decoration = LookDecoration.NEON
    )
    FaceLookKind.PAPER -> FaceLook(
        kind, background = Brush.verticalGradient(listOf(Color(0xFFE6E6E0), Color(0xFFDFDFD8))),
        ink = Color(0xFF151515), dim = Color(0xFF5C5C57), accent = Color(0xFF151515), accent2 = Color(0xFF151515),
        warn = Color(0xFF151515), track = Color(0x1F151515), fill = Color.Transparent, onAccent = Color(0xFFE3E3DD),
        border = Color(0xFFC9C9C1), radius = 6.dp, font = Serif, numFont = Serif, numWeight = FontWeight.SemiBold,
        squareControls = true
    )
    FaceLookKind.GLASS -> FaceLook(
        kind, background = Brush.linearGradient(listOf(Color(0xFF1A2748), Color(0xFF0C1226))),
        ink = Color.White, dim = Color(0xFFC3CDE6), accent = Color(0xFF7FE3FF), accent2 = Color(0xFFB7A2FF),
        warn = Color(0xFFFF8FA3), track = Color(0x22FFFFFF), fill = Color(0x1FFFFFFF), onAccent = Color(0xFF0C1226),
        border = Color(0x30FFFFFF), radius = 26.dp, font = Sans, numFont = Sans, numWeight = FontWeight.Light,
        labelWeight = FontWeight.Medium, decoration = LookDecoration.GLASS
    )
    FaceLookKind.CARBON -> FaceLook(
        kind, background = Brush.verticalGradient(listOf(Color(0xFF151515), Color(0xFF0F0F0F))),
        ink = Color(0xFFF4F4F4), dim = Color(0xFF9A9A9A), accent = Color(0xFFFF2E3F), accent2 = Color(0xFFFF8A3D),
        warn = Color(0xFFFFD23F), track = Color(0x14FFFFFF), fill = Color(0x0FFFFFFF), onAccent = Color.White,
        border = Color(0xFF2A2A2A), radius = 12.dp, font = CondensedFamily, numFont = CondensedFamily,
        numWeight = FontWeight.ExtraBold, numItalic = true, decoration = LookDecoration.CARBON, squareControls = true
    )
    FaceLookKind.CHROME -> FaceLook(
        kind, background = Brush.radialGradient(listOf(Color(0xFF2A2F36), Color(0xFF121417))),
        ink = Color(0xFFF3F3F3), dim = Color(0xFF9CA3AD), accent = Color(0xFFFF6A2B), accent2 = Color(0xFFFFB36B),
        warn = Color(0xFFFF3B30), track = Color(0x1AFFFFFF), fill = Color(0x0FFFFFFF), onAccent = Color(0xFF121417),
        border = Color(0xFFB9BFC7), borderWidth = 4.dp, radius = 24.dp, font = CondensedFamily, numFont = CondensedFamily,
        numWeight = FontWeight.Bold, decoration = LookDecoration.CHROME
    )
    FaceLookKind.FLAP -> FaceLook(
        kind, background = Brush.verticalGradient(listOf(Color(0xFF111111), Color(0xFF0B0B0B))),
        ink = Color(0xFFF2F2F2), dim = Color(0xFF8D8D8D), accent = Color(0xFFFFD23F), accent2 = Color(0xFFFFE58A),
        warn = Color(0xFFFF5A4E), track = Color(0x14FFFFFF), fill = Color(0xFF1C1C1C), onAccent = Color(0xFF0D0D0D),
        border = Color(0xFF262626), radius = 10.dp, font = CondensedFamily, numFont = Mono, numWeight = FontWeight.Bold,
        squareControls = true
    )
    FaceLookKind.DOTS -> FaceLook(
        kind, background = Brush.verticalGradient(listOf(Color(0xFF0A0A0A), Color(0xFF050505))),
        ink = Color(0xFFF5F5F5), dim = Color(0xFF8A8A8A), accent = Color(0xFFFF3B30), accent2 = Color(0xFFF5F5F5),
        warn = Color(0xFFFF3B30), track = Color(0x1FFFFFFF), fill = Color(0x14FFFFFF), onAccent = Color.White,
        border = Color(0xFF1C1C1C), radius = 22.dp, font = Mono, numFont = Mono, numWeight = FontWeight.Medium,
        labelWeight = FontWeight.Medium, decoration = LookDecoration.DOTS
    )
    // Midnight black with copper, the sport cars' cabin at night.
    FaceLookKind.COPPER -> FaceLook(
        kind, background = Brush.linearGradient(listOf(Color(0xFF1A1714), Color(0xFF0A0908), Color(0xFF050505))),
        ink = Color(0xFFF5F0EA), dim = Color(0xFF9E958C), accent = Color(0xFFC9814F), accent2 = Color(0xFFF0C29A),
        warn = Color(0xFFFF4A3D), track = Color(0x1FF5F0EA), fill = Color(0x1AC9814F), onAccent = Color(0xFF0A0908),
        border = Color(0x40C9814F), radius = 18.dp, font = Sans, numFont = Sans, numWeight = FontWeight.Medium,
        labelWeight = FontWeight.SemiBold, glow = Color(0x55C9814F), decoration = LookDecoration.TRIANGLES,
        squareControls = true, angular = true
    )
    // Matte petrol blue with the same copper, the electric SUV's paint.
    FaceLookKind.PETROL -> FaceLook(
        kind, background = Brush.linearGradient(listOf(Color(0xFF123238), Color(0xFF0A1F24), Color(0xFF061114))),
        ink = Color(0xFFEFF6F5), dim = Color(0xFF8EACAD), accent = Color(0xFFD08A58), accent2 = Color(0xFFF3C9A0),
        warn = Color(0xFFFF5C4D), track = Color(0x1FEFF6F5), fill = Color(0x14EFF6F5), onAccent = Color(0xFF061114),
        border = Color(0x33D08A58), radius = 18.dp, font = Sans, numFont = CondensedFamily, numWeight = FontWeight.Bold,
        labelWeight = FontWeight.SemiBold, decoration = LookDecoration.TRIANGLES, squareControls = true, angular = true
    )
    // Luxury classic: midnight navy, champagne gold hairlines, thin numerals.
    FaceLookKind.NAVY_GOLD -> FaceLook(
        kind, background = Brush.linearGradient(listOf(Color(0xFF1A2A50), Color(0xFF0F1B36), Color(0xFF0A1226))),
        ink = Color(0xFFF7F2E7), dim = Color(0xFFAFB6C9), accent = Color(0xFFE3B96C), accent2 = Color(0xFFF7DEAA),
        warn = Color(0xFFFF6B5E), track = Color(0x24F7F2E7), fill = Color(0x1AE3B96C), onAccent = Color(0xFF0F1B36),
        border = Color(0x99E3B96C), radius = 20.dp, font = Sans, numFont = Sans, numWeight = FontWeight.Light,
        labelWeight = FontWeight.Medium, glow = Color(0x40E3B96C), decoration = LookDecoration.PINSTRIPE
    )
    // Eco leaf: frosted sage green, mint readings, a pale leaf in the corner.
    FaceLookKind.SAGE -> FaceLook(
        kind, background = Brush.linearGradient(listOf(Color(0xFF557462), Color(0xFF3E5A4A), Color(0xFF30473A))),
        ink = Color(0xFFF4FAF5), dim = Color(0xFFCCDDD1), accent = Color(0xFFB4EDC8), accent2 = Color(0xFFE4F7C4),
        warn = Color(0xFFFFB199), track = Color(0x33F4FAF5), fill = Color(0x1FFFFFFF), onAccent = Color(0xFF1F3629),
        border = Color(0x4DE4F7C4), radius = 22.dp, font = Sans, numFont = Sans, numWeight = FontWeight.Normal,
        labelWeight = FontWeight.Medium, decoration = LookDecoration.LEAF
    )
    // Cyber sport: carbon weave, a crimson-to-magenta edge light, italic condensed numerals.
    FaceLookKind.CRIMSON -> FaceLook(
        kind, background = Brush.verticalGradient(listOf(Color(0xFF170A10), Color(0xFF0A0609))),
        ink = Color(0xFFFFF4F7), dim = Color(0xFFB9A5AD), accent = Color(0xFFFF2D55), accent2 = Color(0xFFFF4FD8),
        warn = Color(0xFFFFC23F), track = Color(0x1FFFFFFF), fill = Color(0x1AFF2D55), onAccent = Color.White,
        border = Color(0xCCFF2D55), borderWidth = 1.5.dp, radius = 14.dp, font = CondensedFamily, numFont = CondensedFamily,
        numWeight = FontWeight.Bold, numItalic = true, glow = Color(0xAAFF2D55), decoration = LookDecoration.EDGE_LIGHT,
        squareControls = true
    )
    // Nordic minimal: graphite and ice, hairlines, nothing that shouts.
    FaceLookKind.GRAPHITE -> FaceLook(
        kind, background = Brush.verticalGradient(listOf(Color(0xFF2F3237), Color(0xFF25282C))),
        ink = Color(0xFFF1F3F5), dim = Color(0xFF9EA4AC), accent = Color(0xFFC6D6E4), accent2 = Color(0xFF8EA6BC),
        warn = Color(0xFFFF7A6B), track = Color(0x1FFFFFFF), fill = Color(0x12FFFFFF), onAccent = Color(0xFF25282C),
        border = Color(0x1FFFFFFF), radius = 18.dp, font = Sans, numFont = Sans, numWeight = FontWeight.ExtraLight,
        labelWeight = FontWeight.Medium
    )
}
