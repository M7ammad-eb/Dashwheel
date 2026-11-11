package com.openauto.dash

import android.media.session.PlaybackState

/*
 * Which of the media sessions the system lists the media tile follows.
 *
 * It used to be "the first one playing, else the first one listed", and on a
 * unit with YouTube and YouTube Music both up that went wrong three ways:
 *  - between two songs YouTube Music is briefly buffering / skipping, not
 *    playing, so the tile jumped to whichever session was listed first (often
 *    YouTube, with an old video) and back: the song showed, vanished, came back;
 *  - paused, the tile fell to the first session listed, which could be an
 *    empty or long-stopped one: the paused song disappeared;
 *  - a stopped session with old metadata, listed first, was shown as if current.
 *
 * Now: a session that is playing, or about to (buffering, skipping), wins,
 * the one that started most recently first. With none, the paused one that
 * was shown stays, else the one that played last. A stopped session is shown
 * only when it was the one on screen already, or is the last media app used.
 */

/** What a session is doing, as far as picking one goes. */
internal enum class SessionMood {
    /** Playing (or seeking within the track). */
    PLAYING,
    /** Between two moments of playing: buffering, connecting, skipping to another track. */
    BETWEEN,
    PAUSED,
    /** Stopped, errored, or saying nothing. */
    IDLE;

    val active: Boolean get() = this == PLAYING || this == BETWEEN

    companion object {
        fun of(state: Int?): SessionMood = when (state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING -> PLAYING
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> BETWEEN
            PlaybackState.STATE_PAUSED -> PAUSED
            else -> IDLE
        }
    }
}

/** One listed session, as [SessionPick] sees it. [key] tells sessions apart (their token). */
internal data class SessionView(
    val key: Any,
    val pkg: String?,
    val mood: SessionMood,
    /** Has a title (or any metadata) to show. */
    val hasMetadata: Boolean,
    /** One of the head unit's own players, which publish an empty session (HeadUnitMedia). */
    val stock: Boolean = false
)

/**
 * Remembers when each session last became active (started playing or
 * buffering after being paused or stopped), and picks one ([pick]).
 * Not thread-safe: used from the main thread.
 */
internal class SessionPick(private val clock: () -> Long) {

    /** Session key → when it last went from not active to active. */
    private val activeSince = HashMap<Any, Long>()
    /** Session key → when it was last seen active at all. */
    private val lastActive = HashMap<Any, Long>()
    private val wasActive = HashSet<Any>()
    /** Session key → when it was last seen actually playing. */
    private val lastPlaying = HashMap<Any, Long>()

    /** Notes what every session does now; call before [pick] with the same list. */
    fun observe(sessions: List<SessionView>) {
        val now = clock()
        val keys = sessions.mapTo(HashSet()) { it.key }
        // Forget sessions that are gone.
        activeSince.keys.retainAll(keys)
        lastActive.keys.retainAll(keys)
        wasActive.retainAll(keys)
        lastPlaying.keys.retainAll(keys)
        for (s in sessions) {
            if (s.mood.active) {
                if (s.key !in wasActive) activeSince[s.key] = now
                lastActive[s.key] = now
                if (s.mood == SessionMood.PLAYING) lastPlaying[s.key] = now
                wasActive += s.key
            } else {
                wasActive -= s.key
            }
        }
    }

    /**
     * The session to show, or null for none.
     *
     * @param shown the key of the session on screen now, if any.
     * @param lastPackage the last media app the user played, kept across restarts.
     */
    fun pick(sessions: List<SessionView>, shown: Any?, lastPackage: String?): SessionView? {
        // 1. Playing or about to: the one that started last. Ties (all seen at once) keep the system's order.
        // One stuck buffering for long doesn't take the tile from one that plays.
        val now = clock()
        val anyPlaying = sessions.any { it.mood == SessionMood.PLAYING }
        sessions.filter { it.mood.active }
            .filterNot { anyPlaying && it.mood == SessionMood.BETWEEN && now - (lastPlaying[it.key] ?: activeSince[it.key] ?: now) > STALL_MS }
            .maxWithOrNull(compareBy<SessionView> { activeSince[it.key] ?: Long.MIN_VALUE }
                // At the same start, a playing one over a buffering one, then the one shown.
                .thenBy { it.mood == SessionMood.PLAYING }
                .thenBy { it.key == shown }
                .thenByDescending { sessions.indexOf(it) })
            ?.let { return it }

        // 2. Nothing plays: the paused one on screen stays.
        val current = sessions.firstOrNull { it.key == shown }
        if (current != null && current.mood == SessionMood.PAUSED && (current.hasMetadata || current.stock)) return current

        // 3. The paused one that played last, else any paused one with something to show.
        sessions.filter { it.mood == SessionMood.PAUSED && it.hasMetadata }
            .maxWithOrNull(compareBy<SessionView> { lastActive[it.key] ?: Long.MIN_VALUE }
                .thenBy { it.pkg == lastPackage }
                .thenByDescending { sessions.indexOf(it) })
            ?.let { return it }

        // 4. A stopped session only when it is what was on screen, or the last app used (shown at start-up).
        if (current != null && current.hasMetadata) return current
        sessions.firstOrNull { it.hasMetadata && it.pkg != null && it.pkg == lastPackage }?.let { return it }

        // 5. The head unit's own players: their session is empty, what they play comes from elsewhere.
        return sessions.firstOrNull { it.stock }
    }

    companion object {
        /** How long a session may buffer or skip before one that plays wins over it. */
        const val STALL_MS = 10_000L
    }
}
