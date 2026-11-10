package com.openauto.dash.companion

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.ContactsContract
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.openauto.dash.link.CallCommand
import com.openauto.dash.link.CallState
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * The call, for the car. Two kinds, told the same way to the head unit:
 *
 * - The phone's own calls: the system's PHONE_STATE broadcast, the caller
 *   found in the contacts, answered or ended through [TelecomManager] as a
 *   smartwatch app does.
 * - Calls in an app (WhatsApp, Signal, Telegram…): never in the phone's call
 *   state, so read from the app's own call notification ([CallNotification],
 *   through [PhoneNotificationListener]), answered or ended with the very
 *   buttons the notification carries.
 *
 * A phone call shows first; else the app call announced last. The call's
 * sound stays on the car's Bluetooth hands-free; this only carries who is
 * calling and the buttons.
 */
object PhoneCalls {
    private const val TAG = "PhoneCalls"
    private const val PHOTO_PX = 96
    private const val PHONE = "phone"

    /** What the head unit is told now; re-sent whole when the link comes up. */
    @Volatile
    var current: CallState = CallState(CallState.Phase.IDLE)
        private set

    /** The phone's own call. */
    private var phone: CallState = CallState(CallState.Phase.IDLE)
    /** The apps' calls, by notification key. */
    private val appCalls = LinkedHashMap<String, CallNotification>()
    /** Which call [current] is: [PHONE] or a notification key. */
    private var currentId: String? = null
    @Volatile
    private var answeredAt = 0L
    private var receiver: BroadcastReceiver? = null

    /** Listens while [LinkService] runs. */
    fun start(context: Context) {
        if (receiver != null) return
        val app = context.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = onPhoneState(app, intent)
        }
        // A protected system broadcast: only the system can send it.
        ContextCompat.registerReceiver(app, r, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        receiver = r
        seed(app)
    }

    fun stop(context: Context) {
        receiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        receiver = null
        // Nobody listens any more: a call under way now would never be heard to
        // end, and the car was shown it, hours long, at the next drive.
        synchronized(this) {
            phone = CallState(CallState.Phase.IDLE)
            publish()
        }
    }

    /**
     * A call already under way as the sharing starts (the driver gets in on
     * the phone): no broadcast says so, they only tell of changes. Without
     * the caller, which only the broadcast carries.
     */
    @SuppressLint("MissingPermission") // checked just before
    @Suppress("DEPRECATION") // callState: all that is asked is ringing, in a call or neither.
    private fun seed(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        val phase = when (runCatching { context.getSystemService(TelephonyManager::class.java)?.callState }.getOrNull()) {
            TelephonyManager.CALL_STATE_RINGING -> CallState.Phase.RINGING
            TelephonyManager.CALL_STATE_OFFHOOK -> CallState.Phase.ACTIVE
            else -> return
        }
        synchronized(this) {
            if (phone.phase != CallState.Phase.IDLE) return
            phone = CallState(phase = phase, canControl = canControl(context))
            publish()
        }
    }

    /** The state to send now, its answered time brought up to date. */
    fun snapshot(): CallState {
        val c = current
        return if (c.phase == CallState.Phase.ACTIVE) c.copy(activeForMs = SystemClock.elapsedRealtime() - answeredAt) else c
    }

    /** Carries out the head unit's [action] on the call under way. False when not allowed or nothing to act on. */
    @Synchronized
    fun command(context: Context, action: CallCommand.Action): Boolean {
        // A new call, not this one: see dial().
        if (action == CallCommand.Action.DIAL) return false
        val id = currentId ?: return false
        if (id == PHONE) return phoneCommand(context, action)
        val call = appCalls[id] ?: return false
        val intent = when (action) {
            CallCommand.Action.ANSWER -> call.answer
            // A call that rings is declined; one just taken is hung up: whichever the app offers.
            CallCommand.Action.DECLINE -> call.decline ?: call.hangUp
            CallCommand.Action.HANG_UP -> call.hangUp ?: call.decline
            CallCommand.Action.DIAL -> null
        } ?: return false
        return send(context, intent)
    }

    /**
     * Calls [number] as the car asked (a Quick dial favourite tapped there).
     * Through [TelecomManager.placeCall], which needs no screen: the phone is
     * in a pocket. False without CALL_PHONE: the car then offers its own dialer.
     */
    @SuppressLint("MissingPermission") // checked just before
    fun dial(context: Context, number: String): Boolean {
        if (number.isBlank()) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return false
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return false
        return try {
            telecom.placeCall(Uri.fromParts("tel", number, null), android.os.Bundle())
            LinkServer.note("calling $number for the car", logged = "calling a number for the car")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "call refused", e)
            false
        }
    }

    @SuppressLint("MissingPermission") // checked by canControl()
    private fun phoneCommand(context: Context, action: CallCommand.Action): Boolean {
        if (!canControl(context)) return false
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return false
        return try {
            when (action) {
                CallCommand.Action.ANSWER -> {
                    telecom.acceptRingingCall()
                    true
                }
                // Deprecated for new designs (an InCallService), but still what a
                // non-dialer app with ANSWER_PHONE_CALLS uses to end a call.
                CallCommand.Action.DECLINE, CallCommand.Action.HANG_UP -> @Suppress("DEPRECATION") telecom.endCall()
                CallCommand.Action.DIAL -> false
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "call $action refused", e)
            false
        }
    }

    /** Presses a call notification's button, as the shade would. */
    private fun send(context: Context, intent: PendingIntent): Boolean = try {
        // The app may answer by opening its call screen: let it, from Android 14 on.
        val options = if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else null
        intent.send(context, 0, null, null, null, null, options)
        true
    } catch (e: PendingIntent.CanceledException) {
        Log.w(TAG, "call button gone", e)
        false
    }

    fun canControl(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED

    // ---- The apps' calls, from their notifications ----

    /** [sbn] is a call notification: taken as the call, not as a notification. */
    @Synchronized
    fun onNotificationPosted(context: Context, sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        if (!CallNotification.isCall(n)) return false
        // The phone's own calls are followed through the call state, not the dialer's notification.
        if (isDialer(context, sbn.packageName)) return true
        val call = CallNotification.parse(context, sbn) ?: return true
        appCalls[sbn.key] = call
        publish()
        return true
    }

    @Synchronized
    fun onNotificationRemoved(key: String) {
        if (appCalls.remove(key) != null) publish()
    }

    /** Notification access granted (or the listener back): reads the calls announced now. */
    @Synchronized
    fun onListenerConnected(listener: PhoneNotificationListener) {
        appCalls.clear()
        val active = runCatching { listener.activeNotifications }.getOrNull().orEmpty()
        for (sbn in active) {
            val n = sbn.notification ?: continue
            if (!CallNotification.isCall(n) || isDialer(listener, sbn.packageName)) continue
            CallNotification.parse(listener, sbn)?.let { appCalls[sbn.key] = it }
        }
        publish()
    }

    /** Notification access gone: the apps' calls can't be followed any more. */
    @Synchronized
    fun onListenerDisconnected() {
        if (appCalls.isEmpty()) return
        appCalls.clear()
        publish()
    }

    /** The phone app: the default dialer, or the in-call screen it comes with (its own package on some phones). */
    private fun isDialer(context: Context, pkg: String): Boolean =
        pkg == "com.android.phone" || pkg == "com.android.server.telecom" || "incallui" in pkg ||
            pkg == runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()

    // ---- The phone's own calls ----

    private fun onPhoneState(context: Context, intent: Intent) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        // Only there with READ_CALL_LOG; the broadcast then comes twice, with and without it.
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.takeIf { it.isNotBlank() }
        val phase = when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> CallState.Phase.RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> CallState.Phase.ACTIVE
            else -> CallState.Phase.IDLE
        }
        synchronized(this) {
            val before = phone
            var unknown: String? = null
            phone = if (phase == CallState.Phase.IDLE) {
                CallState(CallState.Phase.IDLE)
            } else {
                // The same call keeps its caller when a later broadcast lacks the number.
                val sameCall = before.phase != CallState.Phase.IDLE
                val known = number ?: before.number.takeIf { sameCall }
                // A number not seen in this call: told now, as a number; the name and photo follow from the worker.
                if (known != null && known != before.number) unknown = known
                CallState(
                    phase = phase,
                    number = known,
                    name = before.name.takeIf { sameCall && known == before.number },
                    photoPng = before.photoPng.takeIf { sameCall && known == before.number },
                    canControl = canControl(context)
                )
            }
            publish()
            unknown?.let { lookUpLater(context, it) }
        }
    }

    /** Which contact lookup is the latest: an older one's answer, come late, is dropped. */
    private var lookups = 0

    /**
     * The contacts query and the photo's decode take tens of milliseconds: off
     * the main thread, where the broadcast had them run under the lock
     * [onNotificationPosted] waits for. The answer only counts while this call,
     * with this number, is still the one shown: a later state is never overtaken.
     */
    private fun lookUpLater(context: Context, number: String) {
        val gen = ++lookups
        PhoneLists.worker.execute {
            val contact = lookUp(context, number) ?: return@execute
            synchronized(this) {
                if (gen != lookups || phone.phase == CallState.Phase.IDLE || phone.number != number) return@synchronized
                phone = phone.copy(name = contact.first, photoPng = contact.second)
                publish()
            }
        }
    }

    /** Picks the call to show, and tells the head unit when it changed. */
    private fun publish() {
        val before = current
        val beforeId = currentId
        val app = appCalls.values.maxByOrNull { it.postedAt }
        // On some phones (a Samsung…) an app's call reaches the phone's call
        // state too, without a number: the app's notification knows who it is,
        // and its buttons work where Telecom ignores a call it doesn't manage.
        val phoneFirst = phone.phase != CallState.Phase.IDLE && (app == null || phone.number != null)
        val next: CallState
        val id: String?
        when {
            phoneFirst -> {
                next = phone
                id = PHONE
            }
            app != null -> {
                next = CallState(
                    phase = if (app.incoming) CallState.Phase.RINGING else CallState.Phase.ACTIVE,
                    name = app.caller,
                    photoPng = app.photoPng,
                    canControl = app.canControl,
                    app = app.appName,
                    packageName = app.packageName
                )
                id = app.key
            }
            else -> {
                next = CallState(CallState.Phase.IDLE)
                id = null
            }
        }
        if (next.phase == CallState.Phase.ACTIVE && (before.phase != CallState.Phase.ACTIVE || id != beforeId)) {
            // Taken now; an app's notification may say when, on the wall clock.
            val startedAt = app?.startedAt?.takeIf { id != PHONE }
            answeredAt = if (startedAt != null) SystemClock.elapsedRealtime() - (System.currentTimeMillis() - startedAt).coerceAtLeast(0)
            else SystemClock.elapsedRealtime()
        }
        current = next
        currentId = id
        if (next != before) {
            LinkServer.note(describe(next, id), logged = describe(next, id, named = false))
            LinkServer.send(snapshot())
        }
    }

    /** One line for the companion's status: what the car is told of the call. */
    private fun describe(state: CallState, id: String?, named: Boolean = true): String {
        val who = if (named) state.name ?: state.number ?: "?" else "someone"
        val from = if (id == PHONE) "phone" else "${state.app ?: "app"} notification"
        return when (state.phase) {
            CallState.Phase.IDLE -> "call ended"
            CallState.Phase.RINGING -> "call from $who ringing ($from${if (state.canControl) "" else ", no buttons"})"
            CallState.Phase.ACTIVE -> "call with $who taken ($from${if (state.canControl) "" else ", no buttons"})"
        }
    }

    /** (name, photo) from the phone's contacts, when allowed and found. */
    private fun lookUp(context: Context, number: String): Pair<String?, String?>? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI),
                null, null, null
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                val name = c.getString(0)
                val photo = c.getString(1)?.let { photoPng(context, Uri.parse(it)) }
                name to photo
            }
        }.onFailure { Log.w(TAG, "contact lookup failed", it) }.getOrNull()
    }

    /** A contact's photo as a small PNG, base64, as the car shows it. */
    internal fun photoPng(context: Context, uri: Uri): String? = runCatching {
        val bitmap = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) ?: return null
        val scaled = Bitmap.createScaledBitmap(bitmap, PHOTO_PX, PHOTO_PX, true)
        val bytes = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        Base64.getEncoder().encodeToString(bytes.toByteArray())
    }.getOrNull()
}
