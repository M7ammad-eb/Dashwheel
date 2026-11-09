package com.openauto.dash

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.openauto.dash.link.ParkingTimer
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Date

/**
 * The parking paid for: when it runs out. Set on the car, it is handed to
 * the phone, which reminds the driver ten minutes before and at the end,
 * wherever they walked off to. Sent again each time the phone comes back.
 * Without a phone, the car itself says so ten minutes before, while it is on.
 */
internal object ParkingClock {
    private const val PREFS = "parking_timer"
    private const val KEY_ENDS = "ends"
    val DURATIONS_MIN = listOf(30, 60, 120)
    const val ADD_MIN = 15
    /** How long before the end the reminder comes. */
    const val REMIND_BEFORE_MS = 10 * 60_000L

    private val _endsAt = MutableStateFlow(0L)
    /** When the parking runs out, 0 for none. */
    val endsAt: StateFlow<Long> = _endsAt
    private val _onPhone = MutableStateFlow(false)
    /** Whether the phone holds the running timer, so it reminds away from the car. */
    val onPhone: StateFlow<Boolean> = _onPhone
    private var loaded = false
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var reminder: Job? = null

    /** Reads the saved one once; returns [endsAt]. */
    fun load(context: Context): StateFlow<Long> {
        appContext = appContext ?: context.applicationContext
        if (loaded) return endsAt
        loaded = true
        val ends = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_ENDS, 0L)
        _endsAt.value = if (ends > System.currentTimeMillis()) ends else 0L
        remindHere()
        return endsAt
    }

    /** The phone is back ([PhoneLink]): the running timer goes to it, and the car's own reminder stands down. */
    fun resend() {
        val ends = _endsAt.value.takeIf { it > System.currentTimeMillis() } ?: return
        _onPhone.value = PhoneLink.send(ParkingTimer(ends))
        remindHere()
    }

    /** The car's own reminder, only while no phone holds the timer and the app is on. */
    private fun remindHere() {
        reminder?.cancel()
        val ends = _endsAt.value
        if (ends <= 0L || _onPhone.value) return
        val at = ends - REMIND_BEFORE_MS
        if (at <= System.currentTimeMillis()) return
        reminder = scope.launch {
            delay(at - System.currentTimeMillis())
            val app = appContext ?: return@launch
            CarVoice.setContext(app)
            CarVoice.announce(AppLanguage.wrap(app).getString(R.string.widgets_park_say_ten), InAppNav.locale(app))
        }
    }

    /** From now for [minutes], or [minutes] more on a running one. */
    fun add(context: Context, minutes: Int) {
        val now = System.currentTimeMillis()
        val from = _endsAt.value.takeIf { it > now } ?: now
        set(context, from + minutes * 60_000L)
    }

    /** From now for [minutes], in place of a running one. */
    fun startFor(context: Context, minutes: Int) = set(context, System.currentTimeMillis() + minutes * 60_000L)

    fun stop(context: Context) = set(context, 0L)

    private fun set(context: Context, ends: Long) {
        appContext = appContext ?: context.applicationContext
        _endsAt.value = ends
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_ENDS, ends).apply()
        _onPhone.value = PhoneLink.send(ParkingTimer(ends))
        remindHere()
    }
}

private fun remaining(ms: Long): String {
    val min = (ms + 59_999) / 60_000
    return if (min >= 60) "%d:%02d".format(min / 60, min % 60) else "$min min"
}

@Composable
internal fun ParkingTimerCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val endsAt by remember { ParkingClock.load(context) }.collectAsState()
    val onPhone by ParkingClock.onPhone.collectAsState()
    val units by Units.current.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Ticks only while the timer runs: once it has passed, nothing is left to count down.
    LaunchedEffect(endsAt) { while (endsAt > 0) { now = System.currentTimeMillis(); if (endsAt <= now) break; delay(15_000) } }
    val running = endsAt > now
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg), verticalArrangement = Arrangement.SpaceBetween) {
            TileHeader(stringResource(R.string.widgets_park)) {
                if (running) {
                    IconButton(onClick = { ParkingClock.stop(context) }) {
                        Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.widgets_park_stop), tint = DashColors.Muted)
                    }
                }
            }
            if (running) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(remaining(endsAt - now), color = DashColors.TextPrimary, fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "  " + stringResource(R.string.widgets_park_until, units.time(Date(endsAt))),
                        color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium
                    )
                }
                // No phone: the car reminds while it is on, and says what to pair for more.
                if (!onPhone) {
                    Text(stringResource(R.string.widgets_park_no_phone), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                }
            } else {
                Text(stringResource(R.string.widgets_park_none), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DashSpace.Sm)) {
                if (running) {
                    ParkButton("+${ParkingClock.ADD_MIN} min", Modifier.weight(1f)) { ParkingClock.add(context, ParkingClock.ADD_MIN) }
                } else {
                    ParkingClock.DURATIONS_MIN.forEach { m ->
                        ParkButton(if (m >= 60) "${m / 60} h" else "$m min", Modifier.weight(1f)) { ParkingClock.add(context, m) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ParkButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Box(
        modifier = modifier.height(DashSize.Touch).clip(DashShape.Medium).background(DashColors.CardHi)
            .clickable(role = Role.Button) { tap(); onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
internal fun parkingTimerFace(): WidgetFace {
    val context = LocalContext.current
    val endsAt by remember { ParkingClock.load(context) }.collectAsState()
    val units by Units.current.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Ticks only while the timer runs: once it has passed, nothing is left to count down.
    LaunchedEffect(endsAt) { while (endsAt > 0) { now = System.currentTimeMillis(); if (endsAt <= now) break; delay(15_000) } }
    val running = endsAt > now
    return WidgetFace(
        icon = Icons.Filled.LocalParking,
        title = BuiltinKind.PARKING_TIMER.label,
        // The Road sign design draws a "P" for it (its else branch is a fuel pump).
        sign = SignKind.PARKING,
        value = if (running) remaining(endsAt - now) else "--",
        textValue = true,
        caption = if (running) stringResource(R.string.widgets_park_until, units.time(Date(endsAt))) else stringResource(R.string.widgets_park_none),
        alert = running && endsAt - now < 10 * 60_000L,
        actions = if (running) {
            listOf(
                FaceAction(Icons.Filled.LocalParking, "+${ParkingClock.ADD_MIN} min", onClick = { ParkingClock.add(context, ParkingClock.ADD_MIN) }, primary = true),
                FaceAction(Icons.Filled.Stop, stringResource(R.string.widgets_park_stop), onClick = { ParkingClock.stop(context) })
            )
        } else {
            listOf(FaceAction(Icons.Filled.LocalParking, "1 h", onClick = { ParkingClock.add(context, 60) }, primary = true))
        }
    )
}
