package com.openauto.dash

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Sections of the "Add widget" catalogue; [titleRes] is the section heading. */
enum class WidgetCategory(@StringRes val titleRes: Int) {
    DRIVING(R.string.apps_category_driving),
    NAVIGATION(R.string.apps_category_navigation),
    VEHICLE(R.string.apps_category_vehicle),
    INFO(R.string.apps_category_info),
    APPS(R.string.apps_category_apps)
}

/**
 * The kinds of built-in (app-provided) widgets a dashboard tile can show. These
 * are rendered by our own Compose panels, not by the Android app-widget host.
 * [coarseW] x [coarseH] is the span a fresh tile gets, in the coarse cells of
 * the 12 x 7 grid ([defaultW] x [defaultH] in the grid's own cells). Names are
 * persisted, so never rename an entry. [labelRes] / [blurbRes] are the
 * picker's display name and one-line description (read them in Compose as
 * `label` / `blurb`).
 */
enum class BuiltinKind(
    @StringRes val labelRes: Int,
    val category: WidgetCategory,
    @StringRes val blurbRes: Int,
    val coarseW: Int = 5,
    val coarseH: Int = 3
) {
    NAVMAP(R.string.apps_kind_navmap, WidgetCategory.NAVIGATION, R.string.apps_kind_navmap_blurb),
    NAVIGATION(R.string.apps_kind_navigation, WidgetCategory.NAVIGATION, R.string.apps_kind_navigation_blurb, 4, 3),
    PIP_ANCHOR(R.string.apps_kind_pip_anchor, WidgetCategory.NAVIGATION, R.string.apps_kind_pip_anchor_blurb, 4, 3),
    // Google Maps itself inside the tile, where the firmware grants system permissions (EmbeddedApp.kt).
    MAPS_INSIDE(R.string.apps_kind_maps_inside, WidgetCategory.NAVIGATION, R.string.apps_kind_maps_inside_blurb),
    MEDIA(R.string.apps_kind_media, WidgetCategory.APPS, R.string.apps_kind_media_blurb),
    TELEMETRY(R.string.apps_kind_telemetry, WidgetCategory.VEHICLE, R.string.apps_kind_telemetry_blurb),
    OBD_DTC(R.string.apps_kind_obd_dtc, WidgetCategory.VEHICLE, R.string.apps_kind_obd_dtc_blurb, 3, 2),
    OBD_ALL(R.string.apps_kind_obd_all, WidgetCategory.VEHICLE, R.string.apps_kind_obd_all_blurb),
    RANGE(R.string.apps_kind_range, WidgetCategory.VEHICLE, R.string.apps_kind_range_blurb, 3, 3),
    DOORS(R.string.apps_kind_doors, WidgetCategory.VEHICLE, R.string.apps_kind_doors_blurb, 3, 2),
    CAN_MON(R.string.apps_kind_can_mon, WidgetCategory.VEHICLE, R.string.apps_kind_can_mon_blurb, 4, 3),
    SPEED_HUD(R.string.apps_kind_speed_hud, WidgetCategory.DRIVING, R.string.apps_kind_speed_hud_blurb, 3, 2),
    COMPASS(R.string.apps_kind_compass, WidgetCategory.DRIVING, R.string.apps_kind_compass_blurb, 3, 3),
    TRIP(R.string.apps_kind_trip, WidgetCategory.DRIVING, R.string.apps_kind_trip_blurb, 4, 2),
    GFORCE(R.string.apps_kind_gforce, WidgetCategory.DRIVING, R.string.apps_kind_gforce_blurb, 4, 2),
    PARKING(R.string.apps_kind_parking, WidgetCategory.NAVIGATION, R.string.apps_kind_parking_blurb, 4, 2),
    CLOCK(R.string.apps_kind_clock, WidgetCategory.INFO, R.string.apps_kind_clock_blurb, 3, 2),
    WEATHER(R.string.apps_kind_weather, WidgetCategory.INFO, R.string.apps_kind_weather_blurb, 4, 2),
    CALENDAR(R.string.apps_kind_calendar, WidgetCategory.INFO, R.string.apps_kind_calendar_blurb, 4, 3),
    QUICK_DIAL(R.string.apps_kind_quick_dial, WidgetCategory.INFO, R.string.apps_kind_quick_dial_blurb, 4, 2),
    NOTIFICATIONS(R.string.apps_kind_notifications, WidgetCategory.INFO, R.string.apps_kind_notifications_blurb, 4, 3),
    AUDIO(R.string.apps_kind_audio, WidgetCategory.APPS, R.string.apps_kind_audio_blurb, 3, 2),
    // Car care, tailored by the car profile (CarCareTiles.kt).
    FILTER_CARE(R.string.car_kind_filter, WidgetCategory.VEHICLE, R.string.car_kind_filter_blurb, 4, 3),
    WARMUP(R.string.car_kind_warmup, WidgetCategory.VEHICLE, R.string.car_kind_warmup_blurb, 3, 3),
    BATTERY(R.string.car_kind_battery, WidgetCategory.VEHICLE, R.string.car_kind_battery_blurb, 3, 3),
    MY_CAR(R.string.car_kind_my_car, WidgetCategory.VEHICLE, R.string.car_kind_my_car_blurb, 4, 3),
    ECO_DRIVE(R.string.car_kind_eco, WidgetCategory.DRIVING, R.string.car_kind_eco_blurb, 4, 3),
    BREAK_TIMER(R.string.car_kind_break, WidgetCategory.DRIVING, R.string.car_kind_break_blurb, 3, 3),
    FUEL_TO_DEST(R.string.car_kind_fuel_dest, WidgetCategory.VEHICLE, R.string.car_kind_fuel_dest_blurb, 3, 3),
    // Servicing planner (Maintenance.kt) and fuel prices around the car (FuelPrices.kt).
    SERVICE(R.string.upkeep_kind, WidgetCategory.VEHICLE, R.string.upkeep_kind_blurb, 4, 3),
    FUEL_PRICES(R.string.fuel_kind, WidgetCategory.VEHICLE, R.string.fuel_kind_blurb, 4, 3),
    // The car's own data from its CAN box, on the QF firmware (CarBox.kt).
    CAR_STATUS(R.string.car_kind_status, WidgetCategory.VEHICLE, R.string.car_kind_status_blurb, 4, 3),
    // The TPMS sensors, through the head unit's TPMS app or a USB receiver (Tyres.kt, UsbTpms.kt).
    TYRES(R.string.car_kind_tyres, WidgetCategory.VEHICLE, R.string.car_kind_tyres_blurb, 4, 3),
    // The dashboard bar: dashboards and apps, one tap each, as in the Canvas rail (CanvasTabs.kt).
    DASH_BAR(R.string.apps_kind_dash_bar, WidgetCategory.APPS, R.string.apps_kind_dash_bar_blurb, 1, 5),
    // The head unit's CPU, memory, temperature and busiest apps (HeadUnitMonitor.kt).
    HEAD_UNIT(R.string.monitor_kind, WidgetCategory.INFO, R.string.monitor_kind_blurb, 4, 3),
    // The gear engaged, from reverse and the revs against the speed (GearEstimator.kt).
    GEAR(R.string.gear_kind, WidgetCategory.DRIVING, R.string.gear_kind_blurb, 3, 2),
    // The second screen's backlight, day or night level, for a display wired to its buttons (SecondScreenBrightness.kt).
    SCREEN_LIGHT(R.string.apps_kind_screen_light, WidgetCategory.INFO, R.string.apps_kind_screen_light_blurb, 3, 2),
    // The head unit's Wi-Fi and Bluetooth, on and off (RadioSwitches.kt).
    RADIOS(R.string.apps_kind_radios, WidgetCategory.INFO, R.string.apps_kind_radios_blurb, 3, 2),
    // The widgets of 2026-10-05, each in its own file (UnitLight.kt, SpeedCameras.kt, RadioPresets.kt, ...).
    UNIT_LIGHT(R.string.widgets_light, WidgetCategory.INFO, R.string.widgets_light_blurb, 3, 2),
    SPEED_CAMERAS(R.string.widgets_cam, WidgetCategory.DRIVING, R.string.widgets_cam_blurb, 3, 2),
    RADIO_PRESETS(R.string.widgets_radio, WidgetCategory.APPS, R.string.widgets_radio_blurb, 4, 3),
    HOME_WORK(R.string.widgets_hw, WidgetCategory.NAVIGATION, R.string.widgets_hw_blurb, 4, 2),
    SHARE_ETA(R.string.widgets_eta, WidgetCategory.NAVIGATION, R.string.widgets_eta_blurb, 3, 3),
    VOICE_NOTES(R.string.widgets_notes, WidgetCategory.INFO, R.string.widgets_notes_blurb, 3, 3),
    QUICK_SWITCHES(R.string.widgets_switches, WidgetCategory.INFO, R.string.widgets_switches_blurb, 5, 2),
    GPS_STATUS(R.string.widgets_gps, WidgetCategory.DRIVING, R.string.widgets_gps_blurb, 4, 2),
    PERF_TIMER(R.string.widgets_perf, WidgetCategory.DRIVING, R.string.widgets_perf_blurb, 4, 2),
    WIFI_NETWORKS(R.string.widgets_wifi, WidgetCategory.INFO, R.string.widgets_wifi_blurb, 4, 3),
    BT_DEVICES(R.string.widgets_bt, WidgetCategory.INFO, R.string.widgets_bt_blurb, 4, 3),
    // The second batch of 2026-10-05 (SpeedLimit.kt, ParkingTimerTile.kt, FuelLogTile.kt, ...).
    SPEED_LIMIT(R.string.widgets_limit, WidgetCategory.DRIVING, R.string.widgets_limit_blurb, 3, 2),
    PARKING_TIMER(R.string.widgets_park, WidgetCategory.NAVIGATION, R.string.widgets_park_blurb, 3, 2),
    FUEL_LOG(R.string.widgets_fuel, WidgetCategory.VEHICLE, R.string.widgets_fuel_blurb, 4, 3),
    CONSUMPTION(R.string.widgets_consumption, WidgetCategory.VEHICLE, R.string.widgets_consumption_blurb, 4, 2),
    WEATHER_ALERTS(R.string.widgets_wx, WidgetCategory.INFO, R.string.widgets_wx_blurb, 4, 2),
    ENGINE_TEMPS(R.string.widgets_temps, WidgetCategory.VEHICLE, R.string.widgets_temps_blurb, 4, 2),
    COMMUTE(R.string.widgets_commute, WidgetCategory.NAVIGATION, R.string.widgets_commute_blurb, 4, 2),
    // The second tank of an LPG car, its level worked out (LpgTank.kt).
    LPG_TANK(R.string.lpg_kind, WidgetCategory.VEHICLE, R.string.lpg_kind_blurb, 4, 3),
    // The driver's own OBD requests and formulas (MyReadings.kt).
    MY_READINGS(R.string.mine_title, WidgetCategory.VEHICLE, R.string.mine_kind_blurb, 4, 2),
    // The last drives the drive log kept, with this week's total (DriveHistoryTile.kt).
    DRIVE_HISTORY(R.string.widgets_history, WidgetCategory.DRIVING, R.string.widgets_history_blurb, 4, 3),
    // The one thing that matters right now, picked from the other feeds (NowCard.kt).
    NOW(R.string.widgets_now, WidgetCategory.DRIVING, R.string.widgets_now_blurb, 5, 2);

    /** The span a fresh tile gets, in the grid's cells. */
    val defaultW: Int get() = coarseW * GRID_SCALE
    val defaultH: Int get() = coarseH * GRID_SCALE
}

/**
 * Kinds the add sheet no longer offers, for being another tile twice over or
 * showing nothing useful in the car: All OBD data is Engine gauges in another
 * order; the Maps window and Google Maps inside are the Windows tab's own two
 * ways to add Maps; My car is a Settings page; the parking spot measured the
 * car against itself (the spot is kept at switch-off and sent to the phone
 * anyway). Tiles already on a dashboard keep working.
 */
private val LEFT_OUT_OF_CATALOG = setOf(
    BuiltinKind.OBD_ALL, BuiltinKind.PIP_ANCHOR, BuiltinKind.MAPS_INSIDE, BuiltinKind.MY_CAR, BuiltinKind.PARKING
)

/** Whether the add sheet offers this kind (see [LEFT_OUT_OF_CATALOG]); My car comes back once it has the driver's own car to show (MyCarLook.kt). */
internal val BuiltinKind.offered: Boolean get() = this !in LEFT_OUT_OF_CATALOG || (this == BuiltinKind.MY_CAR && MyCarLook.hasLook)

/**
 * One tile on a dashboard page, placed freely on a [GRID_COLS] x [GRID_ROWS]
 * grid. [x],[y] are the top-left cell (0-based) and [w],[h] are the span in
 * cells. Tiles may be moved and resized to any cell rectangle that fits.
 *
 *  - [AppShortcut]   a small icon that launches an installed app,
 *  - [SplitPair]     launches two apps side-by-side in split-screen,
 *  - [LaunchBar]     an editable row of app icons (a dock),
 *  - [BuiltinWidget] one of our own cards (map / media / OBD / directions),
 *  - [SystemWidget]  a real Android app-widget, hosted via [WidgetHostHolder],
 *  - [AppWindow]     any installed app running in a window the size of the tile,
 *                    or inside the tile itself on units that allow it ([AppWindow.inside]).
 *
 * [zoom] scales what the tile draws (text, icons, spacing) inside its cells,
 * like the system's display size but for this tile alone; 1 is as designed.
 * [frame] is how the tile sits among the others and what frames it: in the
 * grid with them or over them, its opacity, its border ([TileFrame]).
 */
sealed interface DashboardItem {
    val x: Int
    val y: Int
    val w: Int
    val h: Int
    val zoom: Float
    val frame: TileFrame

    data class AppShortcut(
        val packageName: String,
        override val x: Int = 0, override val y: Int = 0,
        override val w: Int = 2 * GRID_SCALE, override val h: Int = 2 * GRID_SCALE,
        override val zoom: Float = 1f,
        override val frame: TileFrame = TileFrame.NONE
    ) : DashboardItem

    data class SplitPair(
        val primaryPackage: String,
        val secondaryPackage: String,
        override val x: Int = 0, override val y: Int = 0,
        override val w: Int = 2 * GRID_SCALE, override val h: Int = 2 * GRID_SCALE,
        override val zoom: Float = 1f,
        override val frame: TileFrame = TileFrame.NONE
    ) : DashboardItem

    data class LaunchBar(
        val packages: List<String> = emptyList(),
        override val x: Int = 0, override val y: Int = 0,
        override val w: Int = 8 * GRID_SCALE, override val h: Int = GRID_SCALE,
        override val zoom: Float = 1f,
        override val frame: TileFrame = TileFrame.NONE
    ) : DashboardItem

    /** [design] is this tile's own look (see WidgetDesigns.kt); two clocks may differ. */
    data class BuiltinWidget(
        val kind: BuiltinKind,
        override val x: Int = 0, override val y: Int = 0,
        override val w: Int = 5 * GRID_SCALE, override val h: Int = 3 * GRID_SCALE,
        val design: WidgetDesign = WidgetDesign.STANDARD,
        override val zoom: Float = 1f,
        override val frame: TileFrame = TileFrame.NONE
    ) : DashboardItem

    data class SystemWidget(
        val appWidgetId: Int,
        override val x: Int = 0, override val y: Int = 0,
        override val w: Int = 5 * GRID_SCALE, override val h: Int = 3 * GRID_SCALE,
        override val zoom: Float = 1f,
        override val frame: TileFrame = TileFrame.NONE
    ) : DashboardItem

    /**
     * An app (YouTube Music, Waze, ...) docked as a floating window over this
     * tile, like the Maps window, or with [inside] running inside the tile
     * itself, like Google Maps inside (EmbeddedApp).
     */
    data class AppWindow(
        val packageName: String,
        override val x: Int = 0, override val y: Int = 0,
        override val w: Int = 5 * GRID_SCALE, override val h: Int = 3 * GRID_SCALE,
        override val zoom: Float = 1f,
        val inside: Boolean = false,
        override val frame: TileFrame = TileFrame.NONE
    ) : DashboardItem
}

/**
 * How a tile sits among the others and what frames it. A tile that
 * [overlap]s leaves the grid's one-tile-per-cell rule: it may cover the
 * tiles in the grid and be covered by another such tile, is drawn above the
 * grid's tiles (the later of two in the page's order on top), never pushes
 * one aside and never blocks a cell for a new tile. [opacity] is how solid
 * the whole tile is drawn, [OPACITY_MIN]..1; [border] its edge's width in dp,
 * 0 for none, up to [BORDER_MAX], in [borderColor]. Persisted; the defaults
 * write nothing, so a plain tile's layout reads as before.
 */
data class TileFrame(
    val overlap: Boolean = false,
    val opacity: Float = 1f,
    val border: Int = 0,
    val borderColor: TileBorderColor = TileBorderColor.ACCENT
) {
    companion object {
        val NONE = TileFrame()
    }
}

/** A tile's border colour; the names are persisted, so never rename one. */
enum class TileBorderColor(@StringRes val labelRes: Int) {
    ACCENT(R.string.frame_colour_accent),
    TEXT(R.string.frame_colour_text),
    WHITE(R.string.frame_colour_white),
    BLACK(R.string.frame_colour_black),
    RED(R.string.frame_colour_red),
    YELLOW(R.string.frame_colour_yellow),
    GREEN(R.string.frame_colour_green),
    BLUE(R.string.frame_colour_blue);

    companion object {
        fun fromName(name: String?): TileBorderColor = entries.firstOrNull { it.name == name } ?: ACCENT
    }
}

/** How faint a tile may be drawn, and by how much per tap. */
const val OPACITY_MIN = 0.2f
const val OPACITY_STEP = 0.1f

/** The opacity one step down ([steps] < 0) or up (> 0) from [opacity], on whole tenths so it never drifts. */
fun opacityStep(opacity: Float, steps: Int): Float {
    val tenths = kotlin.math.round(opacity * 10f).toInt() + steps
    return (tenths / 10f).coerceIn(OPACITY_MIN, 1f)
}

/** The widest border a tile gets, in dp. */
const val BORDER_MAX = 8

/** A tile's [frame], with its values kept in range. */
fun TileFrame.clamped(): TileFrame = copy(
    opacity = opacity.takeIf { it.isFinite() }?.coerceIn(OPACITY_MIN, 1f) ?: 1f,
    border = border.coerceIn(0, BORDER_MAX)
)

/**
 * The coarse grid the dashboards were designed on: 12 cells across, 7 down.
 * Templates, the skins' fixed layouts and the AI designer still think in it;
 * the tiles are placed on the finer grid below.
 */
const val COARSE_COLS = 12
const val COARSE_ROWS = 7

/**
 * How many of the grid's cells make one coarse cell, each way: the tiles
 * snap to a third of what they used to, so one can be set exactly where it
 * belongs, over another tile too (TileFrame). Saved layouts from before are
 * scaled up on load ([DashboardStore.SCHEMA_VERSION] 3).
 */
const val GRID_SCALE = 3

/** The dashboard grid: 36 cells across, 21 down; the other way round on an upright screen ([ScreenShape]). */
val GRID_COLS: Int get() = (if (ScreenShape.vertical) COARSE_ROWS else COARSE_COLS) * GRID_SCALE
val GRID_ROWS: Int get() = (if (ScreenShape.vertical) COARSE_COLS else COARSE_ROWS) * GRID_SCALE

/** Compact icon tiles (shortcuts / split pairs) vs. larger widget cards. */
fun DashboardItem.isCompactTile(): Boolean =
    this is DashboardItem.AppShortcut || this is DashboardItem.SplitPair

/**
 * Smallest span a built-in kind stays readable at, width to height, in the
 * grid's cells ([coarseMinSize] times [GRID_SCALE]). The resize handle, the
 * templates and the placer never go under it; a saved tile that is smaller
 * still loads at its size.
 */
fun BuiltinKind.minSize(): Pair<Int, Int> = coarseMinSize().let { (w, h) -> w * GRID_SCALE to h * GRID_SCALE }

/**
 * [minSize] in the coarse cells of the 12 x 7 grid: a number tile needs two
 * cells each way, a list three across, the map three by three.
 */
fun BuiltinKind.coarseMinSize(): Pair<Int, Int> = when (this) {
    BuiltinKind.SPEED_HUD, BuiltinKind.SPEED_LIMIT, BuiltinKind.GEAR, BuiltinKind.CLOCK -> 2 to 2
    // Lists: rows need their width.
    BuiltinKind.CALENDAR, BuiltinKind.NOTIFICATIONS, BuiltinKind.QUICK_DIAL, BuiltinKind.FUEL_PRICES, BuiltinKind.WIFI_NETWORKS,
    BuiltinKind.BT_DEVICES, BuiltinKind.FUEL_LOG, BuiltinKind.RADIO_PRESETS, BuiltinKind.VOICE_NOTES, BuiltinKind.CAN_MON,
    BuiltinKind.SHARE_ETA, BuiltinKind.DRIVE_HISTORY -> 3 to 2
    BuiltinKind.NAVMAP, BuiltinKind.MAPS_INSIDE, BuiltinKind.PIP_ANCHOR -> 3 to 3
    BuiltinKind.DASH_BAR -> 1 to 3
    BuiltinKind.QUICK_SWITCHES -> 3 to 1
    // One card of words: a sentence needs the width.
    BuiltinKind.NOW -> 3 to 2
    // A gauge or a picture with a few lines under it.
    BuiltinKind.TELEMETRY, BuiltinKind.OBD_ALL, BuiltinKind.OBD_DTC, BuiltinKind.RANGE, BuiltinKind.DOORS, BuiltinKind.COMPASS,
    BuiltinKind.GFORCE, BuiltinKind.NAVIGATION, BuiltinKind.MEDIA, BuiltinKind.FILTER_CARE, BuiltinKind.MY_CAR, BuiltinKind.ECO_DRIVE,
    BuiltinKind.SERVICE, BuiltinKind.CAR_STATUS, BuiltinKind.TYRES, BuiltinKind.HEAD_UNIT -> 2 to 2
    // One reading and a caption.
    else -> 2 to 1
}

/**
 * Smallest span this tile may be resized to where it sits: its kind's
 * [minSize] for a built-in widget, one coarse cell ([GRID_SCALE]) for the
 * rest, and never more than the room left to the grid's edge, so a saved tile
 * smaller than its kind's minimum is kept where it is rather than pushed off
 * or dropped.
 */
fun DashboardItem.minW(): Int =
    (if (this is DashboardItem.BuiltinWidget) kind.minSize().first else GRID_SCALE).coerceAtMost(GRID_COLS - x).coerceAtLeast(1)

fun DashboardItem.minH(): Int =
    (if (this is DashboardItem.BuiltinWidget) kind.minSize().second else GRID_SCALE).coerceAtMost(GRID_ROWS - y).coerceAtLeast(1)

/** How far a tile's content can be zoomed out and in, and by how much per tap. */
const val ZOOM_MIN = 0.5f
const val ZOOM_MAX = 2f
const val ZOOM_STEP = 0.1f

/** The zoom one step out ([steps] < 0) or in (> 0) from [zoom], on whole tenths so it never drifts. */
fun zoomStep(zoom: Float, steps: Int): Float {
    val tenths = kotlin.math.round(zoom * 10f).toInt() + steps
    return (tenths / 10f).coerceIn(ZOOM_MIN, ZOOM_MAX)
}

/**
 * Whether zooming changes anything: a system widget draws itself. An app
 * inside its tile is drawn at the tile's density, so it does; a window tile
 * too where apps can run inside ([insideAllowed]), see [runsInside].
 */
fun DashboardItem.canZoom(insideAllowed: Boolean = false): Boolean =
    !(this is DashboardItem.AppWindow && !inside && !insideAllowed) && this !is DashboardItem.SystemWidget

/**
 * Whether this tile's app runs inside it: added that way, or a window tile
 * zoomed where apps can run inside ([insideAllowed], EmbeddedApp.allowed).
 * A floating window always has the whole screen's density, so an app gets a
 * size of its own (Waze bigger) only on the tile's own display.
 */
fun DashboardItem.AppWindow.runsInside(insideAllowed: Boolean): Boolean =
    inside || (insideAllowed && zoom != 1f)

/**
 * Whether the frame's layer controls change anything: a floating window is a
 * window of its own over everything, so neither overlapping nor opacity nor a
 * border reaches it; an app inside its tile is drawn with the tile.
 */
fun DashboardItem.canFrame(insideAllowed: Boolean = false): Boolean =
    !(this is DashboardItem.AppWindow && !runsInside(insideAllowed))

/** Returns a copy framed as [frame] (its values kept in range, see [TileFrame.clamped]). */
fun DashboardItem.withFrame(frame: TileFrame): DashboardItem {
    val f = frame.clamped()
    return when (this) {
        is DashboardItem.AppShortcut -> copy(frame = f)
        is DashboardItem.SplitPair -> copy(frame = f)
        is DashboardItem.LaunchBar -> copy(frame = f)
        is DashboardItem.BuiltinWidget -> copy(frame = f)
        is DashboardItem.SystemWidget -> copy(frame = f)
        is DashboardItem.AppWindow -> copy(frame = f)
    }
}

/** Returns a copy whose content is drawn at [zoom] (clamped to [ZOOM_MIN]..[ZOOM_MAX]). */
fun DashboardItem.withZoom(zoom: Float): DashboardItem {
    val z = zoom.coerceIn(ZOOM_MIN, ZOOM_MAX)
    return when (this) {
        is DashboardItem.AppShortcut -> copy(zoom = z)
        is DashboardItem.SplitPair -> copy(zoom = z)
        is DashboardItem.LaunchBar -> copy(zoom = z)
        is DashboardItem.BuiltinWidget -> copy(zoom = z)
        is DashboardItem.SystemWidget -> copy(zoom = z)
        is DashboardItem.AppWindow -> copy(zoom = z)
    }
}

/**
 * Returns a copy placed at cell [x],[y] spanning [w] x [h], clamped to the
 * grid. One cell is the floor here, not the kind's [minSize]: a saved layout
 * with a smaller tile loads as it was (the resize handle and the placer pass
 * the kind's minimum themselves).
 */
fun DashboardItem.withCell(x: Int, y: Int, w: Int, h: Int): DashboardItem {
    val cw = w.coerceIn(1, GRID_COLS)
    val ch = h.coerceIn(1, GRID_ROWS)
    val cx = x.coerceIn(0, GRID_COLS - cw)
    val cy = y.coerceIn(0, GRID_ROWS - ch)
    return when (this) {
        is DashboardItem.AppShortcut -> copy(x = cx, y = cy, w = cw, h = ch)
        is DashboardItem.SplitPair -> copy(x = cx, y = cy, w = cw, h = ch)
        is DashboardItem.LaunchBar -> copy(x = cx, y = cy, w = cw, h = ch)
        is DashboardItem.BuiltinWidget -> copy(x = cx, y = cy, w = cw, h = ch)
        is DashboardItem.SystemWidget -> copy(x = cx, y = cy, w = cw, h = ch)
        is DashboardItem.AppWindow -> copy(x = cx, y = cy, w = cw, h = ch)
    }
}

/** True when the two tiles' cell rectangles share at least one cell. */
fun DashboardItem.overlaps(other: DashboardItem): Boolean =
    DashboardStore.rectanglesOverlap(x, y, w, h, other.x, other.y, other.w, other.h)

/** A tile in the grid, which holds its cells: not one over the others ([TileFrame.overlap]). */
fun DashboardItem.inGrid(): Boolean = !frame.overlap

/**
 * The dashboards that are swiped: the pages of [DashboardStore.ROW] and
 * [DashboardStore.COLUMN] the driver kept, in their order, Home in both.
 * Only Home: nothing to swipe.
 */
internal data class ShownCross(val row: List<Int>, val column: List<Int>) {
    /** Home's index in [row], and in [column]. */
    val rowHome: Int get() = row.indexOf(DashboardStore.CENTER)
    val columnHome: Int get() = column.indexOf(DashboardStore.CENTER)

    operator fun contains(page: Int): Boolean = page in row || page in column

    /** The page at these pager indices: the column's, when the row is on Home. */
    fun pageAt(rowIndex: Int, columnIndex: Int): Int {
        val inRow = row.getOrElse(rowIndex) { DashboardStore.CENTER }
        return if (inRow == DashboardStore.CENTER) column.getOrElse(columnIndex) { DashboardStore.CENTER } else inRow
    }

    /** The pager indices that bring [page] on screen; Home's for a page not in the cross. */
    fun rowIndexOf(page: Int): Int = row.indexOf(page).takeIf { it >= 0 } ?: rowHome
    fun columnIndexOf(page: Int): Int = column.indexOf(page).takeIf { it >= 0 } ?: columnHome
}

/**
 * Persists the seven dashboards (each an ordered list of [DashboardItem])
 * to SharedPreferences as JSON, one arrangement per screen direction. The layout is the user's, so it survives restarts.
 *
 * All grid rules live here so add, drag, resize, load-time repair and the
 * edit-mode ghost preview share one definition of "fits".
 */
object DashboardStore {

    /**
     * Dashboards form a cross: three side by side (pages 0-2), and the middle
     * one also has two above and two below (pages 3-6). New pages were added
     * after the first three so older saved layouts keep their page numbers.
     */
    const val PAGE_COUNT = 7

    /** The pages swiped left/right, in order. */
    val ROW = listOf(0, 1, 2)

    /** The middle page of [ROW], the only one with pages above and below. */
    const val CENTER = 1

    /** The pages swiped up/down from [CENTER], top to bottom; [CENTER] sits at [COLUMN_HOME]. */
    val COLUMN = listOf(3, 4, CENTER, 5, 6)
    const val COLUMN_HOME = 2

    /** The cross made of the [shown] pages alone; Home is always there. */
    internal fun shownCross(shown: Collection<Int>): ShownCross = ShownCross(
        row = ROW.filter { it == CENTER || it in shown },
        column = COLUMN.filter { it == CENTER || it in shown }
    )

    /** The page's place in the cross, for labels: Home, left of it, two above it... */
    @StringRes
    fun nameRes(page: Int): Int = when (page) {
        CENTER -> R.string.dash_page_home
        0 -> R.string.dash_page_left
        2 -> R.string.dash_page_right
        4 -> R.string.dash_page_above
        3 -> R.string.dash_page_above2
        5 -> R.string.dash_page_below
        else -> R.string.dash_page_below2
    }

    private const val PREFS = "dashboard_layout_prefs"
    private const val KEY_PAGES = "pages"
    /** Previous good layout, kept so a corrupt write never costs the user everything. */
    private const val KEY_PAGES_BACKUP = "pages_backup"
    private const val KEY_VERSION = "schema"
    private const val TAG = "DashboardStore"

    /**
     * Layout schema version written with every save. Bump it when the JSON
     * shape changes and add the migration to [load]; readers must keep
     * accepting every older version, so a downgrade-then-upgrade never wipes
     * a layout.
     *
     *  1: `{"schema":1,"pages":[[tile...], ...]}`. Before v1 the value was the bare
     *     pages array, which is still accepted. A built-in tile may carry an
     *     optional `"d"` (its WidgetDesign name); absent means the standard
     *     design, so older builds simply ignore it.
     *  2: a tile may carry its frame ([TileFrame]): `"ov"` true for one over
     *     the other tiles, `"op"` its opacity, `"bw"` its border's width and
     *     `"bc"` the border's colour name; each absent at its default. An
     *     older build ignores them, and lays the tiles over the others back
     *     into the grid on load (its [repairOverlaps] knows no overlap).
     *  3: the cells are the finer grid's ([GRID_SCALE] to a coarse cell each
     *     way, 36 x 21). The same keys; a layout of v1 or v2 (or the bare
     *     array) has its `gx`, `gy`, `gw`, `gh` multiplied by [GRID_SCALE]
     *     on load. An older build reads v3 as it is and clamps the cells to
     *     its 12 x 7 grid, which spoils the arrangement until this build runs
     *     again, but loses no tile.
     */
    internal const val SCHEMA_VERSION = 3

    /**
     * Tiles this build does not understand (a type or builtin kind added by a
     * newer version, seen after a downgrade) are carried through untouched, per
     * page, and written back on the next save instead of being silently
     * dropped. Held here because the in-memory model has no slot for them.
     * Kept per screen direction: loading the other arrangement must not swap
     * its unknown tiles into this one's next save.
     */
    private val retainedByVariant = HashMap<String, HashMap<Int, MutableList<JSONObject>>>()
    private fun retained(variant: String) = retainedByVariant.getOrPut(variant) { HashMap() }

    /**
     * The last layout text known to parse, per variant: what [load] read or
     * [save] wrote. [save] keeps it as the backup without parsing it again,
     * which it used to do on the main thread on every drop, undo and zoom.
     */
    private val lastGoodDoc = HashMap<String, String>()

    /**
     * Default layout when nothing is saved yet: the Daily template on the
     * three dashboards of the row (Home, left and right of it), laid out for
     * the head unit's 1280x720 screen ([half]: beside a Maps dock). The four
     * dashboards above and below start empty and out of the swipes; the
     * templates dialog fills them on request. Car tiles only once an adapter
     * can be dialled (a fresh install has none, and tiles with nothing to
     * show would fill the pages; choosing one later offers to add them, see
     * TemplatePlacer.addCarTiles); the CANbox ones only under root
     * ([PrivilegedShell]), where they can show something.
     */
    private fun defaultPages(half: Boolean = false): List<List<DashboardItem>> = TemplatePlacer.pages(
        DashTemplate.DAILY,
        TemplateScreen.of(
            // Upright (720x1280), the Maps dock takes the top or bottom half instead.
            pageWidthDp = if (ScreenShape.vertical) 720f else if (half) 640f else 1280f,
            pageHeightDp = if (ScreenShape.vertical) (if (half) 560f else 1120f) else 576f,
            obdPaired = ObdBluetoothManager.adapter.value.canDial,
            carKeys = UnitPlatform.suppliedKeys(),
            driverOnRight = CarProfileStore.current.driverOnRight,
            mapsDocked = half,
            dockApps = emptyList(),
            canbox = PrivilegedShell.access.value.root,
            tyres = UnitPlatform.delivers(Cap.TYRES)
        ),
        only = ROW.toSet()
    )

    /**
     * One arrangement per screen direction: the variant is "" on a wide
     * screen and "_v" upright ([ScreenShape.layoutPrefix]), nothing else.
     * Until 2026-10 the Maps dock ([LEGACY_HALF]), the Canvas theme
     * ([LEGACY_CANVAS]) and Canvas over an app ([LEGACY_TABS]) each kept a
     * copy of the pages, seeded once and never receiving later edits; a tap
     * on the bar's layout button forked the arrangement. [mergeLegacyVariants]
     * folds those copies into the one arrangement on the first start after
     * the update.
     */
    const val LEGACY_HALF = "_half"
    const val LEGACY_CANVAS = "_canvas"
    const val LEGACY_TABS = "_tabs"

    /** The two arrangements kept: wide and upright. */
    private val DIRECTIONS = listOf("", "_v")

    private const val KEY_MERGED = "variants_merged"

    /**
     * The legacy suffix in use before the update, from the layout and theme
     * prefs: Canvas over an app, else the Canvas theme and the Maps dock.
     * "" when the user was on the base arrangement.
     */
    fun legacySuffix(canvas: Boolean, overApp: Boolean, docked: Boolean): String = when {
        canvas && overApp -> LEGACY_TABS
        else -> (if (canvas) LEGACY_CANVAS else "") + (if (docked) LEGACY_HALF else "")
    }

    /**
     * Which of two saved arrangements becomes the one kept: the one the user
     * was [lastUsed] when it differs from the [base] (their edits live there),
     * else the base. Null when neither was saved.
     */
    internal fun <T> chooseKept(base: T?, lastUsed: T?): T? = when {
        lastUsed != null && lastUsed != base -> lastUsed
        else -> base
    }

    /**
     * Folds the legacy per-layout copies into the one arrangement per
     * direction, once ([KEY_MERGED]): for each direction, the copy the user
     * last used ([suffix], see [legacySuffix]) replaces the base when it
     * differs from it, so nobody loses the dashboards they were looking at.
     * The copies are then removed. Returns the directions whose arrangement
     * came from the copy, so the bar's dashboards can follow
     * (CanvasTabs.mergeLegacy); null when it had run already.
     */
    fun mergeLegacyVariants(context: Context, suffix: String): Set<String>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MERGED, false)) return null
        val edit = prefs.edit()
        val fromCopy = HashSet<String>()
        for (direction in DIRECTIONS) {
            val base = prefs.getString(pagesKey(direction), null)
            val used = if (suffix.isEmpty()) null else prefs.getString(pagesKey(direction + suffix), null)
            val kept = chooseKept(base, used)
            if (kept != null && kept != base) {
                edit.putString(pagesKey(direction), kept)
                // The base arrangement stays as the fallback, where there was one.
                (base ?: prefs.getString(backupKey(direction + suffix), null))?.let { edit.putString(backupKey(direction), it) }
                fromCopy += direction
                Log.i(TAG, "Kept the $suffix arrangement as the one for '$direction'")
            }
        }
        // The copies go: they are never read again, and an app-widget only they held would stay in use for good.
        for (key in prefs.all.keys) {
            if ((key.startsWith(KEY_PAGES) || key.startsWith(KEY_PAGES_BACKUP)) && LEGACY_SUFFIXES.any { key.endsWith(it) }) edit.remove(key)
        }
        edit.putBoolean(KEY_MERGED, true).apply()
        return fromCopy
    }

    private val LEGACY_SUFFIXES = listOf(LEGACY_HALF, LEGACY_CANVAS, LEGACY_TABS, LEGACY_CANVAS + LEGACY_HALF)

    /**
     * [pages] laid out again for the grid of the current screen direction
     * (the other one's tiles were placed on a 12x7 grid, this one is 7x12,
     * or the reverse): each tile at its own span where that fits, else the
     * largest that does down to its kind's minimum, nearest its old place;
     * one with no room left on its page goes to the nearest page with room,
     * so no tile is dropped. Pages keep their order.
     */
    fun reflow(pages: List<List<DashboardItem>>): List<List<DashboardItem>> {
        val out = MutableList(PAGE_COUNT) { mutableListOf<DashboardItem>() }
        val homeless = mutableListOf<Pair<Int, DashboardItem>>()
        pages.take(PAGE_COUNT).forEachIndexed { p, page ->
            // Reading order, so the tiles keep roughly their places relative to each other.
            for (item in page.sortedWith(compareBy({ it.y }, { it.x }))) {
                val placed = fitNear(out[p], item)
                if (placed != null) out[p] += placed else homeless += p to item
            }
        }
        for ((from, item) in homeless) {
            val target = CROSS_NEAR.sortedBy { crossDistance(from, it) }.firstOrNull { fitNear(out[it], item) != null } ?: continue
            out[target] += fitNear(out[target], item)!!
        }
        return out
    }

    /** Home, then the rest of the cross; the order [reflow] tries pages in, nearest first. */
    private val CROSS_NEAR = listOf(CENTER) + (ROW - CENTER) + (COLUMN - CENTER)

    /** Swipes between two pages of the cross: along the row or the column, else through Home. */
    private fun crossDistance(a: Int, b: Int): Int {
        fun toHome(page: Int) = if (page in ROW) abs(ROW.indexOf(page) - ROW.indexOf(CENTER)) else abs(COLUMN.indexOf(page) - COLUMN_HOME)
        return when {
            a in ROW && b in ROW -> abs(ROW.indexOf(a) - ROW.indexOf(b))
            a in COLUMN && b in COLUMN -> abs(COLUMN.indexOf(a) - COLUMN.indexOf(b))
            else -> toHome(a) + toHome(b)
        }
    }

    /**
     * [item] placed among [placed] on this grid: its own span, or the largest
     * that fits down to its kind's minimum (then one cell), at the free spot
     * nearest its old cell. Null when the page has no room at all.
     */
    private fun fitNear(placed: List<DashboardItem>, item: DashboardItem): DashboardItem? {
        val w = item.w.coerceIn(1, GRID_COLS)
        val h = item.h.coerceIn(1, GRID_ROWS)
        // A tile over the others needs no free cells: its own place, inside the new grid.
        if (!item.inGrid()) return item.withCell(item.x, item.y, w, h)
        val minW = (if (item is DashboardItem.BuiltinWidget) item.kind.minSize().first else 1).coerceAtMost(w)
        val minH = (if (item is DashboardItem.BuiltinWidget) item.kind.minSize().second else 1).coerceAtMost(h)
        val fit = largestFit(placed, w, h, minW, minH) ?: largestFit(placed, w, h, 1, 1) ?: return null
        val cell = nearestFreeCell(placed, fit.w, fit.h, item.x.coerceIn(0, GRID_COLS - 1), item.y.coerceIn(0, GRID_ROWS - 1)) ?: (fit.x to fit.y)
        return item.withCell(cell.first, cell.second, fit.w, fit.h)
    }

    /** Preference keys for a layout variant; pure so the naming is testable. */
    internal fun pagesKey(variant: String) = KEY_PAGES + variant
    internal fun backupKey(variant: String) = KEY_PAGES_BACKUP + variant

    fun exists(context: Context, variant: String = ""): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(pagesKey(variant))

    /** The app-widget ids on [pages]. */
    fun widgetIds(pages: List<List<DashboardItem>>): Set<Int> =
        pages.flatMapTo(HashSet()) { page -> page.filterIsInstance<DashboardItem.SystemWidget>().map { it.appWidgetId } }

    /**
     * The app-widget ids on every saved arrangement (wide and upright) and
     * their backups: the upright one is laid out from the wide one, so both
     * can show the same widget, which is then still in use while one keeps it. Null when one can't be read:
     * what is in use isn't known then.
     */
    fun savedWidgetIds(context: Context): Set<Int>? {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
        val ids = HashSet<Int>()
        for ((key, value) in saved) {
            if (!key.startsWith(KEY_PAGES) || value !is String) continue
            ids += widgetIdsIn(value) ?: return null
        }
        return ids
    }

    /** The app-widget ids in a saved layout's text, either schema; null if it is not a layout. Changes nothing, unlike [parsePages]. */
    internal fun widgetIdsIn(raw: String): Set<Int>? = runCatching<Set<Int>> {
        val trimmed = raw.trim()
        val pages = if (trimmed.startsWith("{")) JSONObject(trimmed).optJSONArray(KEY_PAGES) ?: JSONArray() else JSONArray(trimmed)
        val ids = HashSet<Int>()
        for (p in 0 until pages.length()) {
            val page = pages.optJSONArray(p) ?: continue
            for (i in 0 until page.length()) {
                val tile = page.optJSONObject(i) ?: continue
                if (tile.optString("t") == "widget") tile.optInt("id", -1).takeIf { it != -1 }?.let(ids::add)
            }
        }
        ids
    }.getOrNull()

    fun load(context: Context, variant: String = ""): List<List<DashboardItem>> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(pagesKey(variant), null) ?: run {
            retained(variant).clear()
            // The first time this way up: the other direction's dashboards, laid out again for this grid.
            val other = DIRECTIONS.firstOrNull { it != variant && prefs.contains(pagesKey(it)) }
            val pages = other?.let { o -> prefs.getString(pagesKey(o), null)?.let { parsePages(it, o) } }?.let(::reflow)
                ?: defaultPages(DashLayoutStore.load(context) != DashLayout.GRID)
            return pages.also(::notePlaced)
        }

        // A corrupt primary value falls back to the last good layout rather
        // than to the defaults; only when both are unreadable does the user
        // lose their arrangement, and then it is logged.
        val parsed = parsePages(raw, variant)?.also { lastGoodDoc[variant] = raw }
            ?: prefs.getString(backupKey(variant), null)?.let { backup ->
                Log.w(TAG, "Saved layout unreadable, restoring the previous one")
                parsePages(backup, variant)?.also { lastGoodDoc[variant] = backup }
            }
            ?: run {
                Log.e(TAG, "Saved layout and its backup are both unreadable; using defaults")
                retained(variant).clear()
                lastGoodDoc.remove(variant)
                return defaultPages(DashLayoutStore.load(context) != DashLayout.GRID).also(::notePlaced)
            }

        // Always return exactly PAGE_COUNT pages. Tiles that predate grid
        // coordinates (x = -1) are flowed in; any overlap left by an older
        // grid size, a clamp, or a hand-edited file is repaired so two tiles
        // never share a cell.
        return List(PAGE_COUNT) { p ->
            val page = parsed.getOrElse(p) { emptyList() }
            repairOverlaps(if (page.any { it.x < 0 }) autoPlace(page) else page)
        }.also(::notePlaced)
    }

    /** The built-in kinds on any dashboard of the layout last loaded or saved: the add sheet marks them as already there. */
    private val _placedKinds = MutableStateFlow<Set<BuiltinKind>>(emptySet())
    val placedKinds: StateFlow<Set<BuiltinKind>> = _placedKinds

    private fun notePlaced(pages: List<List<DashboardItem>>) {
        _placedKinds.value = pages.flatten().filterIsInstance<DashboardItem.BuiltinWidget>().map { it.kind }.toSet()
    }

    /**
     * Parses either schema (bare pages array, or the versioned object) into
     * pages of tiles; null if the text is not a layout at all. Unknown tiles
     * are stashed in [retained] ([variant]'s) for the next [save].
     */
    internal fun parsePages(raw: String, variant: String = ""): List<List<DashboardItem>>? =
        parseItems(raw, retained(variant))

    /** [parsePages] for a text that is not this unit's layout (a shared code): nothing is stashed for a later save. */
    internal fun parsePagesPlain(raw: String): List<List<DashboardItem>>? = parseItems(raw, retained = null)

    /**
     * The tiles saved for [variant], read without changing anything: not the
     * tiles kept for its next save, not the layout's backup, not the kinds the
     * add sheet marks as placed. For looking at the other screen direction's
     * arrangement, which [load] would have taken for this one's. Null when
     * none is saved or it cannot be read.
     */
    fun peek(context: Context, variant: String): List<DashboardItem>? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(pagesKey(variant), null) ?: return null
        return peekItems(raw)
    }

    /** The tiles in a saved layout's text, read without side effects ([peek]); null if it is not a layout. */
    internal fun peekItems(raw: String): List<DashboardItem>? = parseItems(raw, retained = null)?.flatten()

    /**
     * The pages of tiles in [raw]; the tiles this build does not understand go
     * to [retained] (emptied first) when one is given, and are dropped otherwise.
     */
    private fun parseItems(raw: String, retained: HashMap<Int, MutableList<JSONObject>>?): List<List<DashboardItem>>? = runCatching {
        val trimmed = raw.trim()
        // Before v3 the cells were the coarse grid's.
        var v = 1
        val pages = if (trimmed.startsWith("{")) {
            val obj = JSONObject(trimmed)
            v = obj.optInt(KEY_VERSION, 1)
            if (v > SCHEMA_VERSION) Log.w(TAG, "Layout schema v$v is newer than this build (v$SCHEMA_VERSION)")
            obj.optJSONArray(KEY_PAGES) ?: JSONArray()
        } else {
            JSONArray(trimmed)
        }
        val scale = if (v < 3) GRID_SCALE else 1
        retained?.clear()
        (0 until pages.length()).map { p ->
            val page = pages.optJSONArray(p) ?: JSONArray()
            (0 until page.length()).mapNotNull { i ->
                val o = page.optJSONObject(i) ?: return@mapNotNull null
                if (o.isDroppedBuiltin()) return@mapNotNull null
                o.toItem(scale) ?: run {
                    retained?.getOrPut(p) { mutableListOf() }?.add(o)
                    null
                }
            }
        }
    }.onFailure { Log.w(TAG, "Layout parse failed", it) }.getOrNull()

    /** The versioned JSON document [save] writes, including any [retained] tiles. */
    internal fun serializePages(pages: List<List<DashboardItem>>, variant: String = ""): String = serialize(pages, retained(variant))

    /** [serializePages] with nothing retained: a shared code carries what the driver sees, not strangers from a code read earlier. */
    internal fun serializePagesPlain(pages: List<List<DashboardItem>>): String = serialize(pages, emptyMap())

    private fun serialize(pages: List<List<DashboardItem>>, retained: Map<Int, List<JSONObject>>): String {
        val json = JSONArray()
        pages.take(PAGE_COUNT).forEachIndexed { p, page ->
            val arr = JSONArray()
            page.forEach { arr.put(it.toJson()) }
            retained[p]?.forEach { arr.put(it) }
            json.put(arr)
        }
        return JSONObject().put(KEY_VERSION, SCHEMA_VERSION).put(KEY_PAGES, json).toString()
    }

    fun save(context: Context, pages: List<List<DashboardItem>>, variant: String = "") {
        notePlaced(pages)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val doc = serializePages(pages, variant)
        // Keep what was there as the fallback for the next load, unless it is
        // the same text (nothing to gain) or unreadable (nothing to keep). What
        // this process read or wrote is known to parse; only a text nobody has
        // looked at yet (no load before the first save) is checked.
        val previous = lastGoodDoc[variant]
            ?: prefs.getString(pagesKey(variant), null)?.takeIf { parsePagesQuietly(it, variant) }
        lastGoodDoc[variant] = doc
        prefs.edit().apply {
            if (previous != null && previous != doc) putString(backupKey(variant), previous)
            putString(pagesKey(variant), doc)
        }.apply()
    }

    /** True if [raw] parses as a layout, without touching [retained]. */
    private fun parsePagesQuietly(raw: String, variant: String): Boolean {
        val retained = retained(variant)
        val keep = HashMap(retained)
        val ok = parsePages(raw, variant) != null
        retained.clear(); retained.putAll(keep)
        return ok
    }

    /** First cell where a [w] x [h] tile fits without overlapping [items]. */
    fun firstFreeCell(items: List<DashboardItem>, w: Int, h: Int): Pair<Int, Int>? {
        val occ = occupancy(items)
        return firstFree(occ, w.coerceIn(1, GRID_COLS), h.coerceIn(1, GRID_ROWS))
    }

    /**
     * True when a proposed tile rectangle is fully in bounds and does not touch
     * another tile of the grid. [ignoredIndex] is the tile currently being
     * moved or resized; one over the others ([TileFrame.overlap]) only has to
     * stay in bounds, and the tiles over the others are never in the way.
     */
    fun canPlace(
        items: List<DashboardItem>,
        ignoredIndex: Int?,
        x: Int,
        y: Int,
        w: Int,
        h: Int
    ): Boolean {
        if (!inBounds(x, y, w, h)) return false
        if (ignoredIndex != null && items.getOrNull(ignoredIndex)?.inGrid() == false) return true
        return items.withIndex().none { (index, item) ->
            index != ignoredIndex && item.inGrid() && rectanglesOverlap(x, y, w, h, item.x, item.y, item.w, item.h)
        }
    }

    /**
     * Moves tile [index] so its top-left is at ([x],[y]), resolving collisions
     * instead of refusing them:
     *
     *  1. free target → plain move;
     *  2. exactly one tile in the way that fits in the vacated rectangle → swap;
     *  3. otherwise every tile in the way is nudged to its nearest free cell.
     *
     * Returns the new page, or null when the tiles in the way have nowhere to go
     * (the caller then keeps the layout and tells the user).
     *
     * A tile over the others goes anywhere in bounds and moves nothing; the
     * tiles over the others are never in a grid tile's way either.
     */
    fun moveResolving(items: List<DashboardItem>, index: Int, x: Int, y: Int): List<DashboardItem>? {
        val mover = items.getOrNull(index) ?: return null
        if (!inBounds(x, y, mover.w, mover.h)) return null
        val moved = mover.withCell(x, y, mover.w, mover.h)
        if (canPlace(items, index, x, y, mover.w, mover.h)) {
            return items.mapIndexed { i, it -> if (i == index) moved else it }
        }

        val blocking = items.withIndex().filter { (i, it) -> i != index && it.inGrid() && it.overlaps(moved) }

        // Swap: one tile in the way, and it fits where the mover came from
        // without touching anything else.
        if (blocking.size == 1) {
            val (bi, b) = blocking.single()
            val swapped = b.withCell(mover.x, mover.y, b.w, b.h)
            val others = items.filterIndexed { i, _ -> i != index && i != bi }
            val clear = swapped.x == mover.x && swapped.y == mover.y &&
                !swapped.overlaps(moved) && others.none { it.inGrid() && it.overlaps(swapped) }
            if (clear) {
                return items.mapIndexed { i, it ->
                    when (i) {
                        index -> moved
                        bi -> swapped
                        else -> it
                    }
                }
            }
        }

        // Nudge: place the mover, then relocate each blocked tile (largest
        // first, so small ones fill the gaps) to the nearest free cell.
        val result = items.toMutableList()
        result[index] = moved
        val fixed = items.indices.filter { i -> i != index && blocking.none { it.index == i } && items[i].inGrid() }
            .map { result[it] }.toMutableList()
        fixed += moved
        for ((bi, b) in blocking.sortedByDescending { it.value.w * it.value.h }) {
            val cell = nearestFreeCell(fixed, b.w, b.h, b.x, b.y) ?: return null
            val placed = b.withCell(cell.first, cell.second, b.w, b.h)
            result[bi] = placed
            fixed += placed
        }
        return result
    }

    /**
     * Free cell for a [w] x [h] tile closest to ([nearX],[nearY]) given the
     * already-placed [items]; null when the page is full.
     */
    fun nearestFreeCell(items: List<DashboardItem>, w: Int, h: Int, nearX: Int, nearY: Int): Pair<Int, Int>? {
        val occ = occupancy(items)
        var best: Pair<Int, Int>? = null
        var bestDist = Int.MAX_VALUE
        for (y in 0..GRID_ROWS - h) for (x in 0..GRID_COLS - w) {
            if (!fits(occ, x, y, w, h)) continue
            val d = abs(x - nearX) + abs(y - nearY)
            if (d < bestDist) { bestDist = d; best = x to y }
        }
        return best
    }

    /**
     * Keeps the first of any overlapping tiles where it is and relocates the
     * later ones to the nearest free cell. A tile with no room left is dropped
     * rather than drawn on top of another one. The tiles over the others
     * ([TileFrame.overlap]) are meant to overlap: they stay where they are,
     * only kept inside the grid, and take no cell from the rest.
     */
    fun repairOverlaps(items: List<DashboardItem>): List<DashboardItem> {
        val placed = mutableListOf<DashboardItem>()
        for (item in items) {
            val fixed = item.withCell(item.x, item.y, item.w, item.h)
            if (!fixed.inGrid() || placed.none { it.inGrid() && it.overlaps(fixed) }) {
                placed += fixed
                continue
            }
            val full = nearestFreeCell(placed, fixed.w, fixed.h, fixed.x, fixed.y)
            if (full != null) {
                placed += fixed.withCell(full.first, full.second, fixed.w, fixed.h)
                continue
            }
            // No room at its size: shrink to its kind's minimum, then to one cell, before giving up.
            val shrunk = listOf(fixed.minW() to fixed.minH(), 1 to 1).firstNotNullOfOrNull { (w, h) ->
                nearestFreeCell(placed, w, h, fixed.x, fixed.y)?.let { cell -> fixed.withCell(cell.first, cell.second, w, h) }
            } ?: continue
            placed += shrunk
        }
        return placed
    }

    /**
     * [items] with the tile at [index] drawn last of all ([toFront]), or first,
     * so among the tiles over the others it is on top, or under the rest of
     * them. Only such a tile moves in the order: the grid's tiles never cover
     * one another, so their order shows nothing. Returns the new page and the
     * tile's new index.
     */
    fun reorder(items: List<DashboardItem>, index: Int, toFront: Boolean): Pair<List<DashboardItem>, Int> {
        val item = items.getOrNull(index) ?: return items to index
        if (item.inGrid()) return items to index
        val rest = items.filterIndexed { i, _ -> i != index }
        return if (toFront) (rest + item) to rest.size else (listOf(item) + rest) to 0
    }

    /**
     * The tile at [index], over the others until now, back in the grid: where
     * it is when its cells are free, else at the nearest free cell at its
     * size, else shrunk to its kind's minimum. Null when the page has no room
     * for it; it then stays over the others.
     */
    fun intoGrid(items: List<DashboardItem>, index: Int): List<DashboardItem>? {
        val item = items.getOrNull(index) ?: return null
        val grid = items.filterIndexed { i, it -> i != index && it.inGrid() }
        val landed = item.withFrame(item.frame.copy(overlap = false))
        val placed = when {
            grid.none { it.overlaps(landed) } -> landed
            else -> listOf(landed.w to landed.h, landed.minW() to landed.minH()).firstNotNullOfOrNull { (w, h) ->
                nearestFreeCell(grid, w, h, landed.x, landed.y)?.let { cell -> landed.withCell(cell.first, cell.second, w, h) }
            } ?: return null
        }
        return items.mapIndexed { i, it -> if (i == index) placed else it }
    }

    private fun inBounds(x: Int, y: Int, w: Int, h: Int): Boolean =
        x >= 0 && y >= 0 && w >= 1 && h >= 1 && x + w <= GRID_COLS && y + h <= GRID_ROWS

    /** The cells the grid's tiles hold; a tile over the others holds none. */
    private fun occupancy(items: List<DashboardItem>): Array<BooleanArray> {
        val occ = Array(GRID_ROWS) { BooleanArray(GRID_COLS) }
        items.forEach { if (it.inGrid()) mark(occ, it.x, it.y, it.w, it.h, true) }
        return occ
    }

    private fun firstFree(occ: Array<BooleanArray>, w: Int, h: Int): Pair<Int, Int>? {
        for (y in 0..GRID_ROWS - h) {
            for (x in 0..GRID_COLS - w) {
                if (fits(occ, x, y, w, h)) return x to y
            }
        }
        return null
    }

    private fun fits(occ: Array<BooleanArray>, x: Int, y: Int, w: Int, h: Int): Boolean {
        for (yy in y until y + h) for (xx in x until x + w) {
            if (yy !in 0 until GRID_ROWS || xx !in 0 until GRID_COLS || occ[yy][xx]) return false
        }
        return true
    }

    private fun mark(occ: Array<BooleanArray>, x: Int, y: Int, w: Int, h: Int, v: Boolean) {
        for (yy in y until y + h) for (xx in x until x + w) {
            if (yy in 0 until GRID_ROWS && xx in 0 until GRID_COLS) occ[yy][xx] = v
        }
    }

    internal fun rectanglesOverlap(
        ax: Int, ay: Int, aw: Int, ah: Int,
        bx: Int, by: Int, bw: Int, bh: Int
    ): Boolean = ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by

    /** Flow tiles onto the grid in order, first free cell for each (migration). */
    private fun autoPlace(items: List<DashboardItem>): List<DashboardItem> {
        val occ = Array(GRID_ROWS) { BooleanArray(GRID_COLS) }
        return items.map { item ->
            val w = item.w.coerceIn(item.minW(), GRID_COLS)
            val h = item.h.coerceIn(item.minH(), GRID_ROWS)
            val pos = firstFree(occ, w, h) ?: (0 to 0)
            mark(occ, pos.first, pos.second, w, h, true)
            item.withCell(pos.first, pos.second, w, h)
        }
    }

    private fun DashboardItem.toJson(): JSONObject {
        val o = when (this) {
            is DashboardItem.AppShortcut -> JSONObject().put("t", "app").put("pkg", packageName)
            is DashboardItem.SplitPair ->
                JSONObject().put("t", "split").put("a", primaryPackage).put("b", secondaryPackage)
            is DashboardItem.LaunchBar ->
                JSONObject().put("t", "bar").put("pkgs", JSONArray(packages))
            is DashboardItem.BuiltinWidget -> JSONObject().put("t", "builtin").put("k", kind.name)
                .apply { if (design != WidgetDesign.STANDARD) put("d", design.name) }
            is DashboardItem.SystemWidget -> JSONObject().put("t", "widget").put("id", appWidgetId)
            is DashboardItem.AppWindow -> JSONObject().put("t", "appwin").put("pkg", packageName)
                .apply { if (inside) put("in", true) }
        }
        if (zoom != 1f) o.put("z", zoom.toDouble())
        if (frame.overlap) o.put("ov", true)
        if (frame.opacity != 1f) o.put("op", frame.opacity.toDouble())
        if (frame.border > 0) {
            o.put("bw", frame.border)
            if (frame.borderColor != TileBorderColor.ACCENT) o.put("bc", frame.borderColor.name)
        }
        return o.put("gx", x).put("gy", y).put("gw", w).put("gh", h)
    }

    /** Copy with x = -1, the sentinel load() uses to auto-place migrated tiles. */
    private fun DashboardItem.markUnplaced(): DashboardItem = when (this) {
        is DashboardItem.AppShortcut -> copy(x = -1)
        is DashboardItem.SplitPair -> copy(x = -1)
        is DashboardItem.LaunchBar -> copy(x = -1)
        is DashboardItem.BuiltinWidget -> copy(x = -1)
        is DashboardItem.SystemWidget -> copy(x = -1)
        is DashboardItem.AppWindow -> copy(x = -1)
    }

    /**
     * Built-in kinds this launcher used to have and dropped for good. Unlike a
     * kind from a newer build (kept for it, see [retained]), a tile of one of
     * these has nowhere to go and is left out of the layout on the next save.
     */
    private val DROPPED_KINDS = setOf("CAR3D")

    private fun JSONObject.isDroppedBuiltin(): Boolean =
        optString("t") == "builtin" && optString("k") in DROPPED_KINDS

    /** The tile in this JSON, its saved cells [scale] times the grid's (a layout from before the finer grid). */
    private fun JSONObject.toItem(scale: Int): DashboardItem? {
        val gx = optInt("gx", -1).let { if (it >= 0) it * scale else it }
        val gy = optInt("gy", -1).let { if (it >= 0) it * scale else it }
        val gw = optInt("gw", -1).let { if (it > 0) it * scale else it }
        val gh = optInt("gh", -1).let { if (it > 0) it * scale else it }
        val zoom = optDouble("z", 1.0).toFloat().takeIf { it.isFinite() } ?: 1f
        val frame = TileFrame(
            overlap = optBoolean("ov"),
            opacity = optDouble("op", 1.0).toFloat(),
            border = optInt("bw", 0),
            borderColor = TileBorderColor.fromName(optString("bc"))
        ).clamped()
        fun place(item: DashboardItem): DashboardItem =
            (if (gx >= 0 && gy >= 0 && gw > 0 && gh > 0) item.withCell(gx, gy, gw, gh)
            else item.markUnplaced())
                .let { if (zoom != 1f) it.withZoom(zoom) else it }
                .let { if (frame != TileFrame.NONE) it.withFrame(frame) else it }

        return when (optString("t")) {
            "app" -> optString("pkg").takeIf { it.isNotBlank() }
                ?.let { place(DashboardItem.AppShortcut(it)) }
            "split" -> {
                val a = optString("a"); val b = optString("b")
                if (a.isNotBlank() && b.isNotBlank()) place(DashboardItem.SplitPair(a, b)) else null
            }
            "bar" -> {
                val arr = optJSONArray("pkgs") ?: JSONArray()
                val pkgs = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                place(DashboardItem.LaunchBar(pkgs))
            }
            // Default span comes from the kind (a clock is 3x2, a map 5x3), so a
            // legacy tile without coordinates is re-flowed at its proper size.
            "builtin" -> runCatching { BuiltinKind.valueOf(optString("k")) }.getOrNull()
                ?.let { place(DashboardItem.BuiltinWidget(it, w = it.defaultW, h = it.defaultH, design = WidgetDesign.fromName(optString("d")))) }
            "widget" -> optInt("id", -1).takeIf { it != -1 }
                ?.let { place(DashboardItem.SystemWidget(it)) }
            "appwin" -> optString("pkg").takeIf { it.isNotBlank() }
                ?.let { place(DashboardItem.AppWindow(it, inside = optBoolean("in"))) }
            else -> null
        }
    }
}
