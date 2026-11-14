package com.openauto.dash

import android.app.Notification
import android.media.session.MediaSession
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/*
 * What each player's own notification says it plays. Some head units'
 * factory music players keep a media session that plays with no title in
 * it, and the media tile said "Nothing playing" over the music; their
 * notification still names the song. [CarMediaController] reads it from
 * here when the session says nothing ([untitledTrack]). Fed by
 * [MediaNotificationListenerService], on the main thread.
 */
object PlayerNotifications {
    private const val TAG = "PlayerNotifications"

    data class Said(val key: String, val title: String, val text: String)

    private val said = ConcurrentHashMap<String, Said>()
    /** Package → the media session its notification names, and that notification's key. */
    private val sessions = ConcurrentHashMap<String, Pair<String, MediaSession.Token>>()
    private val listeners = CopyOnWriteArraySet<(String) -> Unit>()

    /** The latest player notification of [pkg], or null. */
    fun of(pkg: String?): Said? = pkg?.let { said[it] }

    /**
     * The media sessions players' notifications name, by package. Some units'
     * system lists a new player's session late, or not at all until another
     * player changes ([CarMediaController] finds them here too).
     */
    fun sessionTokens(): Map<String, MediaSession.Token> = sessions.mapValues { it.value.second }

    /** Told (main thread) the package whose player notification changed. */
    fun addListener(listener: (String) -> Unit) = listeners.add(listener)
    fun removeListener(listener: (String) -> Unit) = listeners.remove(listener)

    fun onPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val token = sessionToken(n)
        val newSession = token != null && sessions.put(sbn.packageName, sbn.key to token)?.second != token
        // A player's notification: the media kind, or one that stays up while it plays.
        val player = n.category == Notification.CATEGORY_TRANSPORT ||
            n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) || sbn.isOngoing
        if (!player) return
        if (newSession) tell(sbn.packageName)
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val now = Said(sbn.key, title, text)
        if (said.put(sbn.packageName, now) != now) tell(sbn.packageName)
    }

    fun onRemoved(sbn: StatusBarNotification) {
        if (sessions[sbn.packageName]?.first == sbn.key) {
            sessions.remove(sbn.packageName)
            if (said[sbn.packageName]?.key != sbn.key) tell(sbn.packageName)
        }
        if (said[sbn.packageName]?.key != sbn.key) return
        said.remove(sbn.packageName)
        tell(sbn.packageName)
    }

    fun clear() {
        said.clear()
        sessions.clear()
    }

    @Suppress("DEPRECATION") // the typed getParcelable is API 33; the app runs from 28
    private fun sessionToken(n: Notification): MediaSession.Token? =
        runCatching { n.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION) }.getOrNull()

    private fun tell(pkg: String) = listeners.forEach { runCatching { it(pkg) }.onFailure { e -> Log.w(TAG, "listener failed", e) } }
}

/**
 * The title and artist for a session that names no title: its display title,
 * else what its notification says, else, while it plays, the player's own
 * name, so the tile no longer says "Nothing playing" over the music. Null
 * when there's nothing to say.
 */
internal fun untitledTrack(
    displayTitle: String?,
    displaySubtitle: String?,
    notification: PlayerNotifications.Said?,
    appName: String?,
    playing: Boolean
): Pair<String, String>? = when {
    !displayTitle.isNullOrBlank() -> displayTitle.trim() to displaySubtitle.orEmpty().trim()
    notification != null && notification.title.isNotEmpty() -> notification.title to notification.text
    notification != null && notification.text.isNotEmpty() -> notification.text to ""
    playing && !appName.isNullOrBlank() -> appName.trim() to ""
    else -> null
}
