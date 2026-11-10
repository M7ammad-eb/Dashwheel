package com.openauto.dash

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlin.math.ceil

// In its own file: SkinKit.kt's top-level values touch Android at class load, which a JVM unit
// test of the scale (RevScaleTest) cannot do; [SKIN_RPM_MAX] is a constant and inlines.

/** The red line taken when the car's profile has none: a diesel's. */
internal const val DEFAULT_REDLINE_RPM = 4_500

/**
 * A rev scale for the car on the dashboard: where it tops out and where it
 * turns red, from the profile's red line. The top stays at [SKIN_RPM_MAX]
 * until a red line comes within a tenth of it; a petrol engine's 7 500 then
 * gets a scale to 9 000 instead of a needle pinned at the end.
 */
@Immutable
internal data class RevScale(val top: Float, val redFrom: Float) {
    /** [rpm] along the scale, 0 to 1. */
    fun fraction(rpm: Number?): Float = rpm?.let { (it.toFloat() / top).coerceIn(0f, 1f) } ?: 0f
}

internal fun revScale(redlineRpm: Int?): RevScale {
    val redline = redlineRpm?.takeIf { it > 0 } ?: DEFAULT_REDLINE_RPM
    val top = if (redline * 1.1f <= SKIN_RPM_MAX) SKIN_RPM_MAX else ceil(redline * 1.1f / 1000f) * 1000f
    return RevScale(top, (redline / top).coerceIn(0.1f, 1f))
}

/** The car's rev scale as state: it follows the profile when the red line is typed in or fetched. */
@Composable
internal fun rememberRevScale(): RevScale {
    val profile by CarProfileStore.profile.collectAsState()
    return remember(profile.redlineRpm) { revScale(profile.redlineRpm) }
}
