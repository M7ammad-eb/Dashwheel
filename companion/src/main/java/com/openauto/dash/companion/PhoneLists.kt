package com.openauto.dash.companion

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.openauto.dash.link.AgendaSync
import com.openauto.dash.link.PhoneContacts
import com.openauto.dash.link.PhoneEvent
import com.openauto.dash.link.PhoneFavourite
import com.openauto.dash.link.RecentCall
import java.util.concurrent.Executors

/**
 * What the car's Quick dial and Agenda tiles show from this phone: the starred
 * contacts and the last calls ([PhoneContacts]), and the next events
 * ([AgendaSync]). A head unit has no contacts or calendar of its own. Sent
 * whole when the link comes up, then again when the contacts, the call log or
 * the calendar change (a burst of changes, as a sync makes, sends once), and
 * only when what the car would show changed. Each list is left out while its
 * permission is not granted.
 */
object PhoneLists {
    private const val TAG = "PhoneLists"
    /** A contacts sync touches the provider many times in a row. */
    private const val DEBOUNCE_MS = 3_000L
    /** Events come into the 36-hour window as time passes, without the calendar changing. */
    private const val AGENDA_EVERY_MS = 30 * 60_000L

    private val main = Handler(Looper.getMainLooper())
    // The provider queries and the photos, one at a time, off the main thread; [PhoneCalls] looks a caller up on it too.
    internal val worker = Executors.newSingleThreadExecutor()
    private var app: Context? = null
    private val observers = HashMap<Uri, ContentObserver>()
    @Volatile private var lastContacts: PhoneContacts? = null
    @Volatile private var lastAgenda: AgendaSync? = null

    private val sendChanged = Runnable { worker.execute { send(force = false) } }
    private val agendaTick = object : Runnable {
        override fun run() {
            worker.execute { send(force = false) }
            main.postDelayed(this, AGENDA_EVERY_MS)
        }
    }

    /** Follows the three providers while [LinkService] runs. */
    fun start(context: Context) {
        app = context.applicationContext
        watch()
        main.removeCallbacks(agendaTick)
        main.postDelayed(agendaTick, AGENDA_EVERY_MS)
    }

    fun stop(context: Context) {
        main.removeCallbacks(agendaTick)
        main.removeCallbacks(sendChanged)
        observers.values.forEach { runCatching { context.applicationContext.contentResolver.unregisterContentObserver(it) } }
        observers.clear()
        app = null
    }

    /** The link came up: the car gets everything, whether it changed or not. */
    fun linked() {
        worker.execute { send(force = true) }
    }

    /** A permission may have just been granted: follow its provider too, and send what it opens. */
    fun recheck() {
        main.post {
            watch()
            changed()
        }
    }

    private fun changed() {
        main.removeCallbacks(sendChanged)
        main.postDelayed(sendChanged, DEBOUNCE_MS)
    }

    /** Observers for the providers this app may read now; one refused (no permission yet) is tried again later. */
    private fun watch() {
        val context = app ?: return
        for (uri in listOf(ContactsContract.Contacts.CONTENT_URI, CallLog.Calls.CONTENT_URI, CalendarContract.CONTENT_URI)) {
            if (uri in observers) continue
            val observer = object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) = changed()
            }
            runCatching { context.contentResolver.registerContentObserver(uri, true, observer) }
                .onSuccess { observers[uri] = observer }
                .onFailure { Log.i(TAG, "cannot follow $uri yet: ${it.message}") }
        }
    }

    private fun send(force: Boolean) {
        val context = app ?: return
        if (LinkServer.state.value !is LinkState.Connected) return
        val contacts = if (granted(context, Manifest.permission.READ_CONTACTS) || granted(context, Manifest.permission.READ_CALL_LOG)) {
            PhoneContacts.of(favourites(context), recentCalls(context))
        } else null
        if (contacts != null && (force || contacts != lastContacts)) {
            lastContacts = contacts
            LinkServer.send(contacts)
        }
        val agenda = if (granted(context, Manifest.permission.READ_CALENDAR)) AgendaSync.of(events(context)) else null
        if (agenda != null && (force || agenda != lastAgenda)) {
            lastAgenda = agenda
            LinkServer.send(agenda)
        }
    }

    // ---- Reading the providers ----

    private fun favourites(context: Context): List<PhoneFavourite> {
        if (!granted(context, Manifest.permission.READ_CONTACTS)) return emptyList()
        val rows = mutableListOf<FavouriteRow>()
        runCatching {
            // The numbers themselves, each with its contact's star, pin and photo: one query.
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
                    ContactsContract.CommonDataKinds.Phone.PINNED,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI
                ),
                "${ContactsContract.CommonDataKinds.Phone.STARRED} = 1", null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                    rows += FavouriteRow(c.getLong(0), c.getString(1).orEmpty(), number, c.getInt(3) != 0, c.getInt(4), c.getString(5))
                }
            }
        }.onFailure { Log.w(TAG, "favourites not read", it) }
        return pickFavourites(rows, PhoneContacts.MAX_FAVOURITES).map { r ->
            PhoneFavourite(r.name.ifBlank { r.number }, r.number, r.photo?.let { PhoneCalls.photoPng(context, Uri.parse(it)) })
        }
    }

    private fun recentCalls(context: Context): List<RecentCall> {
        if (!granted(context, Manifest.permission.READ_CALL_LOG)) return emptyList()
        val out = mutableListOf<RecentCall>()
        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                null, null, "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext() && out.size < PhoneContacts.MAX_CALLS) {
                    // A hidden number can't be called back.
                    val number = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                    val type = callType(c.getInt(2)) ?: continue
                    out += RecentCall(number, c.getString(1)?.takeIf { it.isNotBlank() }, type, c.getLong(3))
                }
            }
        }.onFailure { Log.w(TAG, "call log not read", it) }
        return out
    }

    private fun events(context: Context): List<PhoneEvent> {
        val now = System.currentTimeMillis()
        val uri = ContentUris.appendId(
            ContentUris.appendId(CalendarContract.Instances.CONTENT_URI.buildUpon(), now), now + AgendaSync.HOURS * 3_600_000L
        ).build()
        val out = mutableListOf<PhoneEvent>()
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION
                ),
                // The calendars shown in the phone's own calendar app, not the hidden ones; nothing cancelled.
                "${CalendarContract.Instances.VISIBLE} = 1 AND ${CalendarContract.Instances.STATUS} != ${CalendarContract.Instances.STATUS_CANCELED}",
                null, "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { c ->
                while (c.moveToNext() && out.size < AgendaSync.MAX_ITEMS) {
                    val end = c.getLong(2)
                    if (end < now) continue
                    out += PhoneEvent(c.getString(0).orEmpty(), c.getLong(1), end, c.getInt(3) == 1, c.getString(4).orEmpty())
                }
            }
        }.onFailure { Log.w(TAG, "calendar not read", it) }
        return out
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/** One of a starred contact's numbers, as the provider lists them. */
internal data class FavouriteRow(
    val contactId: Long,
    val name: String,
    val number: String,
    val primary: Boolean,
    /** The dialer's order for favourites: 1, 2... pinned in that order; 0 or less not pinned. */
    val pinned: Int,
    val photo: String?
)

/**
 * One number per starred contact (its default one, else the first listed),
 * in the phone's own favourites order: the pinned ones first, then by name.
 */
internal fun pickFavourites(rows: List<FavouriteRow>, max: Int): List<FavouriteRow> =
    rows.groupBy { it.contactId }.values
        .map { numbers -> numbers.firstOrNull { it.primary } ?: numbers.first() }
        .sortedWith(compareBy<FavouriteRow>({ if (it.pinned > 0) it.pinned else Int.MAX_VALUE }, { it.name.lowercase() }))
        .take(max)

/** The call log's type as the car tells calls apart; null for what isn't a call to show (voicemail, blocked). */
internal fun callType(type: Int): RecentCall.Type? = when (type) {
    CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> RecentCall.Type.INCOMING
    CallLog.Calls.OUTGOING_TYPE -> RecentCall.Type.OUTGOING
    CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> RecentCall.Type.MISSED
    else -> null
}
