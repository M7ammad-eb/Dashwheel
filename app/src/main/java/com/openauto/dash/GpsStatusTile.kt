package com.openauto.dash

import android.Manifest
import android.annotation.SuppressLint
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay


/** The satellites seen and used in the fix, while a tile shows them. */
@SuppressLint("MissingPermission")
@Composable
private fun rememberSatellites(granted: Boolean): Pair<Int, Int> {
    val context = LocalContext.current
    var seen by remember { mutableIntStateOf(0) }
    var used by remember { mutableIntStateOf(0) }
    DisposableEffect(granted) {
        val lm = context.getSystemService(LocationManager::class.java)
        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                seen = status.satelliteCount
                used = (0 until status.satelliteCount).count { status.usedInFix(it) }
            }
        }
        if (granted) runCatching { lm?.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper())) }
        onDispose { runCatching { lm?.unregisterGnssStatusCallback(callback) } }
    }
    // The phone's GPS standing in ([PhoneGps]): its satellites, not this unit's idle receiver.
    val phone by PhoneGps.inUse.collectAsState()
    phone?.let { return it.seen to it.used }
    return seen to used
}

/** GPS: the fix, the satellites in it and seen, its accuracy, altitude, speed and age. */
@Composable
internal fun GpsStatusCard(modifier: Modifier = Modifier) {
    val perm = rememberPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    val (seen, used) = rememberSatellites(perm.granted)
    val location by LocationFeed.location.collectAsState()
    val units by Units.current.collectAsState()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    // Not on a page off screen: the fix's age is read again when the page shows.
    val active = LocalPageActive.current
    LaunchedEffect(active) { if (!active) return@LaunchedEffect; while (true) { now = SystemClock.elapsedRealtimeNanos(); delay(1_000) } }
    val loc = location
    val ageS = loc?.let { ((now - it.elapsedRealtimeNanos) / 1_000_000_000L).toInt() }
    val fixed = loc != null && ageS != null && ageS < 10
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg), verticalArrangement = Arrangement.SpaceBetween) {
            TileHeader(stringResource(R.string.widgets_gps)) {
                val fromPhone by PhoneGps.inUse.collectAsState()
                Text(
                    stringResource(if (!fixed) R.string.widgets_gps_no_fix else if (fromPhone != null) R.string.widgets_gps_phone else R.string.widgets_gps_fix),
                    color = if (fixed) DashColors.Good else DashColors.Warning, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge
                )
            }
            Text(
                stringResource(R.string.widgets_gps_sats, used, seen),
                color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat(stringResource(R.string.widgets_gps_accuracy), loc?.takeIf { it.hasAccuracy() }?.let { "± ${units.shortDistance(it.accuracy.toDouble())}" } ?: "--")
                Stat(stringResource(R.string.widgets_gps_altitude), loc?.takeIf { it.hasAltitude() }?.let { units.heightText(it.altitude) } ?: "--")
                Stat(stringResource(R.string.widgets_gps_speed), loc?.takeIf { it.hasSpeed() }?.let { "${units.speed(it.speed * 3.6f)} ${units.speedUnit}" } ?: "--")
                Stat(stringResource(R.string.widgets_gps_age), ageS?.let { stringResource(R.string.widgets_gps_seconds, it) } ?: "--")
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
        Text(value, color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun gpsStatusFace(): WidgetFace {
    val perm = rememberPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    val (seen, used) = rememberSatellites(perm.granted)
    val location by LocationFeed.location.collectAsState()
    val units by Units.current.collectAsState()
    val loc = location
    return WidgetFace(
        icon = if (loc != null) Icons.Filled.GpsFixed else Icons.Filled.GpsNotFixed,
        title = BuiltinKind.GPS_STATUS.label,
        value = "$used/$seen",
        caption = loc?.takeIf { it.hasAccuracy() }?.let { "± ${units.shortDistance(it.accuracy.toDouble())}" } ?: stringResource(R.string.widgets_gps_no_fix),
        fraction = (used / 12f).coerceIn(0f, 1f),
        stats = listOf(
            FaceStat(stringResource(R.string.widgets_gps_altitude), loc?.takeIf { it.hasAltitude() }?.let { units.heightText(it.altitude) } ?: "--"),
            FaceStat(stringResource(R.string.widgets_gps_speed), loc?.takeIf { it.hasSpeed() }?.let { "${units.speed(it.speed * 3.6f)}" } ?: "--")
        )
    )
}
