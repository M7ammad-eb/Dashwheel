package com.openauto.dash

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hides the firmware's volume bar: the overlay in the app's assets
 * (tools/volume-overlay), installed under /system/app by a Magisk module of
 * its own, so taking it away again is removing the module. Either way the
 * firmware sees the change at the next start of the head unit only: it builds
 * its window once and keeps it.
 */
internal object VolumeRomHide {
    private const val TAG = "VolumeRomHide"
    const val OVERLAY_PACKAGE = "com.openauto.dash.volumeoverlay"
    const val FIRMWARE_PACKAGE = "com.qf.framework"
    private const val MODULE = "/data/adb/modules/dashwheel_volume"
    private const val MODULE_APK = "$MODULE/system/app/DashwheelVolume/DashwheelVolume.apk"
    private const val ASSET = "volume_overlay.apk"
    private const val TIMEOUT_S = 30L

    private val _active = MutableStateFlow(false)
    /** The overlay is in place now: the firmware's bar is hidden since this start. */
    val active: StateFlow<Boolean> = _active

    fun refresh(context: Context) {
        _active.value = isPackageInstalled(context, OVERLAY_PACKAGE)
    }

    /**
     * Installs ([hide]) or removes the module; true when done. Installing again
     * only copies the overlay when it changed, and takes back a removal asked
     * for before the restart.
     */
    suspend fun apply(context: Context, hide: Boolean): Boolean = withContext(Dispatchers.IO) {
        refresh(context)
        runCatching {
            val script = if (hide) {
                val apk = File(context.cacheDir, ASSET)
                context.assets.open(ASSET).use { input -> apk.outputStream().use { input.copyTo(it) } }
                """
                [ -d /data/adb/modules ] || { echo NO_MAGISK; exit 3; }
                mkdir -p ${MODULE_APK.substringBeforeLast('/')} || exit 4
                cmp -s '${apk.absolutePath}' $MODULE_APK || cp '${apk.absolutePath}' $MODULE_APK || exit 5
                chmod 644 $MODULE_APK
                chcon u:object_r:system_file:s0 $MODULE_APK 2>/dev/null
                cat > $MODULE/module.prop <<'P'
                id=dashwheel_volume
                name=Dashwheel volume bar
                version=v1
                versionCode=1
                author=Dashwheel
                description=Hides the head unit's own volume bar so Dashwheel's shows alone.
                P
                rm -f $MODULE/remove $MODULE/disable
                echo DONE
                """.trimIndent()
            } else {
                "if [ -d $MODULE ]; then touch $MODULE/remove; fi; echo DONE"
            }
            val out = RootShell.su(script, TIMEOUT_S)
            check(out.out.contains("DONE")) { out.all.trim().ifBlank { "exit ${out.exit}" } }
        }.onSuccess { Log.i(TAG, if (hide) "module in place, effective at the next start" else "module removed at the next start") }
            .onFailure { Log.w(TAG, "volume module not ${if (hide) "installed" else "removed"}", it) }
            .isSuccess
    }
}
