package com.openauto.dash

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Settings, Advanced: save the whole setup to a file and restore it
 * ([SetupBackup]). Through Android's file picker, so a USB stick works; a unit
 * without one uses a fixed file in the app's own folder instead.
 */
@Composable
internal fun SetupBackupRows() {
    val context = LocalContext.current
    // The setup read from a file, waiting for the driver to confirm.
    var pending by remember { mutableStateOf<String?>(null) }
    val saved = stringResource(R.string.settings_backup_saved)
    val failed = stringResource(R.string.settings_backup_failed)

    // Off the main thread: a slow USB stick, or the dozens of files a restore
    // writes, froze the screen for seconds.
    val scope = rememberCoroutineScope()
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(SetupBackup.MIME)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) { SetupBackup.save(context, uri).isSuccess }
            Toast.makeText(context, if (ok) saved else failed, Toast.LENGTH_SHORT).show()
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) { SetupBackup.load(context, uri) }.onSuccess { pending = it }
                .onFailure { Toast.makeText(context, failed, Toast.LENGTH_SHORT).show() }
        }
    }

    SettingsSection(stringResource(R.string.settings_backup_section))
    SettingsRow(Icons.Filled.Save, stringResource(R.string.settings_backup_save), stringResource(R.string.settings_backup_save_detail)) {
        val name = "dashwheel-setup-" + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()) + ".json"
        try {
            create.launch(name)
        } catch (e: ActivityNotFoundException) {
            // No file picker on this unit: the app's own folder.
            val file = SetupBackup.fallbackFile(context)
            val ok = runCatching { file.writeText(SetupBackup.export(context)) }.isSuccess
            Toast.makeText(context, if (ok) context.getString(R.string.settings_backup_saved_to, file.path) else failed, Toast.LENGTH_LONG).show()
        }
    }
    SettingsRow(Icons.Filled.Restore, stringResource(R.string.settings_backup_restore), stringResource(R.string.settings_backup_restore_detail)) {
        try {
            open.launch(arrayOf(SetupBackup.MIME, "text/plain", "application/octet-stream"))
        } catch (e: ActivityNotFoundException) {
            val file = SetupBackup.fallbackFile(context)
            runCatching { file.readText() }.onSuccess { pending = it }
                .onFailure { Toast.makeText(context, failed, Toast.LENGTH_SHORT).show() }
        }
    }
    Spacer(Modifier.height(20.dp))

    pending?.let { text ->
        AlertDialog(
            modifier = Modifier.keepClearOfWindows(),
            onDismissRequest = { pending = null },
            containerColor = DashColors.Card,
            title = { Text(stringResource(R.string.settings_backup_confirm_title), color = DashColors.TextPrimary) },
            text = { Text(stringResource(R.string.settings_backup_confirm_body), color = DashColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    scope.launch {
                        // Seen through even if Settings is closed meanwhile: a setup put back without the restart is half a restore.
                        withContext(NonCancellable) {
                            withContext(Dispatchers.IO) { SetupBackup.restore(context, text) }
                                .onSuccess { SetupBackup.restart(context) }
                                .onFailure {
                                    Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
                                    // Half written: the restart puts every part on what is on disk now.
                                    if (it is SetupBackup.PartlyRestored) SetupBackup.restart(context)
                                }
                        }
                    }
                }) { Text(stringResource(R.string.settings_backup_confirm), color = DashColors.Critical) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text(stringResource(R.string.dash_cancel), color = DashColors.Muted) }
            }
        )
    }
}
