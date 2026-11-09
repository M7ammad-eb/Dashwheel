package com.openauto.dash.companion

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Home's way into Road watch while the car is linked: the camera one tap
 * away, and whether the vehicle finder is there; the rest is on its tab.
 */
@Composable
internal fun RoadWatchStartCard() {
    val context = LocalContext.current
    val model by RoadModel.state.collectAsState()
    LaunchedEffect(Unit) { RoadModel.check(context) }
    val ready = model == RoadModel.State.Ready
    Panel {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardHeading(Icons.Filled.Traffic, stringResource(R.string.road_title), stringResource(R.string.road_home_detail), CompanionColors.Teal)
            ExperimentalMark()
            Button(onClick = { RoadWatchActivity.open(context) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Filled.Videocam, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.road_start))
            }
            Text(
                stringResource(if (ready) R.string.road_finder_ready else R.string.road_model_none),
                style = MaterialTheme.typography.bodySmall, color = if (ready) CompanionColors.Teal else CompanionColors.Muted
            )
        }
    }
}

/**
 * Road watch on its tab: what it does, the vehicle finder to fetch once
 * ([RoadModel]), the road recorder's switch ([RoadClips]), and the button
 * that opens the camera ([RoadWatchActivity]). Both are experimental, and
 * say so.
 */
@Composable
internal fun RoadWatchCard(connected: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model by RoadModel.state.collectAsState()
    val recording by RoadClips.enabled.collectAsState()
    val fetching by ClipServer.on.collectAsState()
    LaunchedEffect(Unit) {
        RoadModel.check(context)
        // Reads every clip's notes: not on the main thread.
        withContext(Dispatchers.IO) { RoadClips.load(context) }
        RoadModel.upgrade(context)
        ClipServer.load(context)
    }
    Panel {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHeading(
                Icons.Filled.Traffic, stringResource(R.string.road_title), stringResource(R.string.road_detail),
                if (connected) CompanionColors.Teal else CompanionColors.Blue
            )
            ExperimentalMark()
            when (val m = model) {
                RoadModel.State.Ready -> Unit
                is RoadModel.State.Getting -> LinearProgressIndicator(progress = { m.done }, modifier = Modifier.fillMaxWidth())
                RoadModel.State.Failed -> Text(stringResource(R.string.road_model_failed), style = MaterialTheme.typography.bodySmall, color = CompanionColors.Amber)
                RoadModel.State.Missing -> Text(stringResource(R.string.road_model_none), style = MaterialTheme.typography.bodySmall, color = CompanionColors.Muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { RoadWatchActivity.open(context) }) {
                    Icon(Icons.Filled.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.road_start))
                }
                if (model == RoadModel.State.Missing || model == RoadModel.State.Failed) {
                    OutlinedButton(onClick = { scope.launch { RoadModel.get(context) } }) {
                        Text(stringResource(if (model == RoadModel.State.Failed) R.string.road_model_retry else R.string.road_model_get))
                    }
                }
            }
            Text(stringResource(R.string.road_note), style = MaterialTheme.typography.bodySmall, color = CompanionColors.Muted)

            // The road recorder: its switch, and what the driver should know before turning it on.
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.rec_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.rec_detail), style = MaterialTheme.typography.bodySmall, color = CompanionColors.Muted)
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = recording, onCheckedChange = { RoadClips.setEnabled(context, it) })
            }
            Text(stringResource(R.string.rec_legal), style = MaterialTheme.typography.bodySmall, color = CompanionColors.Amber)

            // The driver's own computer fetching the clips over Tailscale ([ClipServer]).
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.rec_fetch_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.rec_fetch_detail), style = MaterialTheme.typography.bodySmall, color = CompanionColors.Muted)
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = fetching, onCheckedChange = { ClipServer.setOn(context, it) })
            }
            if (fetching) {
                val address = ClipServer.address()
                if (address == null) {
                    Text(stringResource(R.string.rec_fetch_no_tailscale), style = MaterialTheme.typography.bodySmall, color = CompanionColors.Amber)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(address, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        // Handed on as text, to reach the computer by mail, a note or Quick Share.
                        IconButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, address) }
                            runCatching { context.startActivity(Intent.createChooser(send, null)) }
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.rec_share), tint = CompanionColors.Blue)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Road watch is still a trial: it says "Experimental", and the screen doesn't
 * dim by itself, so the driver sees what it sees. Off once it has proved itself.
 */
internal const val ROAD_WATCH_EXPERIMENTAL = true

/** "Experimental", so nobody takes it for a safety system. */
@Composable
internal fun ExperimentalMark() {
    if (!ROAD_WATCH_EXPERIMENTAL) return
    Text(
        stringResource(R.string.road_experimental),
        color = CompanionColors.Amber, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(CompanionColors.Amber.copy(alpha = 0.14f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/** The clips the road recorder kept: when, why, how long; share, its notes, delete. */
@Composable
internal fun ClipsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clips by RoadClips.clips.collectAsState()
    val recording by RoadClips.enabled.collectAsState()
    if (clips.isEmpty() && !recording) return
    Panel {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.rec_clips_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (clips.isEmpty()) {
                Text(stringResource(R.string.rec_no_clips), style = MaterialTheme.typography.bodySmall, color = CompanionColors.Muted)
            }
            clips.take(SHOWN).forEach { clip ->
                // A tap plays the clip; Share hands the video and its notes over together.
                Row(Modifier.fillMaxWidth().clickable { play(context, clip.video) }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            DateUtils.formatDateTime(context, clip.at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH),
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResource(triggerLabel(clip.trigger)) + " · " + stringResource(R.string.rec_length, (clip.durationMs / 1000).toInt(), (clip.bytes / (1024 * 1024)).toInt()),
                            style = MaterialTheme.typography.bodySmall, color = CompanionColors.Muted
                        )
                    }
                    IconButton(onClick = { share(context, listOf(clip.video, clip.notes)) }) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.rec_share), tint = CompanionColors.Blue)
                    }
                    TextButton(onClick = { scope.launch(Dispatchers.IO) { RoadClips.delete(context, clip) } }) { Text(stringResource(R.string.rec_delete), color = CompanionColors.Red) }
                }
            }
        }
    }
}

private const val SHOWN = 10

private fun triggerLabel(trigger: String): Int = when (trigger) {
    "impact" -> R.string.rec_trigger_impact
    "brake" -> R.string.rec_trigger_brake
    "collision" -> R.string.rec_trigger_collision
    "hazard" -> R.string.rec_trigger_hazard
    else -> R.string.rec_trigger_button
}

private fun uriOf(context: android.content.Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.logs", file)

/** The clip and its notes in one go: what an insurer or a garage wants together. */
private fun share(context: android.content.Context, files: List<File>) {
    val uris = ArrayList(files.filter { it.exists() }.map { uriOf(context, it) })
    if (uris.isEmpty()) return
    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(send, null)) }
}

private fun play(context: android.content.Context, video: File) {
    if (!video.exists()) return
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uriOf(context, video), "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(view) }
}
