package com.openauto.dash.unit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.openauto.dash.Cap
import com.openauto.dash.DockShell
import com.openauto.dash.IgnitionEvent
import com.openauto.dash.IgnitionSource
import com.openauto.dash.KeySource
import com.openauto.dash.LauncherTakeover
import com.openauto.dash.Need
import com.openauto.dash.PrivilegedShell
import com.openauto.dash.R
import com.openauto.dash.ReglinkWhitelist
import com.openauto.dash.SignalCatalogue
import com.openauto.dash.SleepSurvival
import com.openauto.dash.SurvivalStep
import com.openauto.dash.SysProps
import com.openauto.dash.UnitAdapter
import com.openauto.dash.UnitCaps
import com.openauto.dash.UnitKeyPress
import com.openauto.dash.UnitPlatform
import com.openauto.dash.isEmulator
import com.openauto.unitproto.AccBroadcasts
import com.openauto.unitproto.KeyBroadcasts
import com.openauto.unitproto.UnitFamily
import com.openauto.unitproto.fyt.FytProtocol
import com.openauto.unitproto.hcn.HcnProtocol
import com.openauto.unitproto.mtc.MtcProtocol
import com.openauto.unitproto.nwd.NwdProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * The families Dashwheel knows only a little about, each a [GenericAdapter]
 * with its few hooks: Topway (the vendor broadcasts, its MCU link: [TopwayBus]), Allwinner (detection and the vendor
 * broadcasts), Reglink (how to live through its sleep), NWD (its launcher
 * choice, its voltage and headlights: [NwdBus]). And the plain unit nothing was recognised on, which still gets
 * the broadcasts: they cost nothing and some generic unit may send them.
 */
internal object PlainAdapters {
    /** The Topway TS firmware's own apps (TS10, TS18, TS7/TS8). */
    val TOPWAY_PACKAGES = setOf("com.tw.car", "com.tw.carchoose", "com.tw.carapps", "com.tw.ac2", "com.tv.carinfoservice", "com.tw.carinfoservice", "com.zjinnova.zlink")
    /** Reglink's own: its services and launcher, and the 360 camera app its units ship. */
    val REGLINK_PACKAGES = setOf("com.reglink.services", "com.reglink.launcher4", "com.baony.avm360")

    /** Every vendor package any adapter talks to: declared in the manifest's queries (QueriesCoverageTest). */
    val ALL_VENDOR_PACKAGES: Set<String>
        get() = TOPWAY_PACKAGES + REGLINK_PACKAGES + HcnProtocol.PACKAGES + FytProtocol.PACKAGES + MtcProtocol.PACKAGES +
            setOf(QfAdapter.VEHICLE_PACKAGE, QfAdapter.BT_PACKAGE, QfAdapter.RADIO_PACKAGE, "com.qf.backcar", "com.qf.framework", "com.syt.tmps")

    /** Every family's broadcasts: what the Signal Finder's sniffer hears whatever the unit was taken for. */
    val ALL_BROADCASTS: Set<String>
        get() = AccBroadcasts.ALL + KeyBroadcasts.ACTIONS + MtcProtocol.BROADCASTS +
            setOf(FytProtocol.ACTION_ACC_ON, FytProtocol.ACTION_ACC_OFF, FytProtocol.ACTION_EXTERNAL_COMMAND) + QfAdapter.BROADCASTS + HcnProtocol.BROADCASTS + NwdProtocol.BROADCASTS

    private val plainCatalogue = SignalCatalogue(broadcasts = AccBroadcasts.ALL + KeyBroadcasts.ACTIONS)

    fun forFamily(context: Context, family: UnitFamily): UnitAdapter {
        val app = context.applicationContext
        val emulator = runCatching { isEmulator }.getOrDefault(false)
        return when (family) {
            UnitFamily.REGLINK -> GenericAdapter(
                family, emulator, AccBroadcastIgnition, KeyBroadcastSource, survival = ReglinkSurvival(app),
                catalogue = plainCatalogue.copy(propPrefixes = setOf("persist.duraspeed.", "ro.mtk_bg", "sys.ipo")),
                vendorPackages = REGLINK_PACKAGES
            )
            UnitFamily.NWD -> GenericAdapter(
                family, emulator, AccBroadcastIgnition, KeyBroadcastSource, launcher = NwdLauncher,
                bus = NwdBus, busCaps = setOf(Cap.VOLTS, Cap.HEADLIGHTS, Cap.OUTSIDE_TEMP),
                // The MCU's input bits (headlights) and the CAN SDK's own table (CanSettingTableKey) live in Settings.System.
                catalogue = plainCatalogue.copy(broadcasts = plainCatalogue.broadcasts + NwdProtocol.BROADCASTS, propPrefixes = setOf("persist.nwd.", "ro.nwd."), settingsSystem = true)
            )
            UnitFamily.TOPWAY -> GenericAdapter(
                family, emulator, AccBroadcastIgnition, KeyBroadcastSource,
                // The MCU's reverse and the canbox's frames through the framework's TWUtil ([TopwayBus]).
                bus = TopwayBus, busCaps = setOf(Cap.REVERSE),
                catalogue = plainCatalogue, vendorPackages = TOPWAY_PACKAGES
            )
            else -> GenericAdapter(family, emulator, AccBroadcastIgnition, KeyBroadcastSource, catalogue = plainCatalogue)
        }
    }
}

/** The ignition from the broadcasts the smaller firmwares send; nothing to read in between. */
internal object AccBroadcastIgnition : IgnitionSource {
    override fun read(): Boolean? = null
    override val actions: Set<String> get() = AccBroadcasts.ALL
    override fun decode(intent: Intent): IgnitionEvent? = when (AccBroadcasts.decode(intent.action)) {
        true -> IgnitionEvent.ON
        false -> IgnitionEvent.OFF
        null -> null
    }
}

/** Wheel keys told as vendor broadcasts (Nowadays, Winca, C3, BYD, Eryanet, BZ...), each in its own code space. */
internal object KeyBroadcastSource : KeySource {
    private const val TAG = "KeyBroadcasts"
    /** The code space is the broadcast's, not one firmware's: the learned key remembers which. */
    override val vendor = "bc"

    private val _listening = MutableStateFlow(false)
    override val listening: StateFlow<Boolean> = _listening
    private var onKey: ((UnitKeyPress) -> Unit)? = null
    private var app: Context? = null

    override fun start(context: Context, onKey: (UnitKeyPress) -> Unit) {
        if (this.onKey != null) return
        this.onKey = onKey
        val filter = IntentFilter().apply { KeyBroadcasts.ACTIONS.forEach(::addAction) }
        app = context.applicationContext.also { ContextCompat.registerReceiver(it, receiver, filter, ContextCompat.RECEIVER_EXPORTED) }
        _listening.value = true
    }

    override fun stop() {
        onKey = null
        // Left registered, the next start would register a second one and hear each key twice.
        app?.let { runCatching { it.unregisterReceiver(receiver) } }
        app = null
        _listening.value = false
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val extras = intent.extras?.let { b -> b.keySet().associateWith { @Suppress("DEPRECATION") b.get(it) } }.orEmpty()
            val key = KeyBroadcasts.decode(intent.action, extras) ?: return
            if (key.down) UnitPlatform.markUsed(Cap.UNIT_KEYS)
            Log.d(TAG, "${key.vendor} key ${key.code} ${if (key.down) "down" else "up"}")
            // One code space per sender: the code carries the sender's name in its high bits' stead.
            onKey?.invoke(UnitKeyPress(codeOf(key.vendor, key.code), key.down))
        }
    }

    /** The senders' codes kept apart: thousands per sender, as open-headunit numbers them. */
    private fun codeOf(sender: String, code: Int): Int = when (sender) {
        "nwd" -> 1000 + code
        "eryanet" -> 2000 + code
        "bz" -> 3000 + code
        "byd" -> 4000 + code
        "c3" -> 5000 + code
        "ikey" -> 6000 + code
        else -> code
    }

    override fun nameRes(code: Int): Int? = when (code) {
        2000 + KeyBroadcasts.CODE_PREV -> R.string.wheel_unit_previous
        2000 + KeyBroadcasts.CODE_NEXT -> R.string.wheel_unit_next
        2000 + KeyBroadcasts.CODE_PLAY_PAUSE, 2000 + KeyBroadcasts.CODE_PLAY, 2000 + KeyBroadcasts.CODE_PAUSE -> R.string.wheel_unit_play_pause
        3000 + KeyBroadcasts.CODE_PICKUP -> R.string.wheel_unit_phone
        3000 + KeyBroadcasts.CODE_HANGUP -> R.string.wheel_unit_hang_up
        else -> null
    }
}

/**
 * Living through a Reglink unit's sleep: at ACC off its firmware force-stops
 * every third-party app it has not whitelisted, after a short countdown.
 * Three ways out, from the cheapest: DuraSpeed (MediaTek's background
 * killer) turned off, which a reboot undoes and Dashwheel redoes at each boot
 * once it may write secure settings; Quick Boot turned off in the factory
 * settings (the driver's doing); the three whitelist files on /system, with root.
 */
internal class ReglinkSurvival(private val app: Context) : SleepSurvival {
    override fun steps(): List<SurvivalStep> = listOf(
        SurvivalStep(R.string.unit_reglink_duraspeed_title, R.string.unit_reglink_duraspeed_detail, Need.SETTINGS, automatic = true),
        SurvivalStep(R.string.unit_reglink_quickboot_title, R.string.unit_reglink_quickboot_detail, Need.NONE, automatic = false),
        SurvivalStep(R.string.unit_reglink_whitelist_title, R.string.unit_reglink_whitelist_detail, Need.ROOT, automatic = true)
    )

    override suspend fun apply(context: Context, access: PrivilegedShell.Access): Boolean {
        var all = true
        if (UnitCaps.needMet(Need.SETTINGS, access, PrivilegedShell.settingsGranted)) all = duraSpeedOff(context) && all
        if (access.root) all = ReglinkWhitelist.apply(context) && all
        return all
    }

    override fun atBoot(context: Context) {
        // DuraSpeed comes back on at every boot; nothing else of this needs redoing.
        if (!UnitCaps.needMet(Need.SETTINGS, PrivilegedShell.access.value, PrivilegedShell.settingsGranted)) return
        kotlinx.coroutines.runBlocking { duraSpeedOff(context) }
    }

    /** DuraSpeed off in both the global and the system table, the way the guide found it reads them. */
    private suspend fun duraSpeedOff(context: Context): Boolean = runCatching {
        if (SysProps.get(DURASPEED_PROP).isNullOrBlank() && SysProps.get("ro.mtk_bg_power_saving_support").isNullOrBlank()) return true
        val direct = PrivilegedShell.settingsGranted
        for (table in listOf("global", "system")) {
            if (direct) {
                if (table == "global") Settings.Global.putString(context.contentResolver, DURASPEED_KEY, "0")
                else Settings.System.putString(context.contentResolver, DURASPEED_KEY, "0")
            } else {
                DockShell.shell(context, "settings put $table $DURASPEED_KEY 0")
            }
        }
        Log.i(TAG, "DuraSpeed turned off")
        true
    }.onFailure { Log.w(TAG, "could not turn DuraSpeed off", it) }.getOrDefault(false)

    companion object {
        private const val TAG = "ReglinkSurvival"
        const val DURASPEED_PROP = "persist.duraspeed.support"
        const val DURASPEED_KEY = "setting.duraspeed.enabled"
    }
}

/** NWD units start the package named in a property as their Home, like the QF firmware; it is written through a shell. */
internal object NwdLauncher : LauncherTakeover {
    const val PROP = "persist.nwd.launcher.default"
    override val need: Need get() = Need.SHELL
    override fun ownChoice(): String? = SysProps.getOrNull(PROP)
    override suspend fun claim(context: Context): Boolean =
        runCatching { DockShell.shell(context, "setprop $PROP ${context.packageName}") }.isSuccess
}
