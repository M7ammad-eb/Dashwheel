package com.openauto.dash

import android.content.Context
import android.util.Log
import kotlin.concurrent.thread

/**
 * Self-installs the app into `/system/priv-app` on a **rooted** device, so it
 * runs as a privileged system app (the same mechanism OEM/aftermarket car
 * launchers use to gain system privileges). Uses `su`; the user grants root
 * via Magisk. Falls back to the head unit's internal root ADB socket
 * ([AdbInstaller]). GitHub edition only: the Play edition's [PrivApp]
 * refuses everything.
 *
 * Does NOT reboot automatically unless asked ([reboot]). Writing to `/system`
 * can fail (or, with enforcing dm-verity, risk a boot loop), so this is a
 * power-user action guarded by a confirmation in the UI.
 */
object PrivApp {

    /**
     * What [SystemInstaller.isRootAvailable] runs: `id`, and Magisk's per-app
     * toast switch turned off for this app, as Magisk's own switch under
     * Superuser does (a launcher on root all day showed its "granted
     * Superuser rights" toast over and over); nothing where there is no Magisk.
     */
    val rootProbe: String =
        "id && { magisk --sqlite \"UPDATE policies SET notification=0 WHERE uid=${android.os.Process.myUid()}\" >/dev/null 2>&1; true; }"


    /** Copying the APK and syncing /data or /system is slow on this unit, but must not hang forever. */
    private const val INSTALL_TIMEOUT_S = 120L

    /** Name of the privileged-permission whitelist, the same for every install path. */
    internal const val PRIVAPP_XML_NAME = "privapp-permissions-openautodash.xml"

    /**
     * Every privileged permission the app asks for (the manifest's
     * "signature|privileged" ones). A ROM that enforces the whitelist refuses
     * to boot with a priv-app missing one, so a permission added to the
     * manifest is added here too. One line, no single quotes: it is echoed
     * inside single quotes by the install scripts.
     */
    internal const val PRIVAPP_XML =
        "<permissions><privapp-permissions package=\"com.openauto.dash\">" +
            "<permission name=\"android.permission.BIND_APPWIDGET\"/>" +
            "<permission name=\"android.permission.WRITE_SECURE_SETTINGS\"/>" +
            "<permission name=\"android.permission.READ_LOGS\"/>" +
            "</privapp-permissions></permissions>"

    /**
     * Installs the app as a privileged system app using `su`.
     *
     * On a Magisk device it creates a **systemless Magisk module** that provides
     * the APK at `/system/priv-app/OpenAutoDash` (mounted from `/data`, so it does
     * NOT need free space on the full `/system` partition) plus a
     * privapp-permissions whitelist so the priv-app boots cleanly. Without Magisk
     * it falls back to writing `/system` directly (fails if `/system` is full).
     */
    fun installAsSystemApp(context: Context): Result<Unit> = runCatching {
        check(Edition.full) { SystemInstaller.NO_ROOT_PLAY }
        val apk = context.applicationInfo.sourceDir
        val mod = "/data/adb/modules/openautodash"
        val script = """
            if [ -d /data/adb/modules ]; then
              mkdir -p $mod/system/priv-app/OpenAutoDash $mod/system/etc/permissions || exit 21
              cp '$apk' $mod/system/priv-app/OpenAutoDash/OpenAutoDash.apk || exit 22
              chmod 644 $mod/system/priv-app/OpenAutoDash/OpenAutoDash.apk
              cat > $mod/module.prop <<'P'
            id=openautodash
            name=OpenAuto Dash (priv-app)
            version=v1
            versionCode=1
            author=OpenAutoDash
            description=Installs OpenAuto Dash as a privileged system app (systemless).
            P
              echo '$PRIVAPP_XML' > $mod/system/etc/permissions/$PRIVAPP_XML_NAME || exit 23
              echo OKINSTALL:magisk
            else
              # Every way out puts /system back read-only: two failures left it writable until a reboot.
              bye() { sync; mount -o remount,ro /system 2>/dev/null; mount -o remount,ro / 2>/dev/null; exit "$1"; }
              mount -o remount,rw / 2>/dev/null
              mount -o remount,rw /system 2>/dev/null
              mkdir -p /system/priv-app/OpenAutoDash || bye 31
              # A full /system stops the copy half-way: no cut-off APK is left where the
              # system would find it at the next start, nor /system writable.
              cp '$apk' /system/priv-app/OpenAutoDash/OpenAutoDash.apk || {
                rm -rf /system/priv-app/OpenAutoDash; bye 32
              }
              chmod 644 /system/priv-app/OpenAutoDash/OpenAutoDash.apk
              chcon u:object_r:system_file:s0 /system/priv-app/OpenAutoDash/OpenAutoDash.apk 2>/dev/null
              mkdir -p /system/etc/permissions
              # Fatal only where the ROM enforces the whitelist (see AdbInstaller).
              echo '$PRIVAPP_XML' > /system/etc/permissions/$PRIVAPP_XML_NAME || {
                if [ "$(getprop ro.control_privapp_permissions)" = enforce ]; then rm -rf /system/priv-app/OpenAutoDash; bye 33; fi
              }
              chmod 644 /system/etc/permissions/$PRIVAPP_XML_NAME
              chcon u:object_r:system_file:s0 /system/etc/permissions/$PRIVAPP_XML_NAME 2>/dev/null
              sync
              mount -o remount,ro /system 2>/dev/null
              mount -o remount,ro / 2>/dev/null
              echo OKINSTALL:system
            fi
        """.trimIndent()

        val res = RootShell.su(script, INSTALL_TIMEOUT_S)
        if (!res.out.contains("OKINSTALL")) {
            error((res.err.ifBlank { res.out }).trim().ifBlank { context.getString(R.string.sys_install_su_failed) })
        }
        compileInBackground { cmd -> RootShell.su(cmd, COMPILE_TIMEOUT_MS / 1000L).all }
    }

    /** Reboots the device via root so the system-app install takes effect. */
    fun reboot(): Result<Unit> = runCatching {
        check(Edition.full) { SystemInstaller.NO_ROOT_PLAY }
        Runtime.getRuntime().exec(arrayOf("su", "-c", "svc power reboot || reboot"))
        Unit
    }



    /**
     * Installs the app as a privileged system app, trying `su` first (Magisk:
     * systemless, survives a full /system) and falling back to the head unit's
     * internal root ADB socket ([AdbInstaller]).
     */
    fun install(context: Context): Result<Unit> {
        val viaSu = installAsSystemApp(context)
        if (viaSu.isSuccess) return viaSu
        val viaAdb = AdbInstaller.installViaAdb(context)
        return if (viaAdb.isSuccess) viaAdb else viaAdb.recoverCatching {
            error("su: ${viaSu.exceptionOrNull()?.message}; ADB: ${it.message}")
        }
    }

    /** Reboots via su, falling back to the root ADB socket. */
    fun rebootDevice(context: Context): Result<Unit> =
        reboot().recoverCatching { AdbInstaller.rebootViaAdb(context).getOrThrow() }

    /** A full AOT compile of a Compose app on this CPU takes minutes. */
    internal const val COMPILE_TIMEOUT_MS = 600_000

    /**
     * Compiles the app ahead of time from the baseline profiles it ships, so
     * Compose does not run interpreted on the unit's slow CPU after the
     * install. Runs on a thread of its own through [run] (the shell the install
     * used) and never holds anything up; a failure only costs speed.
     */
    fun compileInBackground(run: (String) -> String) {
        thread(isDaemon = true, name = "dexopt") {
            runCatching { run("cmd package compile -m speed-profile -f $APP_PACKAGE") }
                .onSuccess { Log.i(TAG, "compiled: ${it.trim()}") }
                .onFailure { Log.w(TAG, "compile after install failed", it) }
        }
    }

    private const val APP_PACKAGE = "com.openauto.dash"
    private const val TAG = "PrivApp"
}
