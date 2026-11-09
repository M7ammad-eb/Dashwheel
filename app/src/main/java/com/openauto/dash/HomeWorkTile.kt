package com.openauto.dash

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.geojson.Point

/** How long the drive to home and to work takes from here, read again every few minutes while the tile shows. */
private const val REFRESH_MS = 5 * 60_000L

/** Minutes of driving from [from] to [to] by the free routing server; null when it can't be had. */
private suspend fun minutesTo(context: Context, fromLat: Double, fromLng: Double, to: Place): Int? = withContext(Dispatchers.IO) {
    runCatching {
        valhallaRoute(Point.fromLngLat(fromLng, fromLat), Point.fromLngLat(to.lng, to.lat), InAppNav.locale(context).language)
            .routes.firstOrNull()?.duration?.let { (it / 60).toInt() }
    }.getOrNull()
}

/**
 * Home and work, one tap each: the time to drive there from where the car is
 * (no traffic: the free router has none), and a tap starts the guidance.
 */
@Composable
internal fun HomeWorkCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val places by PlacesStore.places.collectAsState()
    // Only whether there is a fix: the loop reads the position itself, so the tile doesn't follow each fix.
    val hasFix by LocationFeed.hasFix.collectAsState()
    var homeMin by remember { mutableStateOf<Int?>(null) }
    var workMin by remember { mutableStateOf<Int?>(null) }
    // Not on a page off screen: two router requests every five minutes per composed copy otherwise.
    val active = LocalPageActive.current
    LaunchedEffect(places.home, places.work, hasFix, active) {
        if (!active) return@LaunchedEffect
        while (true) {
            val loc = LocationFeed.location.value
            if (loc != null) {
                places.home?.let { homeMin = minutesTo(context, loc.latitude, loc.longitude, it) }
                places.work?.let { workMin = minutesTo(context, loc.latitude, loc.longitude, it) }
            }
            delay(REFRESH_MS)
        }
    }
    // Guidance starts after the held step ("Guidance to Home", Cancel, three
    // seconds), as on every tile that changes the route: a finger that meant
    // the other button, or nothing, takes it back.
    val pending = rememberPendingAction()
    val homeName = stringResource(R.string.widgets_hw_home)
    val workName = stringResource(R.string.widgets_hw_work)
    fun go(name: String, place: Place) = pending.arm(context.getString(R.string.phone_guidance_to, name)) { NavHandoff.start(context, place) }
    Card(modifier = modifier) {
        if (places.home == null && places.work == null) {
            Box(Modifier.fillMaxSize().padding(DashSpace.Lg), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.widgets_hw_empty), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            return@Card
        }
        Box(Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize().padding(DashSpace.Md), horizontalArrangement = Arrangement.spacedBy(DashSpace.Md)) {
                places.home?.let { PlaceButton(Icons.Filled.Home, homeName, homeMin, Modifier.weight(1f)) { go(homeName, it) } }
                places.work?.let { PlaceButton(Icons.Filled.Work, workName, workMin, Modifier.weight(1f)) { go(workName, it) } }
            }
            PendingActionStrip(pending, Modifier.align(Alignment.BottomCenter).padding(DashSpace.Sm))
        }
    }
}

@Composable
private fun PlaceButton(icon: ImageVector, name: String, minutes: Int?, modifier: Modifier, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Box(
        modifier = modifier.fillMaxHeight().clip(DashShape.Medium).background(DashColors.CardHi)
            .clickable(role = Role.Button, onClickLabel = name) { tap(); onClick() }.padding(DashSpace.Md),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(30.dp))
            Text(name, color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                minutes?.let { formatMinutes(it) } ?: "--",
                color = DashColors.TextPrimary, fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineSmall
            )
        }
    }
}

@Composable
private fun formatMinutes(min: Int): String =
    if (min >= 60) stringResource(R.string.info_map_duration_hm, min / 60, min % 60) else stringResource(R.string.info_map_duration_min, min)

@Composable
internal fun homeWorkFace(): WidgetFace {
    val context = LocalContext.current
    val places by PlacesStore.places.collectAsState()
    val home = places.home
    val work = places.work
    val homeName = stringResource(R.string.widgets_hw_home)
    val workName = stringResource(R.string.widgets_hw_work)
    // The same held step as the standard card, in the designed tile's strip.
    val pending = LocalPendingAction.current
    fun go(name: String, place: Place) = pending.arm(context.getString(R.string.phone_guidance_to, name)) { NavHandoff.start(context, place) }
    return WidgetFace(
        icon = Icons.Filled.Home,
        title = BuiltinKind.HOME_WORK.label,
        value = homeName,
        textValue = true,
        caption = if (home == null && work == null) stringResource(R.string.widgets_hw_empty) else "",
        actions = listOfNotNull(
            home?.let { FaceAction(Icons.Filled.Home, homeName, onClick = { go(homeName, it) }, primary = true) },
            work?.let { FaceAction(Icons.Filled.Work, workName, onClick = { go(workName, it) }) }
        )
    )
}
