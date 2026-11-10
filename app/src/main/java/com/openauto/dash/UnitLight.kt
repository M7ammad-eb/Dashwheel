package com.openauto.dash

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openauto.dash.vehicle.headlightsFlow
import com.openauto.dash.vehicle.headlightsNow
import com.openauto.dash.vehicle.rememberHeadlights
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The head unit's own screen light: a level for the day and one for the night
 * (the headlights on), put on by itself when the headlights change, and − / +
 * on the tile for the one in use. Written as Android's screen brightness, by
 * the settings permission when Dashwheel has it, else through the privileged shell.
 */
object UnitLight {
    private const val TAG = "UnitLight"
    private const val PREFS = "unit_light"
    private const val KEY_DAY = "day"
    private const val KEY_NIGHT = "night"
    private const val KEY_FOLLOW = "follow"
    const val MAX = 255
    const val MIN = 5

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    private val _day = MutableStateFlow(200)
    val day: StateFlow<Int> = _day
    private val _night = MutableStateFlow(60)
    val night: StateFlow<Int> = _night
    private val _follow = MutableStateFlow(false)
    /** The level follows the headlights; off: the unit's own setting stands. */
    val follow: StateFlow<Boolean> = _follow

    /** Night by the headlights. */
    val isNight: Boolean get() = headlightsNow()

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _day.value = p.getInt(KEY_DAY, 200)
        _night.value = p.getInt(KEY_NIGHT, 60)
        _follow.value = p.getBoolean(KEY_FOLLOW, false)
        scope.launch {
            headlightsFlow().collect { if (_follow.value) apply(app) }
        }
    }

    /** The level in use now. */
    fun current(): Int = if (isNight) _night.value else _day.value

    /** One step of − or + on the level in use; it is also written to the screen at once. */
    fun nudge(context: Context, by: Int) = set(context, current() + by)

    fun set(context: Context, level: Int) {
        val v = level.coerceIn(MIN, MAX)
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (isNight) { _night.value = v; p.putInt(KEY_NIGHT, v) } else { _day.value = v; p.putInt(KEY_DAY, v) }
        p.apply()
        scope.launch { write(context.applicationContext, v, fromTap = true) }
    }

    fun setFollow(context: Context, on: Boolean) {
        _follow.value = on
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_FOLLOW, on).apply()
        if (on) scope.launch { apply(context.applicationContext) }
    }

    private suspend fun apply(context: Context) = write(context, current(), fromTap = false)

    /** The follow collector found no way to write: said once, not at every headlight change. */
    private var gaveUp = false

    /**
     * Writes [level] to the screen. Without the settings permission or a shell,
     * Android's permission page is opened only for a driver's tap ([fromTap]):
     * the headlights collector runs while driving and must not cover the dashboard.
     */
    private suspend fun write(context: Context, level: Int, fromTap: Boolean) {
        val cr = context.contentResolver
        val direct = Settings.System.canWrite(context) && runCatching {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, level)
        }.getOrDefault(false)
        if (direct) return
        try {
            DockShell.shell(context, "settings put system screen_brightness_mode 0; settings put system screen_brightness $level")
        } catch (e: Exception) {
            if (!fromTap) {
                if (!gaveUp) Log.w(TAG, "can't set the screen light: ${e.message}; following the headlights waits for the settings permission")
                gaveUp = true
                return
            }
            Log.w(TAG, "can't set the screen light: ${e.message}")
            // No shell on this unit: Android's own "modify system settings" once, then it is written directly.
            runCatching {
                context.startActivity(
                    android.content.Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}

/** The tile: the level in use (day or night), − / + and a slider, and whether it follows the headlights. */
@Composable
internal fun UnitLightCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    DisposableEffect(Unit) { UnitLight.start(context); onDispose { } }
    val day by UnitLight.day.collectAsState()
    val night by UnitLight.night.collectAsState()
    val follow by UnitLight.follow.collectAsState()
    val isNight = rememberHeadlights()
    val level = if (isNight) night else day
    var dragged by remember(level) { mutableFloatStateOf(level.toFloat()) }
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg)) {
            TileHeader(stringResource(R.string.widgets_light)) {
                IconButton(onClick = { UnitLight.setFollow(context, !follow) }, modifier = Modifier.size(DashSize.Touch)) {
                    Icon(
                        if (follow) Icons.Filled.BrightnessAuto else if (isNight) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                        contentDescription = stringResource(R.string.widgets_light_follow),
                        tint = if (follow) DashColors.Accent else DashColors.Muted
                    )
                }
            }
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { UnitLight.nudge(context, -STEP) }, modifier = Modifier.size(DashSize.Touch)) {
                    Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.widgets_light_down), tint = DashColors.TextPrimary, modifier = Modifier.size(24.dp))
                }
                Slider(
                    value = dragged,
                    onValueChange = { dragged = it },
                    onValueChangeFinished = { UnitLight.set(context, dragged.roundToInt()) },
                    valueRange = UnitLight.MIN.toFloat()..UnitLight.MAX.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = if (DashColors.Light) DashColors.Accent else Color.White, activeTrackColor = DashColors.Accent,
                        inactiveTrackColor = DashColors.CardHi
                    ),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { UnitLight.nudge(context, STEP) }, modifier = Modifier.size(DashSize.Touch)) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.widgets_light_up), tint = DashColors.TextPrimary, modifier = Modifier.size(24.dp))
                }
            }
            Text(
                stringResource(
                    if (follow) (if (isNight) R.string.widgets_light_night else R.string.widgets_light_day) else R.string.widgets_light_manual,
                    level * 100 / UnitLight.MAX
                ),
                color = DashColors.TextSecondary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
internal fun unitLightFace(): WidgetFace {
    val context = LocalContext.current
    DisposableEffect(Unit) { UnitLight.start(context); onDispose { } }
    val day by UnitLight.day.collectAsState()
    val night by UnitLight.night.collectAsState()
    val isNight = rememberHeadlights()
    val level = if (isNight) night else day
    return WidgetFace(
        icon = if (isNight) Icons.Filled.DarkMode else Icons.Filled.LightMode,
        title = BuiltinKind.UNIT_LIGHT.label,
        value = (level * 100 / UnitLight.MAX).toString(), unit = "%",
        fraction = level / UnitLight.MAX.toFloat(),
        actions = listOf(
            FaceAction(Icons.Filled.Remove, stringResource(R.string.widgets_light_down), onClick = { UnitLight.nudge(context, -STEP) }),
            FaceAction(Icons.Filled.Add, stringResource(R.string.widgets_light_up), onClick = { UnitLight.nudge(context, STEP) })
        )
    )
}

private const val STEP = 20
