package com.openauto.dash

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.openauto.dash.link.ActionResult
import com.openauto.dash.link.AgendaSync
import com.openauto.dash.link.CallCommand
import com.openauto.dash.link.DashShareCode
import com.openauto.dash.link.ParkingTimer
import com.openauto.dash.link.CallState
import com.openauto.dash.link.Destination
import com.openauto.dash.link.DialResult
import com.openauto.dash.link.Dismiss
import com.openauto.dash.link.DisplayPair
import com.openauto.dash.link.EnableTyping
import com.openauto.dash.link.Hello
import com.openauto.dash.link.IdleGuard
import com.openauto.dash.link.LINK_BLUETOOTH_UUID
import com.openauto.dash.link.LINK_PORT
import com.openauto.dash.link.LinkMessage
import com.openauto.dash.link.LinkSession
import com.openauto.dash.link.MarkRead
import com.openauto.dash.link.NotificationPosted
import com.openauto.dash.link.NotificationRemoved
import com.openauto.dash.link.NotificationSync
import com.openauto.dash.link.ObdOffer
import com.openauto.dash.link.ObdRelayState
import com.openauto.dash.link.ObdRx
import com.openauto.dash.link.FuelPrice
import com.openauto.dash.link.NowPlaying
import com.openauto.dash.link.PhoneAbilities
import com.openauto.dash.link.PhoneBattery
import com.openauto.dash.link.PhoneFix
import com.openauto.dash.link.SendText
import com.openauto.dash.link.CarLog
import com.openauto.dash.link.CarLogAck
import com.openauto.dash.link.CarLookPart
import com.openauto.dash.link.CarRegistration
import com.openauto.dash.link.RegistrationReader
import com.openauto.dash.link.RescueCommand
import com.openauto.dash.link.RescueResult
import com.openauto.dash.link.TextSent
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import com.openauto.dash.link.PairingOffer
import com.openauto.dash.link.PairingStorage
import com.openauto.dash.link.PhoneContacts
import com.openauto.dash.link.PhotoQuestion
import com.openauto.dash.link.Ping
import com.openauto.dash.link.Pong
import com.openauto.dash.link.RecentCall
import com.openauto.dash.link.Reply
import com.openauto.dash.link.SecureChannel
import com.openauto.dash.link.ServiceEntries
import com.openauto.dash.link.StoredPairing
import com.openauto.dash.link.TypeText
import com.openauto.dash.link.PhonePointer
import com.openauto.dash.link.UnknownPairingException
import com.openauto.dash.link.RoadScene
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/*
 * The phone link, head unit side. The driver's phone shares its connection
 * over Wi-Fi; the Dashwheel Companion app on it listens on the hotspot's
 * gateway address. This side finds that gateway, dials it, proves it holds
 * the secret from the pairing QR code, then shows the phone's notifications
 * in the Notifications card, sends back replies, shows its calls
 * ([PhoneCallOverlay]) with answer / hang-up buttons, and types the text sent
 * from its keyboard card ([PhoneKeyboard]). It also brings the phone's
 * favourites, last calls and agenda for the Quick dial and Agenda tiles
 * ([PhoneLists]), calls a favourite through the phone, and starts guidance to
 * a place shared on the phone ([Destination]).
 *
 * With no hotspot (the unit has its own SIM), the same link runs over
 * Bluetooth: the phone offers the link as an RFCOMM service, and this side dials
 * the phones the unit is Bluetooth-paired with. The channel, the pairing secret
 * and everything on top are the same; only the carrier differs.
 */

/** A phone the driver paired by scanning the QR code with the companion app. */
data class PairedPhone(
    val id: String,
    val secret: ByteArray,
    /** From the phone's [Hello]; empty until it first connected. */
    val name: String,
    val pairedAt: Long,
    /** The phone said it no longer knows this pairing: it must be paired again. */
    val forgotten: Boolean = false,
    /** Occasions a hotspot refused this pairing since the phone last connected (see [PhoneLink]). */
    val refusals: Int = 0,
    val lastRefusalAt: Long = 0
)

/** The phone's call as the head unit shows it. */
data class PhoneCall(
    val phase: CallState.Phase,
    val number: String?,
    val name: String?,
    val photo: Bitmap?,
    /** [SystemClock.elapsedRealtime] when it was answered, on this head unit's clock. */
    val answeredAt: Long,
    /** False: the companion may not answer / hang up, so the call is only shown. */
    val canControl: Boolean,
    /** The app the call is in ("WhatsApp"…); null for a phone call. */
    val app: String? = null,
    /** Answered / hung up through the head unit's own Bluetooth ([HeadUnitPhone]), not the companion. */
    val viaHeadUnit: Boolean = false,
    /** Our own call, not answered yet: "Calling" instead of a duration. */
    val dialing: Boolean = false,
    /** Made up by the alert style picker's "Try it": its buttons only close it. */
    val preview: Boolean = false
)

/**
 * What the phone shares for the Quick dial and Agenda tiles (a head unit has
 * no contacts or calendar of its own). Each is null until the phone sent it:
 * no link, an older companion, or not allowed on the phone.
 */
internal data class PhoneLists(
    val favourites: List<Favourite>? = null,
    /** Newest first. */
    val calls: List<RecentCall>? = null,
    val agenda: List<AgendaEvent>? = null
)

sealed interface PhoneLinkState {
    /** No phone paired. */
    data object Unpaired : PhoneLinkState
    /** Paired, waiting for the phone's hotspot and companion app. */
    data object Searching : PhoneLinkState
    data class Connected(val phoneName: String) : PhoneLinkState
}

object PhoneLink {
    private const val TAG = "PhoneLink"
    private const val PREFS = "phone_link"
    private const val KEY_PHONES = "phones"
    private const val CONNECT_TIMEOUT_MS = 3_000
    /** Bluetooth's connect() has no timeout of its own: a phone that is off or far away would hold it for ~20 s. */
    private const val BLUETOOTH_CONNECT_TIMEOUT_MS = 8_000L
    /**
     * A Bluetooth device connecting looks for the phone at once, but at most this
     * often: dialling a phone with no companion running connects and drops the radio
     * link itself, which would otherwise look like another device and start a loop.
     */
    private const val BLUETOOTH_WAKE_GAP_MS = 30_000L
    private const val HANDSHAKE_TIMEOUT_MS = 10_000
    /** Two missed pongs: the phone is gone, and the OBD relay and the tiles should know sooner than after three. */
    private const val READ_TIMEOUT_MS = 30_000
    private const val PING_EVERY_MS = 15_000L
    /** A send stuck this long is in a socket the phone no longer reads (Wi-Fi power save, out of range): the link is closed. */
    private const val SEND_STALL_MS = 5_000L
    /** How often to look for the phone: quickly while a pairing code is on screen. */
    private const val RETRY_MS = 10_000L
    /**
     * Nobody answering on this Wi-Fi (home, work: the gateway is a router, not
     * a phone) slows the tries down to this; a new network starts over at [RETRY_MS].
     */
    private const val RETRY_MAX_MS = 2 * 60_000L
    private const val RETRY_PAIRING_MS = 2_000L
    /**
     * A link that just ended is looked for again this soon, not after [RETRY_MS]: the phone
     * is usually still there, and the tiles went without it for 10 s at each drop (issue #118).
     */
    private const val RETRY_AFTER_DROP_MS = 1_500L
    /**
     * Any other driver's companion refuses a pairing it never saw, so one
     * refusal proves nothing: only this many, on occasions this far apart, with
     * none of this unit's pairings known there, mean the phone removed the car.
     */
    private const val REFUSALS_TO_FORGET = 3
    private const val REFUSAL_OCCASION_MS = 30 * 60_000L
    private const val OUTBOX_SIZE = 64

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    private val _state = MutableStateFlow<PhoneLinkState>(PhoneLinkState.Unpaired)
    val state: StateFlow<PhoneLinkState> = _state

    private val _phones = MutableStateFlow<List<PairedPhone>>(emptyList())
    val phones: StateFlow<List<PairedPhone>> = _phones

    /** The pairing whose QR code is on screen, until a phone uses it. */
    private val _pending = MutableStateFlow<PairingOffer?>(null)
    val pending: StateFlow<PairingOffer?> = _pending

    /**
     * The last try, as a short technical line for the pairing dialog and
     * Settings: the address dialled and what came back. Not translated: it is
     * read out when the link does not come up, to tell where it stops.
     */
    private val _lastAttempt = MutableStateFlow<String?>(null)
    val lastAttempt: StateFlow<String?> = _lastAttempt

    /** How the phone carried out a reply / mark-as-read / dismiss. */
    private val _results = MutableSharedFlow<ActionResult>(extraBufferCapacity = 8)
    val results: SharedFlow<ActionResult> = _results

    /** The phone's call; null when there is none (or no phone). */
    private val _call = MutableStateFlow<PhoneCall?>(null)
    val call: StateFlow<PhoneCall?> = _call

    /** The phone's favourites, last calls and agenda; all null without a link. */
    private val _lists = MutableStateFlow(PhoneLists())
    internal val lists: StateFlow<PhoneLists> = _lists
    /** The phone's own lists, kept up to date while [DemoMode] shows its made-up ones. */
    private var realLists = PhoneLists()
    /**
     * The phone can call a number for the car ([CallCommand.Action.DIAL]): it
     * sent [PhoneContacts], which came with DIAL. An older companion would skip
     * the command without a word.
     */
    @Volatile private var phoneDials = false
    private val main = Handler(Looper.getMainLooper())

    /** Bumped when the network changes or the phones change, to retry right away. */
    private val wake = MutableStateFlow(0)
    @Volatile private var session: LinkSession? = null
    /** Links that came up since start, to tell a try that linked (and has since ended) from one that didn't. */
    private val linksOpened = AtomicInteger()
    // Everything sent goes through one coroutine, so it reaches the phone in the order it was sent.
    private val outbox = Channel<Pair<LinkSession, LinkMessage>>(OUTBOX_SIZE)

    /** The link a send has been stuck in since when (uptime), for the stall watchdog; null between sends. */
    @Volatile private var sending: Pair<LinkSession, Long>? = null

    /** A link for the pending pairing, opened while another phone's link was up. */
    private class Handover(val gateway: InetAddress, val link: LinkSession, val phone: PairedPhone)
    private val handover = AtomicReference<Handover?>(null)

    private enum class Attempt { LINKED, REFUSED, UNREACHABLE }

    /** An open link, and for Bluetooth the guard that closes it when it goes quiet (it has no read timeout). */
    private class Opened(val link: LinkSession, val idle: IdleGuard? = null)

    /** How the Bluetooth try went: no radio or no paired phone, nobody answering, or a phone that did. */
    private enum class Reach { UNAVAILABLE, NOBODY, ANSWERED }

    /** The Bluetooth address of the phone that linked last: tried first next time. */
    @Volatile private var lastBluetoothPhone: String? = null
    @Volatile private var lastBluetoothWake = 0L

    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (started) return
            started = true
        }
        _phones.value = readPhones(app)
        refreshIdleState()
        app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = wake.update { it + 1 }
                override fun onLost(network: Network) = wake.update { it + 1 }
            }
        )
        // The phone connecting by Bluetooth, or Bluetooth being switched on: look for it now.
        // Never unregistered on purpose: start() runs once and this lives as long as the process.
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val now = SystemClock.elapsedRealtime()
                    if (session == null && now - lastBluetoothWake > BLUETOOTH_WAKE_GAP_MS) {
                        lastBluetoothWake = now
                        wake.update { it + 1 }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(receiver, filter)
        }
        scope.launch {
            for ((link, message) in outbox) {
                sending = link to android.os.SystemClock.elapsedRealtime()
                try {
                    link.sendOrClose(message)
                } finally {
                    sending = null
                }
            }
        }
        scope.launch { run(app) }
    }

    /** A fresh pairing code to show; replaces any previous one. */
    fun beginPairing(context: Context): PairingOffer {
        val offer = PairingOffer.create(unitName(context))
        _pending.value = offer
        wake.update { it + 1 }
        return offer
    }

    fun cancelPairing() {
        _pending.value = null
    }

    fun forget(context: Context, id: String) {
        updatePhones(context) { phones -> phones.filter { it.id != id } }
        // The phone connected now goes with its contacts and its notifications; another's leave the connected one's alone.
        if (session?.pairingId == id) {
            session?.close()
            editLists { PhoneLists() }
            _notificationsOn.value = false
        }
        refreshIdleState()
    }

    /** Sends to the connected phone; false when no phone is connected. */
    fun send(message: LinkMessage): Boolean {
        val current = session ?: return false
        return outbox.trySend(current to message).isSuccess
    }

    fun reply(key: String, text: String) = send(Reply(NotificationFeed.phoneKey(key), text))
    fun markRead(key: String) = send(MarkRead(NotificationFeed.phoneKey(key)))
    fun dismiss(key: String) = send(Dismiss(NotificationFeed.phoneKey(key)))
    fun callCommand(action: CallCommand.Action) = send(CallCommand(action))

    /** Has the phone call [number]; false when it can't be asked (no link, an older companion). */
    fun dial(number: String): Boolean = phoneDials && send(CallCommand(CallCommand.Action.DIAL, number))

    private val _battery = MutableStateFlow<PhoneBattery?>(null)
    /** The linked phone's battery, exact and with its charging ([PhoneBattery]); null without a companion link. */
    val battery: StateFlow<PhoneBattery?> = _battery

    private val _textsOn = MutableStateFlow(false)
    /** The linked phone sends texts for the car ([PhoneAbilities]): "On my way". */
    val textsOn: StateFlow<Boolean> = _textsOn

    private val _notificationsOn = MutableStateFlow(false)
    /** The linked phone was granted Notification access, so its notifications reach the car ([PhoneAbilities]). */
    val notificationsOn: StateFlow<Boolean> = _notificationsOn

    private val textIds = AtomicLong()
    private val textsWaiting = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()

    /** Has the phone text [text] to [number]: true once sent, false when refused, null without an answer. */
    suspend fun sendText(number: String, text: String): Boolean? {
        if (!_textsOn.value) return false
        val id = textIds.incrementAndGet()
        val answer = CompletableDeferred<Boolean>()
        textsWaiting[id] = answer
        return try {
            if (!send(SendText(number, text, id))) false else withTimeoutOrNull(TEXT_TIMEOUT_MS) { answer.await() }
        } finally {
            textsWaiting.remove(id)
        }
    }

    private const val TEXT_TIMEOUT_MS = 15_000L

    private val logIds = AtomicLong()
    private val logsWaiting = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()

    /**
     * Gives the phone the unit's log to share ([DebugLog]): true once it is saved
     * there, false when it can't be, null without an answer (no link, or a
     * companion too old to know the message).
     */
    suspend fun sendLog(title: String, text: String): Boolean? {
        val id = logIds.incrementAndGet()
        val answer = CompletableDeferred<Boolean>()
        logsWaiting[id] = answer
        return try {
            if (!send(CarLog(id, title, CarLog.fit(text)))) null else withTimeoutOrNull(LOG_TIMEOUT_MS) { answer.await() }
        } finally {
            logsWaiting.remove(id)
        }
    }

    private const val LOG_TIMEOUT_MS = 20_000L

    /** [DemoMode]'s lists. */
    internal fun demoWrite(lists: PhoneLists) {
        _lists.value = lists
    }

    /** The demo is over: the phone's own lists back, as they are now. */
    @Synchronized
    internal fun endDemo() {
        _lists.value = realLists
    }

    /** Applies [change] to the phone's lists; published unless the demo is on. */
    @Synchronized
    private fun editLists(change: (PhoneLists) -> PhoneLists) {
        realLists = change(realLists)
        if (!DemoMode.isOn) _lists.value = realLists
    }

    private suspend fun run(context: Context) {
        var last = wake.value
        // Tries in a row where no phone answered at the gateway.
        var misses = 0
        while (scope.isActive) {
            val linkedBefore = linksOpened.get()
            handover.getAndSet(null)?.let { runSession(context, it.gateway, it.link, it.phone, isPending = true) }
            val pending = _pending.value
            // The ones marked forgotten too: three refusals can be another driver's
            // phone on three trips (its companion never saw this car), and the
            // pairing was then dropped for good while its own phone still held it.
            val candidates = listOfNotNull(pending?.let { PairedPhone(it.id, it.secret, "", 0) }) + _phones.value
            val gateway = if (candidates.isEmpty()) null else hotspotGateway(context)
            var answered = gateway != null && dial(context, gateway, candidates, pending?.id)
            if (gateway == null && candidates.isNotEmpty()) note("no Wi-Fi gateway: not on a phone hotspot")
            // No hotspot, or nobody on it: the phone may be reachable over Bluetooth.
            var bluetooth = Reach.UNAVAILABLE
            if (!answered && candidates.isNotEmpty()) {
                bluetooth = dialBluetooth(context, candidates, pending?.id)
                answered = bluetooth == Reach.ANSWERED
            }
            // Tries that cost nothing (no Wi-Fi gateway, Bluetooth off) stay quick; ones that reached out for nobody back off.
            val free = gateway == null && bluetooth == Reach.UNAVAILABLE
            misses = if (answered || free) 0 else misses + 1
            refreshIdleState()
            val wait = when {
                _pending.value != null -> RETRY_PAIRING_MS
                linksOpened.get() != linkedBefore -> RETRY_AFTER_DROP_MS
                else -> retryDelay(misses)
            }
            // A network change or a new pairing code: look again at once, and quickly.
            if (withTimeoutOrNull(wait) { wake.first { it != last } } != null) misses = 0
            last = wake.value
        }
    }

    /** Waits between tries: 10 s, then doubling while nobody answers, up to 2 min. */
    internal fun retryDelay(misses: Int): Long =
        if (misses <= 1) RETRY_MS else (RETRY_MS shl (misses - 1).coerceAtMost(4)).coerceAtMost(RETRY_MAX_MS)

    /**
     * Tries each pairing on the phone at [gateway] until one links (and that link has ended).
     * False when nothing answered there at all.
     */
    private fun dial(context: Context, gateway: InetAddress, candidates: List<PairedPhone>, pendingId: String?): Boolean {
        val refused = mutableListOf<String>()
        var answered = false
        for (phone in candidates) {
            val isPending = phone.id == pendingId
            when (tryPhone(context, gateway, phone, isPending)) {
                Attempt.LINKED -> return true
                // A pairing code the phone hasn't accepted yet: the paired phones still get their turn.
                Attempt.REFUSED -> {
                    answered = true
                    if (!isPending) refused += phone.id
                }
                Attempt.UNREACHABLE -> Unit
            }
        }
        if (refused.isNotEmpty()) noteRefusals(context, refused)
        return answered
    }

    /** One attempt with one pairing. */
    private fun tryPhone(context: Context, gateway: InetAddress, phone: PairedPhone, isPending: Boolean): Attempt =
        attempt(context, "${gateway.hostAddress}:$LINK_PORT ${if (isPending) "new code" else "paired"}", gateway, phone, isPending) {
            Opened(connect(gateway, phone))
        }

    /** Opens a link with [open] and runs it until it ends; [gateway] is null over Bluetooth. */
    private fun attempt(
        context: Context, what: String, gateway: InetAddress?, phone: PairedPhone, isPending: Boolean, open: () -> Opened
    ): Attempt {
        val opened = try {
            open()
        } catch (e: UnknownPairingException) {
            note("$what: phone does not know this code")
            return Attempt.REFUSED
        } catch (e: IOException) {
            note("$what: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            return Attempt.UNREACHABLE
        } catch (e: Exception) {
            // A crypto provider missing an algorithm on this unit, say: say so rather than retry blind.
            note("$what: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            Log.w(TAG, "link attempt failed", e)
            return Attempt.UNREACHABLE
        }
        note("$what: linked")
        runSession(context, gateway, opened.link, phone, isPending, opened.idle)
        return Attempt.LINKED
    }

    /**
     * Dials the phones this unit is Bluetooth-paired with, for a unit that has
     * no hotspot to reach the phone by. Only phones: never the OBD adapter or
     * the other devices paired here. No hotspot refusals are counted from
     * here: a paired phone of another driver refusing says nothing about ours.
     */
    @SuppressLint("MissingPermission") // a missing grant throws SecurityException, caught below
    private fun dialBluetooth(context: Context, candidates: List<PairedPhone>, pendingId: String?): Reach {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return Reach.UNAVAILABLE
        val phones = try {
            if (!adapter.isEnabled) return Reach.UNAVAILABLE
            bondedPhones(adapter)
        } catch (e: SecurityException) {
            note("Bluetooth: not allowed on this unit")
            return Reach.UNAVAILABLE
        }
        if (phones.isEmpty()) {
            note("Bluetooth: no paired phone")
            return Reach.UNAVAILABLE
        }
        // A search under way makes every connection slow.
        runCatching { adapter.cancelDiscovery() }
        var answered = false
        for (device in phones) {
            for (phone in candidates) {
                val isPending = phone.id == pendingId
                val what = "Bluetooth ${if (isPending) "new code" else "paired"}"
                when (attempt(context, what, null, phone, isPending) { connectBluetooth(device, phone) }) {
                    Attempt.LINKED -> {
                        lastBluetoothPhone = device.address
                        return Reach.ANSWERED
                    }
                    Attempt.REFUSED -> answered = true
                    // Nobody on this device (no companion running, or out of range): its other codes would meet the same.
                    Attempt.UNREACHABLE -> break
                }
            }
        }
        // The last line the log keeps: refused (a phone answered, wrong code) is not nobody answering.
        note(if (answered) "Bluetooth: phone answered but does not know this code" else "Bluetooth: ${phones.size} paired phone(s), nobody answering")
        return if (answered) Reach.ANSWERED else Reach.NOBODY
    }

    /** The paired devices that are phones, the one that linked last first. The OBD adapter and the rest are left alone. */
    @SuppressLint("MissingPermission") // a missing grant throws SecurityException, caught below
    private fun bondedPhones(adapter: BluetoothAdapter): List<BluetoothDevice> =
        adapter.bondedDevices.orEmpty()
            .filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE }
            .sortedByDescending { it.address == lastBluetoothPhone }

    @SuppressLint("MissingPermission") // a missing grant throws SecurityException, caught below
    private fun connectBluetooth(device: BluetoothDevice, phone: PairedPhone): Opened {
        val socket = device.createRfcommSocketToServiceRecord(UUID.fromString(LINK_BLUETOOTH_UUID))
        // connect() has no timeout of its own; the guard closes the socket if it takes too long.
        val guard = IdleGuard(BLUETOOTH_CONNECT_TIMEOUT_MS, { runCatching { socket.close() } }).start()
        try {
            socket.connect()
            guard.timeoutMs = HANDSHAKE_TIMEOUT_MS.toLong()
            guard.kick()
            val link = SecureChannel.client(
                socket.inputStream, socket.outputStream, phone.id, phone.secret,
                onClose = { guard.stop(); runCatching { socket.close() } }
            )
            // Bluetooth has no read timeout: from here the guard ends a link that went quiet.
            guard.timeoutMs = READ_TIMEOUT_MS.toLong()
            guard.kick()
            return Opened(link, guard)
        } catch (e: Exception) {
            guard.stop()
            runCatching { socket.close() }
            throw e
        }
    }

    private fun note(line: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
        _lastAttempt.value = "$time  ${line.trim().take(140)}"
        Log.i(TAG, line)
    }

    private fun connect(gateway: InetAddress, phone: PairedPhone): LinkSession {
        val socket = Socket()
        try {
            // Out by the Wi-Fi the gateway was read from: with another network as
            // the unit's main one (a SIM, a cable), the dial left by that one and
            // never reached the phone. A network gone meanwhile: dialled as before.
            hotspotNetwork?.let { runCatching { it.bindSocket(socket) } }
            socket.connect(InetSocketAddress(gateway, LINK_PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            socket.tcpNoDelay = true
            val link = SecureChannel.client(
                socket.getInputStream(), socket.getOutputStream(), phone.id, phone.secret,
                onClose = { runCatching { socket.close() } }
            )
            socket.soTimeout = READ_TIMEOUT_MS
            return link
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw e
        }
    }

    /** Runs an open link until it ends. */
    private fun runSession(
        context: Context, gateway: InetAddress?, link: LinkSession, phone: PairedPhone, isPending: Boolean, idle: IdleGuard? = null
    ) {
        if (!link.sendOrClose(Hello(unitName(context), BuildConfig.VERSION_NAME))) return
        session = link
        linksOpened.incrementAndGet()
        // Each link's start and end go in the unit log, with why it ended: the drops testers see (issue #118)
        // left nothing there, the system log's lines of this class being filtered out of the report.
        val carried = if (gateway == null) "Bluetooth" else "Wi-Fi"
        val upAt = SystemClock.elapsedRealtime()
        var lastHeardAt = upAt // the reader's own: written and read on this thread only
        val endedBy = AtomicReference<String?>(null)
        DebugLog.note(context, "Phone link up over $carried")
        val pinger = scope.launch {
            while (isActive) {
                delay(PING_EVERY_MS)
                send(Ping)
            }
        }
        // A socket the phone stopped reading takes a send with it, and every message behind it
        // (the OBD relay's bytes first): closed, so the reader and the writer both let go.
        val watchdog = scope.launch {
            while (isActive) {
                delay(1_000)
                val (stuckIn, since) = sending ?: continue
                if (stuckIn === link && android.os.SystemClock.elapsedRealtime() - since > SEND_STALL_MS) {
                    Log.i(TAG, "link stalled: a send took over ${SEND_STALL_MS / 1000} s")
                    endedBy.compareAndSet(null, "a send to the phone stuck over ${SEND_STALL_MS / 1000} s")
                    link.close()
                    break
                }
            }
        }
        // While a pairing code is on screen, keep asking the phone whether it now knows it.
        // (Over Bluetooth there is no second link to try the code on: a new phone waits for this one to end.)
        val prober = if (isPending || gateway == null) null else scope.launch { probePending(gateway, link) }
        // Where the car stops, for the phone's "where's my car".
        val whereabouts = scope.launch { CarWhereabouts.report(context) { send(it) } }
        // The drives it logged, and the one under way, for the phone's drive journal.
        val drives = scope.launch { DriveLog.report { send(it) } }
        // The refuels, and what the phone should remind the driver of.
        val fuel = scope.launch { FuelLog.report { send(it) } }
        val news = scope.launch { CarNews.report { send(it) } }
        // A parking still running: the phone may have missed it, or lost its reminders to a restart.
        ParkingClock.load(context)
        ParkingClock.resend()
        try {
            while (true) {
                val received = link.receive()
                idle?.kick()
                lastHeardAt = SystemClock.elapsedRealtime()
                val message = received ?: continue
                // One message this build can't make sense of is skipped: thrown out of
                // here, it ended the launcher (nothing above catches it).
                try {
                    handle(context, phone, isPending, message)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "message not handled: ${message::class.simpleName}", e)
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "link ended: ${e.message}")
            // A read timeout (Wi-Fi) or the idle guard (Bluetooth) both end the link after this long without a word.
            val quiet = SystemClock.elapsedRealtime() - lastHeardAt >= READ_TIMEOUT_MS - 1_000
            endedBy.compareAndSet(null, if (quiet) "nothing heard from the phone for ${READ_TIMEOUT_MS / 1000} s"
                else "${e.javaClass.simpleName} ${e.message.orEmpty()}".trim())
        } finally {
            DebugLog.note(context, "Phone link over $carried down after ${(SystemClock.elapsedRealtime() - upAt) / 1000} s: ${endedBy.get() ?: "closed here"}")
            idle?.stop()
            pinger.cancel()
            watchdog.cancel()
            prober?.cancel()
            whereabouts.cancel()
            drives.cancel()
            fuel.cancel()
            news.cancel()
            link.close()
            if (session === link) session = null
            NotificationFeed.phoneClear()
            _call.value = null
            phoneDials = false
            // The phone's favourites and last calls stay on the tiles: the link drops and comes back
            // now and then, and the Quick dial emptied each time (issue #118). The next link replaces them.
            editLists { it.copy(agenda = null) }
            // An OBD link relayed by the phone ends with it; the unit's own Bluetooth takes over.
            PhoneObd.linkDown()
            _textsOn.value = false
            // Kept: the phone says again as the next link comes up. Reset, the tile asked for the phone's
            // Notification access for the moment between the link coming back and the phone saying so (#123).
            HeadUnitMedia.phoneGone()
            _battery.value = null
            PhoneGps.linkDown()
        }
    }

    /**
     * Once the phone accepts the pending pairing, its link takes over from
     * [current] (the phone keeps one link at a time anyway).
     */
    private suspend fun probePending(gateway: InetAddress, current: LinkSession) {
        while (true) {
            val offer = _pending.filterNotNull().first()
            val phone = PairedPhone(offer.id, offer.secret, "", 0)
            val link = try {
                connect(gateway, phone)
            } catch (e: IOException) {
                null
            }
            if (link != null) {
                if (!currentCoroutineContext().isActive) {
                    link.close()
                    return
                }
                handover.getAndSet(Handover(gateway, link, phone))?.link?.close()
                current.close()
                wake.update { it + 1 }
                return
            }
            delay(RETRY_PAIRING_MS)
        }
    }

    private fun handle(context: Context, phone: PairedPhone, isPending: Boolean, message: LinkMessage) {
        when (message) {
            is Hello -> {
                if (isPending) {
                    // The QR code was used: this phone is now paired.
                    _pending.value = null
                    val paired = PairedPhone(phone.id, phone.secret, message.deviceName, System.currentTimeMillis())
                    updatePhones(context) { phones -> phones.filter { it.id != phone.id } + paired }
                } else {
                    // It answered, so earlier refusals were some other phone's.
                    updatePhones(context) { phones ->
                        phones.map {
                            if (it.id == phone.id && (it.name != message.deviceName || it.refusals != 0 || it.forgotten)) {
                                it.copy(name = message.deviceName, refusals = 0, lastRefusalAt = 0, forgotten = false)
                            } else {
                                it
                            }
                        }
                    }
                }
                _state.value = PhoneLinkState.Connected(message.deviceName)
            }
            Ping -> send(Pong)
            is NotificationSync -> NotificationFeed.phoneSync(message.notifications)
            is NotificationPosted -> NotificationFeed.phonePosted(message.notification)
            is NotificationRemoved -> NotificationFeed.phoneRemoved(message.key)
            is ActionResult -> _results.tryEmit(message)
            is CallState -> _call.value = toPhoneCall(context, message)
            is TypeText -> PhoneKeyboard.type(context, message) { send(it) }
            EnableTyping -> PhoneKeyboard.enable(context) { send(it) }
            // The phone's touchpad: the pointer on the car's screen, and its taps.
            is PhonePointer -> PhoneMouse.handle(message) { send(it) }
            is PhoneContacts -> {
                phoneDials = true
                val favourites = message.favourites.map { Favourite(it.name, it.number, it.photoPng?.let(::bitmapOf)) }
                editLists { it.copy(favourites = favourites, calls = message.recentCalls) }
            }
            is AgendaSync -> {
                val events = message.events.map { AgendaEvent(it.title, it.begin, it.end, it.allDay, it.location) }
                editLists { it.copy(agenda = events) }
            }
            // Not allowed on the phone: the unit's own dialer, with the number in, as before the link; and why.
            is DialResult -> if (!message.placed) main.post {
                context.launchSafely(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", message.number, null)))
                HandsFree.say(context, R.string.phone_dial_not_allowed)
            }
            // Toasts and the voice want the main thread.
            is Destination -> main.post { NavHandoff.fromPhone(context, message) }
            is DashShareCode -> if (message.code.length <= DashShareCode.MAX_CHARS) DashShare.receive(message.code)
            is ObdOffer -> PhoneObd.onOffer(message)
            is ObdRelayState -> PhoneObd.onState(message)
            is ObdRx -> PhoneObd.onRx(message)
            is FuelPrice -> main.post { FuelLog.setPrice(context, message.at, message.pricePerL) }
            is PhoneAbilities -> {
                _textsOn.value = message.sendsTexts
                _notificationsOn.value = message.notifications
            }
            is TextSent -> textsWaiting[message.id]?.complete(message.sent)
            is CarLogAck -> logsWaiting[message.id]?.complete(message.ok)
            is CarLookPart -> MyCarLook.receive(context, message) { send(it) }
            is CarRegistration -> main.post { saveRegistration(context, message) }
            // A warning light or a booklet page photographed on the phone: the car asks its Gemini and answers.
            is PhotoQuestion -> PhotoAsk.answer(context, message) { send(it) }
            is ServiceEntries -> main.post {
                PhotoAsk.keep(message.entries)
                Toast.makeText(context, context.resources.getQuantityString(R.plurals.photo_booklet_saved, message.entries.size, message.entries.size), Toast.LENGTH_LONG).show()
            }
            is NowPlaying -> HeadUnitMedia.fromPhone(message)
            is PhoneBattery -> _battery.value = message
            is PhoneFix -> PhoneGps.feed(message)
            // What the phone's camera reads of the road ahead, while Road watch runs on it.
            is RoadScene -> RoadWatch.feed(message)
            // The phone scanned a second-screen display's code for this car.
            is DisplayPair -> DisplayLink.pair(context, message.uri)
            // A way out for a driver locked out of the screen: only from a phone paired here, never one being shown the code.
            is RescueCommand -> if (_phones.value.any { it.id == phone.id && it.secret.contentEquals(phone.secret) && !it.forgotten }) {
                main.post { send(RescueResult(message.id, PhoneRescue.run(context, message.action))) }
            } else {
                DebugLog.note(context, "Rescue ${message.action} refused: not a paired phone")
            }
            else -> Unit
        }
    }

    /** The registration the phone read: into the car's profile, and a word on screen that it landed. */
    private fun saveRegistration(context: Context, registration: CarRegistration) {
        if (registration.empty) return
        CarProfileStore.save(CarProfileStore.current.withRegistration(registration))
        RegistrationReader.date(registration.nextInspection)?.let {
            Maintenance.inspectionDueBy(it.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
        }
        val text = registration.plate.ifBlank { null }?.let { context.getString(R.string.car_registration_saved_plate, it) }
            ?: context.getString(R.string.car_registration_saved)
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    private fun bitmapOf(png: String): Bitmap? = runCatching {
        val bytes = Base64.getDecoder().decode(png)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun toPhoneCall(context: Context, state: CallState): PhoneCall? {
        if (state.phase == CallState.Phase.IDLE) return null
        // The same app on this head unit (a WhatsApp linked to the phone's) rings here
        // by itself, with its own screen: no card over it.
        if (state.packageName?.let { isInstalled(context, it) } == true) return null
        val before = _call.value
        val sameCaller = before != null && before.number == state.number && before.name == state.name && before.app == state.app
        val photo = if (before != null && state.photoPng != null && sameCaller) before.photo
        else state.photoPng?.let(::bitmapOf)
        return PhoneCall(
            phase = state.phase,
            number = state.number,
            name = state.name,
            photo = photo,
            answeredAt = SystemClock.elapsedRealtime() - state.activeForMs,
            canControl = state.canControl,
            app = state.app
        )
    }

    private fun isInstalled(context: Context, packageName: String): Boolean =
        runCatching { context.packageManager.getApplicationInfo(packageName, 0).enabled }.getOrDefault(false)

    /** Pairings every one of which the hotspot's phone refused; counted once per occasion. */
    private fun noteRefusals(context: Context, ids: List<String>) {
        val now = System.currentTimeMillis()
        updatePhones(context) { phones ->
            phones.map {
                if (it.id !in ids || now - it.lastRefusalAt in 0 until REFUSAL_OCCASION_MS) {
                    it
                } else {
                    val refusals = it.refusals + 1
                    it.copy(refusals = refusals, lastRefusalAt = now, forgotten = refusals >= REFUSALS_TO_FORGET)
                }
            }
        }
    }

    private fun refreshIdleState() {
        if (session != null && _state.value is PhoneLinkState.Connected) return
        _state.value = if (_phones.value.isEmpty() && _pending.value == null) PhoneLinkState.Unpaired else PhoneLinkState.Searching
    }

    /**
     * The phone's address when this head unit is on its hotspot: the Wi-Fi
     * network's default gateway. Android 11+ picks a random hotspot subnet,
     * so it is read from the network rather than assumed to be 192.168.43.1.
     */
    private fun hotspotGateway(context: Context): InetAddress? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION") // allNetworks: the Wi-Fi network may not be the default one.
        val networks = listOfNotNull(cm.activeNetwork) + cm.allNetworks
        return networks.distinct().firstNotNullOfOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@firstNotNullOfOrNull null
            cm.getLinkProperties(network)?.routes
                ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway
                ?.also { hotspotNetwork = network }
        }
    }

    /** The Wi-Fi network [hotspotGateway] last found the phone on. */
    @Volatile private var hotspotNetwork: android.net.Network? = null

    /** This head unit's name, as the phone and a display show it. */
    internal fun unitName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL?.takeIf { it.isNotBlank() }
            ?: "Dashwheel"

    private fun readPhones(context: Context): List<PairedPhone> =
        PairingStorage.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PHONES, null)).map {
            PairedPhone(it.id, it.secret, it.name, it.pairedAt, it.forgotten, it.refusals, it.lastRefusalAt)
        }

    /** Changes the paired phones and saves them, as one step: the link, the UI and pairing all change them. */
    @Synchronized
    private fun updatePhones(context: Context, change: (List<PairedPhone>) -> List<PairedPhone>) {
        val before = _phones.value
        val phones = change(before)
        if (phones == before) return
        val stored = phones.map { StoredPairing(it.id, it.secret, it.name, it.pairedAt, it.forgotten, it.refusals, it.lastRefusalAt) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PHONES, PairingStorage.encode(stored)).apply()
        _phones.value = phones
        wake.update { it + 1 }
    }
}
