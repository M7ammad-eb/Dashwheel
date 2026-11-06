package com.openauto.dash

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * Whether [item] can work with this shell: the CANbox tiles need root, the
 * car status and the Maps window a shell ([PrivilegedShell.Access.allows]),
 * and an app in a window of its own or inside its tile is placed through
 * the shell too. Every other tile works anywhere.
 */
internal fun PrivilegedShell.Access.allows(item: DashboardItem, learnedDoors: Boolean = false): Boolean = when (item) {
    is DashboardItem.BuiltinWidget -> allows(item.kind, learnedDoors)
    is DashboardItem.AppWindow -> shell
    else -> true
}

/**
 * Tiles placed while Dashwheel had root or the unit's ADB can only sit
 * waiting once the probe finds neither (Magisk removed, root refused, a
 * layout copied from another unit). Once the probe has answered, a word
 * names them and they are removed from every page, one Undo step for all.
 * Cancel keeps them until the next start: su can run out of time while the
 * unit boots, and a later probe may find it after all. Nothing shows while
 * the car moves.
 */
@Composable
internal fun RootlessTiles(
    pages: List<List<DashboardItem>>,
    access: PrivilegedShell.Access,
    onRemove: (List<List<DashboardItem>>) -> Unit
) {
    var kept by rememberSaveable { mutableStateOf(false) }
    if (kept || access == PrivilegedShell.Access.UNKNOWN || LocalDriveLock.current.moving) return
    val learnedDoors = LearnedSignals.mapped.collectAsState().value.keys.any { it.isDoor }
    val stranded = pages.flatten().filterNot { access.allows(it, learnedDoors) }
    if (stranded.isEmpty()) return
    val context = LocalContext.current
    val names = stranded.map { tile ->
        when (tile) {
            is DashboardItem.BuiltinWidget -> context.getString(tile.kind.labelRes)
            is DashboardItem.AppWindow -> runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(tile.packageName, 0)).toString()
            }.getOrDefault(tile.packageName)
            else -> ""
        }
    }.distinct()
    ConfirmDialog(
        title = stringResource(R.string.dash_rootless_title),
        // Two Doors tiles read as one name, so the sentence counts names, not tiles.
        body = pluralStringResource(R.plurals.dash_rootless_body, names.size, names.joinToString(", ")),
        action = stringResource(R.string.dash_tile_remove),
        onConfirm = { onRemove(pages.map { page -> page.filter { access.allows(it, learnedDoors) } }) },
        onDismiss = { kept = true }
    )
}
