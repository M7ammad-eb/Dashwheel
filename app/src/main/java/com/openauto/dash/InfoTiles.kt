@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openauto.dash

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhoneMissed
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openauto.dash.link.RecentCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Information and convenience widgets: clock, weather, calendar, quick dial,
 * notifications and audio volume.
 */

// --- Permission helper ------------------------------------------------------------

/** [blocked]: Android no longer shows its dialog for this one; [request] then leads to the app's page in the system settings. */
internal class PermissionState(val granted: Boolean, val blocked: Boolean = false, val request: () -> Unit)

/**
 * A request that came back denied without Android showing its dialog: no
 * rationale was due before it nor after it, which is how a permanent denial
 * looks. A rationale due before means the dialog was shown and the driver
 * just chose; only the next tap is the dead one.
 */
internal fun deniedForGood(granted: Boolean, rationaleBefore: Boolean, rationaleAfter: Boolean): Boolean =
    !granted && !rationaleBefore && !rationaleAfter

/**
 * Runtime permission as state, with a launcher to ask for it. After a
 * permanent denial Android answers no without asking: the tap then opens the
 * app's page in the system settings, so Allow never does nothing.
 */
@Composable
internal fun rememberPermission(permission: String): PermissionState {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    fun check() = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun rationale() = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    var granted by remember { mutableStateOf(check()) }
    var blocked by remember { mutableStateOf(false) }
    var rationaleBefore by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { answer ->
        granted = answer
        blocked = activity != null && deniedForGood(answer, rationaleBefore, rationale())
        if (blocked) {
            context.launchSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }
    }
    // Checked again on every return to the launcher: the permission may have
    // been granted (or taken back) in the system settings meanwhile.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, permission) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = check()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return PermissionState(granted, blocked && !granted) {
        rationaleBefore = rationale()
        launcher.launch(permission)
    }
}

/** Centered "needs X" state with a button, used by tiles gated on a permission. */
@Composable
internal fun NeedsAccess(icon: ImageVector, title: String, action: String, onAction: () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val button: @Composable () -> Unit = {
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.OnAccent),
                shape = DashShape.Medium,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
            ) { Text(action, maxLines = 1) }
        }
        if (maxHeight < 110.dp) {
            // Short tile: one line, icon + text + button.
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = DashColors.Muted, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    title, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                button()
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(icon, contentDescription = null, tint = DashColors.Muted, modifier = Modifier.size(30.dp))
                Spacer(Modifier.height(4.dp))
                Text(title, color = DashColors.TextSecondary, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                Spacer(Modifier.height(6.dp))
                button()
            }
        }
    }
}

// --- Clock ----------------------------------------------------------------------

/** Time and date, big. Tap opens the clock / alarms app. */
@Composable
internal fun ClockCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Card(modifier = modifier) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clickable {
                    runCatching {
                        context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                .padding(DashSpace.Lg)
        ) {
            val numSize = min(maxWidth.value * 0.28f, maxHeight.value * 0.55f).coerceIn(36f, 120f).roundToInt()
            ClockReadout(numSize)
        }
    }
}

/** The ticking part of the clock tile, so each second recomposes only these lines. */
@Composable
private fun ClockReadout(numSize: Int) {
    val now = rememberNow(1_000L)
    val locale = Locale.getDefault()
    val u = LocalUnits.current
    val zone = rememberZone()
    val timeFmt = remember(locale, u.clock24, zone) { SimpleDateFormat(u.digitsPattern(), locale).inZone(zone) }
    val dateFmt = remember(locale, zone) { SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).inZone(zone) }
    val time = timeFmt.format(now)
    val date = dateFmt.format(now)
    // Hours and minutes only: seconds ticking beside them pulled the eye for nothing. A 12-hour clock's AM / PM stays small.
    val amPm = u.amPm(now, locale)
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            HeroNumber(text = time, size = numSize)
            if (amPm != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    amPm,
                    color = DashColors.TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = (numSize * 0.16f).dp)
                )
            }
        }
        Text(
            date.replaceFirstChar { it.uppercase() },
            color = DashColors.TextSecondary,
            letterSpacing = 1.sp,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// --- Weather --------------------------------------------------------------------

internal fun weatherIcon(code: Int): ImageVector = when (code) {
    0, 1 -> Icons.Filled.WbSunny
    2 -> Icons.Filled.WbCloudy
    3 -> Icons.Filled.Cloud
    45, 48 -> Icons.Filled.Dehaze
    in 51..67, in 80..82 -> Icons.Filled.Grain
    in 71..77, 85, 86 -> Icons.Filled.AcUnit
    95, 96, 99 -> Icons.Filled.FlashOn
    else -> Icons.Filled.Cloud
}

/** Current conditions at the car's position (Open-Meteo, refreshed every 15 min). */
@Composable
internal fun WeatherCard(modifier: Modifier = Modifier) {
    UseLocationFeed()
    // The fetch is keyed on the position to 1/20 degree: the tile doesn't follow each fix.
    val location by rememberLocationCell(20)
    val weather by WeatherRepo.weather.collectAsState()
    val error by WeatherRepo.error.collectAsState()
    val now = rememberNow(60_000L)
    LaunchedEffect(location?.latitude?.let { (it * 20).roundToInt() }, location?.longitude?.let { (it * 20).roundToInt() }) {
        val l = location ?: return@LaunchedEffect
        while (true) {
            WeatherRepo.refresh(l.latitude, l.longitude)
            delay(60_000)
        }
    }

    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg)) {
            TileHeader(stringResource(R.string.info_weather_title)) {
                val l = location
                if (l != null) {
                    var busy by remember { mutableStateOf(false) }
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    val refreshLabel = stringResource(R.string.info_weather_refresh)
                    IconButton(
                        onClick = {
                            // Asked for where the car is now, not where it entered its cell.
                            val here = LocationFeed.location.value ?: l
                            scope.launch { busy = true; WeatherRepo.refresh(here.latitude, here.longitude, force = true); busy = false }
                        },
                        modifier = Modifier.size(DashSize.Touch)
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = refreshLabel, tint = if (busy) DashColors.Accent else DashColors.Muted, modifier = Modifier.size(20.dp))
                    }
                }
            }
            val w = weather
            val u = LocalUnits.current
            when {
                w != null -> Row(modifier = Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(weatherIcon(w.code), contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            HeroNumber(text = u.temp(w.tempC).toString(), size = 44)
                            Spacer(Modifier.width(4.dp))
                            Text(u.tempUnit, color = DashColors.TextSecondary, fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                        }
                        Text(w.condition, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                        // An old answer, or a refresh that failed (the button's included): its time
                        // and the reason take the detail line. Not in the demo, whose weather is made up once.
                        val err = error
                        if (!DemoMode.isOn && showsAge(w.fetchedAt, now.time, WeatherRepo.REFRESH_MS, failed = err != null)) AsOfLine(
                            w.fetchedAt,
                            when {
                                err == null -> null
                                err.isBlank() -> stringResource(R.string.info_weather_unavailable)
                                else -> stringResource(R.string.info_weather_unavailable_detail, err)
                            }
                        ) else Text(
                            if (!w.hiC.isNaN()) {
                                stringResource(
                                    if (u.imperial) R.string.units_weather_details_range_mph else R.string.info_weather_details_range,
                                    u.temp(w.feelsC), u.speed(w.windKmh), u.temp(w.loC), u.temp(w.hiC)
                                )
                            } else {
                                stringResource(
                                    if (u.imperial) R.string.units_weather_details_mph else R.string.info_weather_details,
                                    u.temp(w.feelsC), u.speed(w.windKmh)
                                )
                            },
                            color = DashColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                location == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.info_waiting_gps), color = DashColors.Muted)
                }
                else -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val err = error
                    Text(
                        when {
                            err == null -> stringResource(R.string.info_weather_loading)
                            err.isBlank() -> stringResource(R.string.info_weather_unavailable)
                            else -> stringResource(R.string.info_weather_unavailable_detail, err)
                        },
                        color = DashColors.Muted, textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * "As of 14:05" under an answer that is no longer fresh, on the driver's 12 or
 * 24 hour clock, with [reason] (why there is no newer one) beside it.
 */
@Composable
internal fun AsOfLine(fetchedAt: Long, reason: String?, modifier: Modifier = Modifier) {
    val timeFmt = rememberTimeFormat()
    val asOf = stringResource(R.string.info_as_of, timeFmt.format(Date(fetchedAt)))
    Text(
        buildAnnotatedString {
            append(asOf)
            if (reason != null) withStyle(SpanStyle(color = DashColors.Warning)) { append(" · $reason") }
        },
        color = DashColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

// --- Calendar ---------------------------------------------------------------------

internal data class AgendaEvent(val title: String, val begin: Long, val end: Long, val allDay: Boolean, val location: String)

internal fun loadAgenda(context: Context, hours: Int = 36): List<AgendaEvent> {
    val now = System.currentTimeMillis()
    val until = now + hours * 3_600_000L
    val uri = ContentUris.appendId(ContentUris.appendId(CalendarContract.Instances.CONTENT_URI.buildUpon(), now), until).build()
    val projection = arrayOf(
        CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
        CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION
    )
    val out = mutableListOf<AgendaEvent>()
    runCatching {
        context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext() && out.size < 6) {
                val end = c.getLong(2)
                if (end < now) continue
                out += AgendaEvent(
                    // Blank titles get a localized "(No title)" at display time.
                    title = c.getString(0).orEmpty(),
                    begin = c.getLong(1), end = end,
                    allDay = c.getInt(3) == 1,
                    location = c.getString(4).orEmpty()
                )
            }
        }
    }
    return out
}

/**
 * The phone's list when it sent one, else this unit's own: a head unit
 * rarely has contacts or a calendar, the driver's phone does. An empty list
 * from the phone only wins over an empty one here. Every tile showing the
 * list asks this, so the card and the designed faces never disagree.
 */
internal fun <T> phoneOrUnit(phone: List<T>?, unit: List<T>): List<T> =
    if (phone != null && (phone.isNotEmpty() || unit.isEmpty())) phone else unit

/** The phone's events still to come (it sent them a while ago), as many as the unit's own list holds. */
internal fun upcoming(events: List<AgendaEvent>, now: Long, max: Int = AGENDA_MAX): List<AgendaEvent> =
    events.filter { it.end >= now }.take(max)

private const val AGENDA_MAX = 6

/** The Agenda tile's events and where they came from. */
internal class AgendaSource(
    val events: List<AgendaEvent>,
    val fromPhone: Boolean,
    /** The phone sent its agenda: an empty one means nothing planned, not "link your phone". */
    val phoneSent: Boolean,
    val access: PermissionState
) {
    /** Nothing to show and nothing in the way of showing it. */
    val emptyText: Int get() = if (phoneSent) R.string.info_agenda_empty else R.string.phone_agenda_empty
}

/** The next events for the Agenda tile ([phoneOrUnit]): the phone's, else the unit's calendar, re-read every five minutes. */
@Composable
internal fun rememberAgendaSource(): AgendaSource {
    val context = LocalContext.current
    val perm = rememberPermission(Manifest.permission.READ_CALENDAR)
    var own by remember { mutableStateOf<List<AgendaEvent>>(emptyList()) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(perm.granted) {
        while (true) {
            if (perm.granted) own = withContext(Dispatchers.IO) { loadAgenda(context) }
            now = System.currentTimeMillis()
            delay(5 * 60_000)
        }
    }
    val phone = PhoneLink.lists.collectAsState().value.agenda?.let { upcoming(it, now) }
    val events = phoneOrUnit(phone, if (perm.granted) own else emptyList())
    return AgendaSource(events, fromPhone = phone != null && events === phone, phoneSent = phone != null, access = perm)
}

/** Guidance to an event's place, by its address: the navigation app looks it up. */
internal fun guideToEvent(context: Context, e: AgendaEvent) {
    if (e.location.isBlank()) return
    NavHandoff.go(context, e.title.ifBlank { e.location }, null, null, e.location)
}

/**
 * Next events from the driver's phone, else the device calendars. An event
 * with a place: a tap says "Guidance to X" and leaves three seconds to cancel
 * ([PendingActionStrip]) before guidance starts.
 */
@Composable
internal fun CalendarCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val agenda = rememberAgendaSource()
    val events = agenda.events
    val pending = rememberPendingAction()
    fun guide(e: AgendaEvent) = pending.arm(context.getString(R.string.phone_guidance_to, e.title.ifBlank { e.location })) { guideToEvent(context, e) }

    Card(modifier = modifier) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg)) {
                TileHeader(stringResource(R.string.info_agenda_title)) {
                    // The unit's calendar app knows nothing of the phone's events.
                    if (!agenda.fromPhone) TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build())
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                        modifier = Modifier.height(DashSize.Touch), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    ) { Text(stringResource(R.string.info_open), color = DashColors.AccentInk, style = MaterialTheme.typography.labelMedium) }
                }
                when {
                    events.isEmpty() && !agenda.phoneSent && !agenda.access.granted -> NeedsAccess(
                        Icons.Filled.Event, stringResource(R.string.info_agenda_needs_access),
                        stringResource(if (agenda.access.blocked) R.string.dash_open_settings else R.string.info_agenda_allow), agenda.access.request
                    )
                    events.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(stringResource(agenda.emptyText), color = DashColors.Muted, textAlign = TextAlign.Center)
                    }
                    else -> LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(events) { e -> AgendaRow(e) { guide(e) } }
                    }
                }
            }
            PendingActionStrip(pending, Modifier.align(Alignment.BottomCenter).padding(DashSpace.Sm))
        }
    }
}

@Composable
private fun AgendaRow(e: AgendaEvent, onGuide: () -> Unit) {
    val timeFmt = rememberTimeFormat()
    val dayFmt = remember(timeFmt) { SimpleDateFormat("EEE", Locale.getDefault()).inZone(timeFmt.timeZone) }
    val today = remember(e.begin) {
        val a = java.util.Calendar.getInstance(); val b = java.util.Calendar.getInstance().apply { timeInMillis = e.begin }
        a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR) && a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)
    }
    val ongoing = e.begin <= System.currentTimeMillis()
    val noTitle = stringResource(R.string.info_agenda_no_title)
    val allDay = stringResource(R.string.info_agenda_all_day)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DashShape.Small)
            .itemFill(if (DashColors.Glass) DashColors.haze(0.06f) else DashColors.CardHi, DashShape.Small)
            .clickable(enabled = e.location.isNotBlank(), onClick = onGuide)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(30.dp)
                .clip(CircleShape)
                .background(if (ongoing) DashColors.Good else DashColors.Accent)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(e.title.ifBlank { noTitle }, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium)
            Text(
                buildString {
                    if (!today) append(dayFmt.format(Date(e.begin))).append(" ")
                    append(if (e.allDay) allDay else "${timeFmt.format(Date(e.begin))} – ${timeFmt.format(Date(e.end))}")
                    if (e.location.isNotBlank()) append(" · ").append(e.location)
                },
                color = DashColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// --- Quick dial -------------------------------------------------------------------

internal data class Favourite(val name: String, val number: String?, val photo: Bitmap?)

internal fun loadFavourites(context: Context, limit: Int = 8): List<Favourite> {
    val cr = context.contentResolver
    val out = mutableListOf<Favourite>()
    runCatching {
        cr.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI),
            "${ContactsContract.Contacts.STARRED} = 1 AND ${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1",
            null,
            "${ContactsContract.Contacts.TIMES_CONTACTED} DESC"
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: continue
                val number = cr.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(id.toString()), null
                )?.use { p -> if (p.moveToFirst()) p.getString(0) else null }
                val photo = c.getString(2)?.let { u ->
                    runCatching { cr.openInputStream(Uri.parse(u))?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
                }
                out += Favourite(name, number, photo)
            }
        }
    }
    return out
}

/** One of this unit's contacts, for the Phone screen's list (PhoneScreen.kt): its default number, else its first. */
internal data class UnitContact(val name: String, val number: String, val starred: Boolean)

/** A number as the unit's phonebook lists it, with its contact's name and star. */
internal data class ContactRow(val contactId: Long, val name: String, val number: String, val primary: Boolean, val starred: Boolean)

/**
 * Every contact with a number, one number each (its default one, else the
 * first listed), by name as the unit's language sorts names. A contact with
 * no name shows as its number.
 */
internal fun pickContacts(rows: List<ContactRow>): List<UnitContact> =
    rows.filter { it.number.isNotBlank() }
        .groupBy { it.contactId }.values
        .map { numbers ->
            val r = numbers.firstOrNull { it.primary } ?: numbers.first()
            UnitContact(r.name.trim().ifBlank { r.number }, r.number, numbers.any { it.starred })
        }
        .sortedWith(compareBy(Collator.getInstance(), UnitContact::name))

/** This unit's whole phonebook (the one its Bluetooth synced from the phone, mostly), one query. */
internal fun loadContacts(context: Context): List<UnitContact> {
    val rows = mutableListOf<ContactRow>()
    runCatching {
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.STARRED
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                rows += ContactRow(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getInt(3) != 0, c.getInt(4) != 0)
            }
        }
    }
    return pickContacts(rows)
}

/**
 * The unit's contacts, kept for the whole app and read again when they
 * change: the starred ones for the Quick dial, and the whole phonebook
 * while the Phone screen is open ([wantAll]). A head unit's Bluetooth sync
 * wipes its phonebook and writes it back now and then: read in between, the
 * tile went empty for half a minute (issue #118). So a list that comes back
 * empty is believed only after [WIPE_HOLD_MS]; a list with contacts at once.
 */
internal object UnitContacts {
    /** Longer than a phonebook re-sync takes; a phone really unpaired empties the tile after this. */
    private const val WIPE_HOLD_MS = 90_000L
    /** A sync writes its contacts one by one: read once they settle, not on each. */
    private const val SETTLE_MS = 2_000L

    private val _favourites = MutableStateFlow<List<Favourite>>(emptyList())
    val favourites: StateFlow<List<Favourite>> = _favourites.asStateFlow()
    /** Every contact with a number; null until a Phone screen first asked for them. */
    private val _contacts = MutableStateFlow<List<UnitContact>?>(null)
    val contacts: StateFlow<List<UnitContact>?> = _contacts.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val changed = MutableStateFlow(0)
    /** Phone screens open: the whole phonebook is read only for them. */
    private val wanted = MutableStateFlow(0)
    private var started = false

    /** Reads the contacts from now on (READ_CONTACTS granted); again when they change. */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        runCatching {
            app.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, object : android.database.ContentObserver(null) {
                override fun onChange(selfChange: Boolean) = changed.update { it + 1 }
            })
        }
        scope.launch {
            var settled = 0
            combine(changed, wanted) { n, w -> n to (w > 0) }.collectLatest { (n, all) ->
                if (n != settled) {
                    delay(SETTLE_MS)
                    settled = n
                }
                read(app, all)
            }
        }
    }

    /** A Phone screen opened ([on]) or closed: the whole phonebook is read, and followed, only while one is open. */
    fun wantAll(on: Boolean) = wanted.update { if (on) it + 1 else (it - 1).coerceAtLeast(0) }

    private suspend fun read(app: Context, all: Boolean) {
        var favourites = loadFavourites(app)
        var contacts = if (all) loadContacts(app) else null
        val wiped = (favourites.isEmpty() && _favourites.value.isNotEmpty()) ||
            (contacts != null && contacts.isEmpty() && !_contacts.value.isNullOrEmpty())
        if (wiped) {
            // Being re-synced, most likely: the old lists stand meanwhile. Any change reads again (and cancels this).
            delay(WIPE_HOLD_MS)
            favourites = loadFavourites(app)
            if (all) contacts = loadContacts(app)
        }
        _favourites.value = favourites
        if (contacts != null) _contacts.value = contacts
    }
}

/** A missed call worth calling back: the phone's latest call, missed within the last [withinMs]. */
internal fun callBack(calls: List<RecentCall>?, now: Long, withinMs: Long = CALL_BACK_MS): RecentCall? =
    calls?.maxByOrNull { it.at }?.takeIf { it.type == RecentCall.Type.MISSED && now - it.at in 0..withinMs }

/** Half a day: a call missed yesterday evening is no longer the one to return on the way to work. */
private const val CALL_BACK_MS = 12 * 3_600_000L

/** The Quick dial tile's contacts and where they came from. */
internal class QuickDialSource(
    val favourites: List<Favourite>,
    /** The phone's last call, when it was missed and is recent: one tap calls back. */
    val callBack: RecentCall?,
    val phoneSent: Boolean,
    val access: PermissionState
) {
    val emptyText: Int get() = if (phoneSent) R.string.info_quickdial_empty else R.string.phone_quickdial_empty
}

/** The Quick dial's contacts ([phoneOrUnit]): the phone's favourites, else the unit's starred contacts. */
@Composable
internal fun rememberQuickDialSource(): QuickDialSource {
    val context = LocalContext.current
    val perm = rememberPermission(Manifest.permission.READ_CONTACTS)
    LaunchedEffect(perm.granted) {
        if (perm.granted) UnitContacts.start(context)
    }
    val own by UnitContacts.favourites.collectAsState()
    val lists by PhoneLink.lists.collectAsState()
    val favourites = phoneOrUnit(lists.favourites, if (perm.granted) own else emptyList())
    return QuickDialSource(favourites, callBack(lists.calls, System.currentTimeMillis()), lists.favourites != null, perm)
}

/** The narrowest quick-dial column a finger is asked to hit at the wheel, and the gap between two. */
private const val QUICK_DIAL_MIN_DP = 72f
private const val QUICK_DIAL_GAP_DP = 8f

/** How many of [favourites] fit side by side in [widthDp] with no column under [QUICK_DIAL_MIN_DP]: fewer faces rather than narrow ones. */
internal fun quickDialColumns(widthDp: Float, favourites: Int): Int =
    ((widthDp + QUICK_DIAL_GAP_DP) / (QUICK_DIAL_MIN_DP + QUICK_DIAL_GAP_DP)).toInt().coerceIn(1, favourites.coerceAtLeast(1))

/**
 * Calls [number]: through the linked phone, which places the call itself (its
 * sound on the car's Bluetooth as any call), else the unit's own Bluetooth
 * when its firmware dials, else this unit's dialer with the number in, as
 * without a phone. Never a real call during the demo: its contacts are made up.
 */
internal fun dialNumber(context: Context, number: String) {
    val way = when {
        !DemoMode.isOn && PhoneLink.dial(number) -> "the linked phone"
        // The unit's own Bluetooth, where its firmware takes a number to call (FYT).
        !DemoMode.isOn && UnitPhone.dial(number) -> "the unit's Bluetooth"
        context.launchSafely(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))) -> "the unit's dialer"
        // A unit with no dialer (its phone is a car app of its own) at least opens that app.
        UnitPhoneApp.open(context) -> "the unit's phone app, no number"
        else -> "nothing: no phone link, no dialer, no phone app"
    }
    // Which way a call went is what a tester's log needs when nothing rings (issue #118).
    DebugLog.note(context, "Call: through $way")
}

/**
 * Starred contacts as big tap-to-call targets, from the driver's phone when
 * linked. A missed call adds a Call back row. A tap says who is about to be
 * called and leaves three seconds to cancel ([PendingActionStrip]).
 */
@Composable
internal fun QuickDialCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val source = rememberQuickDialSource()
    val perm = source.access
    val favourites = source.favourites
    // The driver's clock (12 or 24 hours), as every other tile shows a time.
    val timeFmt = rememberTimeFormat()
    val pending = rememberPendingAction()
    fun call(name: String, number: String) = pending.arm(context.getString(R.string.phone_calling, name)) { dialNumber(context, number) }

    Card(modifier = modifier) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg)) {
                TileHeader(stringResource(R.string.info_quickdial_title)) {
                    TextButton(
                        onClick = { PhoneScreen.open(context) },
                        modifier = Modifier.height(DashSize.Touch), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    ) { Text(stringResource(R.string.info_quickdial_dialer), color = DashColors.AccentInk, style = MaterialTheme.typography.labelMedium) }
                }
                when {
                    favourites.isEmpty() && !source.phoneSent && !perm.granted -> NeedsAccess(
                        Icons.Filled.Call, stringResource(R.string.info_quickdial_needs_access),
                        stringResource(if (perm.blocked) R.string.dash_open_settings else R.string.info_quickdial_allow), perm.request
                    )
                    favourites.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(stringResource(source.emptyText), color = DashColors.Muted, textAlign = TextAlign.Center)
                    }
                    else -> BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        val avatar = min(maxHeight.value * 0.55f, 64f).coerceAtLeast(36f).dp
                        val shown = favourites.take(quickDialColumns(maxWidth.value, favourites.size))
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(QUICK_DIAL_GAP_DP.dp)
                        ) {
                            shown.forEach { f ->
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(DashShape.Medium)
                                        .clickable(enabled = f.number != null) { f.number?.let { call(f.name, it) } }
                                        .padding(vertical = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(avatar)
                                            .clip(CircleShape)
                                            .background(DashColors.AccentBrush)
                                            .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val bmp = f.photo
                                        if (bmp != null) {
                                            Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                        } else {
                                            Text(
                                                f.name.split(' ').take(2).mapNotNull { it.firstOrNull()?.uppercase() }.joinToString(""),
                                                color = DashColors.OnAccent, fontWeight = FontWeight.ExtraBold,
                                                // Sized to the disc: the theme's title size spilled out of a small one.
                                                fontSize = (avatar.value * 0.4f).coerceIn(MIN_TEXT_DP, 22f).sp,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(f.name.substringBefore(' '), color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                source.callBack?.let { missed ->
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = DashSize.MenuRow)
                            .clip(DashShape.Small)
                            .itemFill(if (DashColors.Glass) DashColors.haze(0.06f) else DashColors.CardHi, DashShape.Small)
                            .clickable { call(missed.name ?: missed.number, missed.number) }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.PhoneMissed, contentDescription = null, tint = DashColors.Warning,modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.phone_call_back, missed.name ?: missed.number), color = DashColors.TextPrimary,
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        Text(timeFmt.format(Date(missed.at)), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            PendingActionStrip(pending, Modifier.align(Alignment.BottomCenter).padding(DashSpace.Sm))
        }
    }
}

// --- Notifications ----------------------------------------------------------------

/**
 * Recent notifications from other apps; tap one to open it. The driver's
 * phone's ones (over [PhoneLink]) open a sheet to hear and answer them.
 */
@Composable
internal fun NotificationsCard(hasAccess: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val items by NotificationFeed.items.collectAsState()
    val phoneConnected = PhoneLink.state.collectAsState().value is PhoneLinkState.Connected
    val phoneNotifications by PhoneLink.notificationsOn.collectAsState()
    val timeFmt = rememberTimeFormat()
    var opened by remember { mutableStateOf<NotifItem?>(null) }

    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg)) {
            TileHeader(stringResource(R.string.info_notif_title)) {
                if (items.isNotEmpty()) {
                    TextButton(onClick = { NotificationFeed.dismissAll() }, modifier = Modifier.height(DashSize.Touch), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                        Text(stringResource(R.string.info_clear), color = DashColors.Muted, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            // Linked, but the phone keeps its notifications to itself (an older companion says nothing: its notifications tell).
            if (phoneConnected && !phoneNotifications && items.none { it.fromPhone }) {
                Text(stringResource(R.string.info_notif_phone_access), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 6.dp))
            }
            when {
                // The unit's own access is asked for only while its notifications are wanted: with
                // them switched off, the phone's are the only ones, and a dropped link asked for it (#123).
                !hasAccess && NotificationFeed.showsUnit(context) && !phoneConnected && items.none { it.fromPhone } -> NeedsAccess(
                    Icons.Filled.Notifications, stringResource(R.string.info_notif_needs_access),
                    stringResource(R.string.info_grant_access)
                ) {
                    CarMediaController.openNotificationAccessSettings(context)
                }
                items.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.info_notif_empty), color = DashColors.Muted)
                }
                else -> LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(items, key = { it.key }) { n ->
                        // Swiped aside, it leaves the card, like on a phone.
                        SwipeAway(onDismiss = { NotificationFeed.remove(n.key) }) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(DashShape.Small)
                                    .itemFill(if (DashColors.Glass) DashColors.haze(0.06f) else DashColors.CardHi, DashShape.Small)
                                    .clickable { if (n.fromPhone) opened = n else runCatching { n.contentIntent?.send() } }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val bmp = n.icon
                                if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)))
                                else Icon(Icons.Filled.Notifications, contentDescription = null, tint = DashColors.Muted, modifier = Modifier.size(30.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(n.title.ifEmpty { n.appLabel }, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                    if (n.text.isNotEmpty()) Text(n.text, color = DashColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall)
                                }
                                Spacer(Modifier.width(8.dp))
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(timeFmt.format(Date(n.postedAt)), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (n.fromPhone) {
                                            Icon(
                                                Icons.Filled.PhoneAndroid, contentDescription = stringResource(R.string.phone_from_phone),
                                                tint = DashColors.Accent, modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(Modifier.width(3.dp))
                                        }
                                        Text(n.appLabel, color = DashColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    opened?.let { item ->
        // Follows the live item (new lines of the conversation); closes once it's gone from the phone.
        val live = items.firstOrNull { it.key == item.key }
        LaunchedEffect(live == null) { if (live == null) opened = null }
        if (live != null) PhoneMessageSheet(live, onDismiss = { opened = null })
    }
}

// --- Audio ------------------------------------------------------------------------

/**
 * Media volume with mute, − and +, plus shortcuts to the sound and Bluetooth
 * settings. Where the head unit ignores Android's media volume (see
 * [MediaVolume]) the slider moves the unit's own volume, or gives way to the
 * buttons, which then press the volume keys like the knob.
 */
@Composable
internal fun AudioCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val audio = remember { MediaVolume.audio(context) }
    val onUnit by MediaVolume.onUnit.collectAsState()
    val max = remember(onUnit) { MediaVolume.max(audio) }
    var dragging by remember { mutableStateOf(false) }
    // Follow the hardware knob / other apps while nobody is dragging the slider.
    var volume by rememberMusicVolume(audio, hold = { dragging })
    val byKeys by MediaVolume.byKeys.collectAsState()
    val unavailable by MediaVolume.unavailable.collectAsState()
    LaunchedEffect(Unit) { MediaVolume.check(audio) }
    val muted = !byKeys && volume == 0
    fun act(change: () -> Unit) {
        change()
        volume = MediaVolume.level(audio)
    }

    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg)) {
            TileHeader(stringResource(R.string.info_audio_title)) {
                if (!byKeys) {
                    Text("${(volume * 100f / max).roundToInt()}%", color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                }
            }
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (byKeys) Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally) else Arrangement.Start
            ) {
                val buttonSize = if (byKeys) DashSize.TouchPrimary else DashSize.Touch
                IconButton(onClick = { act { MediaVolume.toggleMute(context) } }, modifier = Modifier.size(buttonSize)) {
                    Icon(if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, contentDescription = stringResource(if (muted) R.string.info_audio_unmute else R.string.info_audio_mute),
                        tint = if (muted) DashColors.Warning else DashColors.TextPrimary, modifier = Modifier.size(26.dp))
                }
                IconButton(onClick = { act { MediaVolume.lower(context) } }, modifier = Modifier.size(buttonSize)) {
                    Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.design_volume_down), tint = DashColors.TextPrimary, modifier = Modifier.size(24.dp))
                }
                if (!byKeys) {
                    Slider(
                        value = volume.toFloat(),
                        onValueChange = { v ->
                            dragging = true
                            volume = v.roundToInt()
                            MediaVolume.set(context, volume)
                        },
                        onValueChangeFinished = {
                            dragging = false
                            volume = MediaVolume.level(audio)
                        },
                        valueRange = 0f..max.toFloat(),
                        steps = (max - 1).coerceAtLeast(0),
                        colors = SliderDefaults.colors(
                            thumbColor = if (DashColors.Light) DashColors.Accent else Color.White, activeTrackColor = DashColors.Accent,
                            inactiveTrackColor = if (DashColors.Glass) DashColors.well(0.35f) else DashColors.CardHi,
                            activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
                IconButton(onClick = { act { MediaVolume.raise(context) } }, modifier = Modifier.size(buttonSize)) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.design_volume_up), tint = DashColors.TextPrimary, modifier = Modifier.size(24.dp))
                }
            }
            if (byKeys) {
                Text(
                    stringResource(if (unavailable) R.string.info_audio_unavailable else R.string.info_audio_by_keys),
                    color = if (unavailable) DashColors.Warning else DashColors.Muted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallAction(stringResource(R.string.info_audio_sound)) { context.launchSafely(Intent(Settings.ACTION_SOUND_SETTINGS)) }
                SmallAction("Bluetooth", Icons.Filled.Bluetooth) { context.launchSafely(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            }
        }
    }
}

/**
 * The media volume, following the hardware knob and other apps: the system
 * broadcasts VOLUME_CHANGED_ACTION on every change (QF firmware its own
 * com.qf.action.VOLUME_CHANGED, see [MediaVolume]), and a slow poll remains as
 * a fallback for ROMs that don't send it. Outside changes are ignored while
 * [hold] is true (the slider is being dragged). Shared by the audio tile and
 * its designed face.
 */
@Composable
internal fun rememberMusicVolume(audio: AudioManager, hold: () -> Boolean = { false }): MutableIntState {
    val context = LocalContext.current
    val volume = remember { mutableIntStateOf(MediaVolume.level(audio)) }
    val held by rememberUpdatedState(hold)
    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (!held()) volume.intValue = MediaVolume.level(audio)
            }
        }
        val filter = android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION").apply {
            addAction("com.qf.action.VOLUME_CHANGED")
        }
        // Exported: the QF framework is another app. The broadcast only makes the tile read the level again.
        runCatching { ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED) }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    // The poll holds on a page off screen ([LocalPageActive]) and reads once as it shows again.
    val active = LocalPageActive.current
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        if (!held()) volume.intValue = MediaVolume.level(audio)
        while (true) {
            delay(5000)
            if (!held()) volume.intValue = MediaVolume.level(audio)
        }
    }
    return volume
}

@Composable
private fun SmallAction(label: String, icon: ImageVector? = null, onClick: () -> Unit) {
    val shape = DashShape.Pill
    Row(
        modifier = Modifier
            .clip(shape)
            .itemFill(if (DashColors.Glass) DashColors.haze(0.08f) else DashColors.CardHi, shape)
            .clickable(onClick = onClick)
            // A pill, but never under the driving minimum height.
            .heightIn(min = DashSize.Touch)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, letterSpacing = 0.02.em, style = MaterialTheme.typography.labelMedium)
    }
}
