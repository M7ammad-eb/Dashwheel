package com.openauto.dash

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.openauto.dash.link.ActionResult
import com.openauto.dash.link.ConversationLine
import com.openauto.dash.link.PairingOffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/*
 * The phone link's screens: the sheet a phone message opens (hear it, answer
 * it with a quick reply or by voice), and Settings → Phone with the pairing
 * code the companion app scans.
 */

/** How long a reply / mark-as-read / dismiss may wait for the phone's answer. */
private const val SEND_TIMEOUT_MS = 20_000L

/** A phone notification, opened from the Notifications card. */
@Composable
internal fun PhoneMessageSheet(item: NotifItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val connected = PhoneLink.state.collectAsState().value is PhoneLinkState.Connected
    val phoneKey = NotificationFeed.phoneKey(item.key)
    var draft by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val quickReplies = listOf(
        stringResource(R.string.phone_quick_1), stringResource(R.string.phone_quick_2), stringResource(R.string.phone_quick_3)
    )
    val failed = stringResource(R.string.phone_failed)
    val noLink = stringResource(R.string.phone_no_link)

    LaunchedEffect(phoneKey) {
        PhoneLink.results.collect { r ->
            if (r.key != phoneKey) return@collect
            sending = false
            when {
                !r.ok -> problem = failed
                r.action == ActionResult.Action.REPLY -> {
                    Toast.makeText(context, R.string.phone_sent, Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
                else -> {
                    NotificationFeed.remove(item.key)
                    onDismiss()
                }
            }
        }
    }

    fun sent(ok: Boolean) {
        problem = if (ok) null else noLink
        sending = ok
    }

    // The phone's answer may never come: the link dropped, or it went unheard.
    LaunchedEffect(sending, connected) {
        if (!sending) return@LaunchedEffect
        if (!connected) {
            sending = false
            problem = noLink
            return@LaunchedEffect
        }
        delay(SEND_TIMEOUT_MS)
        sending = false
        problem = failed
    }

    val dictate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { draft = it }
    }
    val dictation = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            .takeIf { it.resolveActivity(context.packageManager) != null }
    }
    val lines = item.messages.ifEmpty { listOf(ConversationLine("", item.text, item.postedAt)) }
    val spoken = spokenMessage(item)

    AlertDialog(
        modifier = Modifier.keepClearOfWindows(),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val bmp = item.icon
                if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)))
                else Icon(Icons.Filled.Notifications, contentDescription = null, tint = DashColors.Muted, modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(item.title.ifEmpty { item.appLabel }, color = DashColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.appLabel, color = DashColors.Muted, style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                lines.forEach { l ->
                    Column {
                        if (l.sender.isNotEmpty() && l.sender != item.title) {
                            Text(l.sender, color = DashColors.AccentInk, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                        }
                        Text(l.text, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (item.canReply) {
                    HorizontalDivider(color = DashColors.Line)
                    Label(stringResource(R.string.phone_quick_replies))
                    quickReplies.forEach { text ->
                        OutlinedButton(
                            onClick = { sent(PhoneLink.reply(item.key, text)) },
                            enabled = connected && !sending,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        ) { Text(text, color = DashColors.TextPrimary) }
                    }
                    if (dictation != null) {
                        Button(
                            onClick = { draft = null; runCatching { dictate.launch(dictation) } },
                            enabled = connected && !sending,
                            colors = buttonColors(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        ) {
                            Icon(Icons.Filled.Mic, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.phone_dictate))
                        }
                    }
                    draft?.let { text ->
                        Text("« $text »", color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { sent(PhoneLink.reply(item.key, text)) },
                                enabled = connected && !sending,
                                colors = buttonColors()
                            ) { Text(stringResource(R.string.phone_send)) }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { draft = null }) { Text(stringResource(R.string.dash_cancel), color = DashColors.Muted) }
                        }
                    }
                }
                if (sending) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = DashColors.Accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.phone_sending), color = DashColors.TextSecondary)
                    }
                }
                problem?.let { Text(it, color = DashColors.Warning, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                CarVoice.setContext(context)
                CarVoice.speak(spoken, locale)
            }) {
                Icon(Icons.Filled.VolumeUp, contentDescription = null, tint = DashColors.Accent)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.phone_read_aloud), color = DashColors.AccentInk)
            }
        },
        dismissButton = {
            Row {
                if (item.canMarkRead) {
                    TextButton(onClick = { sent(PhoneLink.markRead(item.key)) }, enabled = connected && !sending) {
                        Text(stringResource(R.string.phone_mark_read), color = DashColors.TextSecondary)
                    }
                }
                TextButton(onClick = { sent(PhoneLink.dismiss(item.key)) }, enabled = connected && !sending) {
                    Text(stringResource(R.string.phone_clear), color = DashColors.TextSecondary)
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_close), color = DashColors.Muted) }
            }
        }
    )
}

/** Settings → Phone: the link's state, the paired phones, and pairing a new one. */
@Composable
internal fun PhonePane() {
    val context = LocalContext.current
    val state by PhoneLink.state.collectAsState()
    val phones by PhoneLink.phones.collectAsState()
    var pairing by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf<PairedPhone?>(null) }

    SettingsSection(stringResource(R.string.settings_section_phone))
    val (title, detail) = when (val s = state) {
        is PhoneLinkState.Connected -> stringResource(R.string.phone_status_connected, s.phoneName) to stringResource(R.string.phone_status_connected_detail)
        PhoneLinkState.Searching -> stringResource(R.string.phone_status_searching) to stringResource(R.string.phone_status_searching_detail)
        PhoneLinkState.Unpaired -> stringResource(R.string.phone_status_unpaired) to stringResource(R.string.phone_status_unpaired_detail)
    }
    // A status, not a setting: on a panel of its own, so it is not taken for a row to tap.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DashShape.Medium)
            .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * 0.5f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (state is PhoneLinkState.Connected) Icons.Filled.CheckCircle else Icons.Filled.PhoneAndroid, contentDescription = null,
            tint = if (state is PhoneLinkState.Connected) DashColors.Good else DashColors.TextSecondary, modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(detail, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            if (state !is PhoneLinkState.Connected) LinkAttemptLine()
        }
    }
    Spacer(Modifier.height(8.dp))

    phones.forEach { phone ->
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.PhoneAndroid, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(phone.name.ifEmpty { stringResource(R.string.phone_unnamed) }, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (phone.forgotten) stringResource(R.string.phone_forgotten)
                    else stringResource(R.string.phone_paired_on, DateFormat.getDateInstance().format(Date(phone.pairedAt))),
                    color = if (phone.forgotten) DashColors.Warning else DashColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            // Removed on the phone already: nothing is lost by forgetting it here.
            TextButton(onClick = { if (phone.forgotten) PhoneLink.forget(context, phone.id) else forgetting = phone }) {
                Text(stringResource(R.string.phone_forget), color = DashColors.AccentInk)
            }
        }
        HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(horizontal = 12.dp))
    }

    SettingsRow(Icons.Filled.AddLink, stringResource(R.string.phone_pair), stringResource(R.string.phone_pair_detail)) { pairing = true }

    // The phone's GPS in place of this unit's ([PhoneGps]).
    if (phones.isNotEmpty()) {
        val useGps by PhoneGps.enabled.collectAsState()
        val gpsInUse = PhoneGps.inUse.collectAsState().value
        val gpsRefused by PhoneGps.refused.collectAsState()
        val gpsDetail = when {
            !useGps -> stringResource(R.string.phone_gps_detail)
            gpsRefused -> stringResource(R.string.phone_gps_refused)
            gpsInUse != null -> stringResource(R.string.phone_gps_in_use, gpsInUse.used)
            state is PhoneLinkState.Connected -> stringResource(R.string.phone_gps_waiting)
            else -> stringResource(R.string.phone_gps_detail)
        }
        SettingsToggle(Icons.Filled.MyLocation, stringResource(R.string.phone_gps), gpsDetail, useGps) { PhoneGps.setEnabled(context, it) }
        // The unit's own notifications next to the phone's, or the phone's only (#123).
        var unitNotifs by remember { mutableStateOf(NotificationFeed.showsUnit(context)) }
        SettingsToggle(Icons.Filled.Notifications, stringResource(R.string.phone_unit_notifs), stringResource(R.string.phone_unit_notifs_detail), unitNotifs) {
            unitNotifs = it
            NotificationFeed.setShowsUnit(context, it)
        }
    }

    // Calls show in their own window over other apps; without that, only over the launcher.
    val scope = rememberCoroutineScope()
    var overlayAllowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            overlayAllowed = Settings.canDrawOverlays(context)
            delay(2_000)
        }
    }
    if (phones.isNotEmpty() && !overlayAllowed) {
        SettingsRow(Icons.Filled.Layers, stringResource(R.string.phone_overlay), stringResource(R.string.phone_overlay_detail)) {
            scope.launch {
                overlayAllowed = PipAnchor.grantOverlayPermission(context)
                if (!overlayAllowed) {
                    context.launchSafely(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                }
            }
        }
    }

    if (pairing) PhonePairingDialog(onDismiss = { pairing = false })
    forgetting?.let { phone ->
        ConfirmDialog(
            title = stringResource(R.string.phone_forget_title, phone.name.ifEmpty { stringResource(R.string.phone_unnamed) }),
            body = stringResource(R.string.phone_forget_body),
            action = stringResource(R.string.phone_forget),
            onConfirm = { PhoneLink.forget(context, phone.id); forgetting = null },
            onDismiss = { forgetting = null }
        )
    }
}

/** The companion's Play edition package (companion/build.gradle.kts): its Google Play listing. */
private const val COMPANION_PACKAGE = "com.dashwheel.companion"

/**
 * The pairing code, with the companion app's download next to it. Closes on
 * its own once the phone has used the code.
 */
@Composable
internal fun PhonePairingDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Begun once the dialog is on screen, not while it is composed: a composition thrown away
    // before it applied would leave a code pending that nothing cancels.
    var offer by remember { mutableStateOf<PairingOffer?>(null) }
    DisposableEffect(Unit) {
        val begun = PhoneLink.beginPairing(context)
        offer = begun
        onDispose { if (PhoneLink.pending.value?.id == begun.id) PhoneLink.cancelPairing() }
    }
    val phones by PhoneLink.phones.collectAsState()
    val current = offer ?: return
    val paired = phones.firstOrNull { it.id == current.id }
    // Where the phone gets the companion app: the GitHub release's APK, or
    // the Play edition's companion on Google Play (same package name, see
    // companion/build.gradle.kts).
    val download = remember {
        if (Edition.play) PLAY_LISTING + COMPANION_PACKAGE
        else "https://github.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest/download/${UpdateManager.COMPANION_APK_NAME}"
    }

    AlertDialog(
        modifier = Modifier.keepClearOfWindows(),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { TitleWithHelp(stringResource(R.string.phone_pair_title), WikiPage.PHONE_COMPANION) },
        text = {
            if (paired != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = DashColors.Good, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.phone_pair_done, paired.name.ifEmpty { stringResource(R.string.phone_unnamed) }),
                        color = DashColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    // The download code is only for a first install: smaller, and labelled so it
                    // isn't taken for the pairing code (the companion app says so if it is).
                    Column(Modifier.weight(0.8f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.phone_pair_step1), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        QrCode(download, Modifier.fillMaxWidth())
                        QrCaption(stringResource(R.string.phone_pair_qr_download), primary = false)
                    }
                    Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.phone_pair_step2), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.phone_pair_step3), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        QrCode(current.toUri(), Modifier.fillMaxWidth())
                        QrCaption(stringResource(R.string.phone_pair_qr_pair), primary = true)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = DashColors.Accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.phone_pair_waiting), color = DashColors.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        LinkAttemptLine()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(if (paired != null) R.string.phone_done else R.string.dash_cancel), color = DashColors.AccentInk)
            }
        }
    )
}

@Composable
private fun QrCaption(text: String, primary: Boolean) {
    Text(
        text,
        color = if (primary) DashColors.Accent else DashColors.Muted,
        fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center
    )
}

/** A QR code, dark on white with its quiet zone, as wide as it is given. */
@Composable
internal fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
    }
    Canvas(modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Color.White)) {
        val cells = matrix.width
        val cell = size.minDimension / cells
        for (y in 0 until cells) {
            for (x in 0 until cells) {
                // A hair over one cell so neighbouring squares leave no seams.
                if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/** The link's last try (see [PhoneLink.lastAttempt]), small, for when it does not come up. */
@Composable
private fun LinkAttemptLine() {
    val line = PhoneLink.lastAttempt.collectAsState().value ?: return
    Text(
        line,
        color = DashColors.Muted,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
        maxLines = 2
    )
}
