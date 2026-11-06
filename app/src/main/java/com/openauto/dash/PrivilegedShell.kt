package com.openauto.dash

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.thread

/**
 * Whether the app has a privileged shell to work with: root through `su`
 * (Magisk), the Shizuku app once the driver allowed Dashwheel in it
 * ([ShizukuShell]), or the head unit's internal ADB socket ([AdbInstaller]).
 * Found out once at start ([probe]) and again when the launcher comes back to
 * the front while nothing was found ([refresh]), so root granted in Magisk
 * meanwhile is picked up; Shizuku coming or going asks for a new look itself
 * ([recheck]).
 *
 * The features built on one of them (the system-app install, the boot logo,
 * app windows and the Maps window, the volume keys, the CANbox stream) stay
 * out of the settings, the widget catalogue and the templates while neither
 * is there: a phone or an unrooted unit never shows a control that can only
 * fail. Until the probe has answered, nothing is offered either.
 *
 * The Play edition ([Edition.play]) has neither and never looks: the probe
 * answers [Access.NONE] without running `su` or opening a socket.
 */
object PrivilegedShell {

    /** [Edition.play], as a field so a test can try the Play edition's answer on a GitHub build. */
    @Volatile internal var editionPlay: Boolean = Edition.play

    enum class Access {
        /** Not probed yet. */
        UNKNOWN,
        /** Neither `su`, nor Shizuku, nor a listening ADB socket. */
        NONE,
        /** Shizuku runs and Dashwheel is allowed in it: the shell user, or root where Shizuku was started by root. */
        SHIZUKU,
        /** The internal ADB socket answers (the shell user; `adb root` raises it on these units). */
        ADB,
        /** `su` is granted. */
        ROOT;

        /** A shell of some kind, root, Shizuku or ADB: what [DockShell] runs its window and settings commands through. */
        val shell: Boolean get() = this == ROOT || this == SHIZUKU || this == ADB

        /**
         * A shell that writes the unit itself (/system, the logo partition):
         * root, or the unit's internal ADB, which these units run as root. The
         * installers and the boot logo run through one of those two; Shizuku's
         * shell user cannot write there.
         */
        val system: Boolean get() = this == ROOT || this == ADB

        /** `su` itself: the CANbox stream ([McuReader]) and the wheel sniffers ([WheelMonitor]) only run through it. */
        val root: Boolean get() = this == ROOT

        /**
         * The car app's switches in the global settings can be written
         * ([RomPopups.writeGlobals]): through a shell, or by Dashwheel itself
         * once it holds WRITE_SECURE_SETTINGS ([settingsGranted]).
         */
        val carSettings: Boolean get() = shell || settingsGranted

        /**
         * Whether [kind] can show anything here: the CANbox monitor needs root, the Maps window a shell.
         * [learnedDoors]: the driver taught a door in Car signals, which needs neither.
         */
        fun allows(kind: BuiltinKind, learnedDoors: Boolean = false): Boolean = when (kind) {
            BuiltinKind.CAN_MON -> root
            BuiltinKind.DOORS -> carSettings || learnedDoors
            // The car box's data is shared once Dashwheel is registered with the car app (CarBox).
            BuiltinKind.CAR_STATUS -> carSettings
            BuiltinKind.PIP_ANCHOR -> shell
            else -> true
        }
    }

    @Volatile private var appContext: Context? = null

    fun setContext(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Dashwheel holds WRITE_SECURE_SETTINGS: granted once from a PC
     * (`adb shell pm grant`), or by the system to its privileged copy. Read
     * each time, so a grant made while it runs counts at once.
     */
    val settingsGranted: Boolean
        get() = !editionPlay &&
            appContext?.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private const val TAG = "PrivilegedShell"

    /** A probe that found nothing is not run again sooner than this. */
    private const val REFRESH_MIN_MS = 15_000L

    private val _access = MutableStateFlow(Access.UNKNOWN)
    val access: StateFlow<Access> = _access.asStateFlow()

    private val probing = AtomicBoolean(false)
    @Volatile private var probedAt = 0L
    @Volatile private var probedOnce = false

    /** The first probe of the process; later calls do nothing. */
    fun probe() {
        if (probedOnce) return
        probedOnce = true
        run()
    }

    /**
     * Probes again while nothing was found: root granted since, or ADB turned
     * on in the developer options. Cheap where there is no `su` at all.
     */
    fun refresh() {
        if (!probedOnce) return probe()
        ShizukuShell.refresh()
        if (_access.value.shell) return
        if (SystemClock.elapsedRealtime() - probedAt < REFRESH_MIN_MS) return
        run()
    }

    /**
     * Probes again whatever was found: Shizuku's binder came or went, or the
     * driver allowed Dashwheel in it ([ShizukuShell]). The answer may go down
     * as well as up (Shizuku stopped, nothing else there).
     */
    fun recheck() {
        if (!probedOnce) return probe()
        run()
    }

    private fun run() {
        if (!probing.compareAndSet(false, true)) return
        thread(isDaemon = true, name = "shell-probe") {
            try {
                val found = find()
                probedAt = SystemClock.elapsedRealtime()
                if (found != _access.value) Log.i(TAG, "privileged shell: $found")
                _access.value = found
            } finally {
                probing.set(false)
            }
        }
    }

    /**
     * Root first (it is what the most features need), then Shizuku (already
     * allowed by the driver, no prompt), else the ADB socket. Only a listening
     * socket is looked for, not a connection: the connection's authorisation
     * prompt is left to the first feature that uses it. An emulator counts as
     * having the socket, so what needs a shell can be tried there without root
     * (`adb tcpip 5555` makes it answer). The Play edition is answered first,
     * before anything is run.
     */
    internal fun find(): Access = when {
        editionPlay -> Access.NONE
        SystemInstaller.isRootAvailable() -> Access.ROOT
        ShizukuShell.ready -> Access.SHIZUKU
        AdbInstaller.listeningPort() != null -> Access.ADB
        isEmulator -> Access.ADB
        else -> Access.NONE
    }
}

/**
 * Where Shizuku stands on this unit, for its settings row ([ShizukuShell.state]).
 * Shared by both editions so the row's code compiles in each; the Play edition
 * never leaves [NOT_INSTALLED] and never shows the row.
 */
enum class ShizukuState {
    /** The Shizuku app is not on the unit. */
    NOT_INSTALLED,
    /** Installed, but its service is not running (not started since boot). */
    NOT_RUNNING,
    /** Running, but older than the API Dashwheel speaks (v11). */
    OLD_VERSION,
    /** Running; the driver has not allowed Dashwheel in it yet. */
    NOT_ALLOWED,
    /** Running and allowed: the shell runs through it. */
    READY
}

/** The shell as the composition sees it; recomposes when the probe answers. */
@Composable
internal fun shellAccess(): PrivilegedShell.Access {
    val access by PrivilegedShell.access.collectAsState()
    return access
}
