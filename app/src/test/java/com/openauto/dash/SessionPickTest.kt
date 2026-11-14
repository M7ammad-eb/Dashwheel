package com.openauto.dash

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionPickTest {

    private var now = 1_000L
    private val pick = SessionPick { now }

    private fun s(key: String, mood: SessionMood, meta: Boolean = true, stock: Boolean = false, track: String? = if (meta) "song-$key" else null) =
        SessionView(key, key, mood, meta, stock, track)

    private fun choose(vararg sessions: SessionView, shown: String? = null, last: String? = null): String? {
        val list = sessions.toList()
        pick.observe(list)
        return pick.pick(list, shown, last)?.key as String?
    }

    @Test fun `playing beats the first listed`() {
        assertEquals("ytm", choose(s("yt", SessionMood.PAUSED), s("ytm", SessionMood.PLAYING)))
    }

    @Test fun `a song change does not jump to another session`() {
        // YouTube Music plays; YouTube sits paused with an old video, listed first.
        assertEquals("ytm", choose(s("yt", SessionMood.PAUSED), s("ytm", SessionMood.PLAYING)))
        now += 100
        // Skipping, then buffering, with its metadata gone for a moment.
        assertEquals("ytm", choose(s("yt", SessionMood.PAUSED), s("ytm", SessionMood.BETWEEN), shown = "ytm"))
        now += 100
        assertEquals("ytm", choose(s("yt", SessionMood.PAUSED), s("ytm", SessionMood.BETWEEN, meta = false), shown = "ytm"))
        now += 100
        assertEquals("ytm", choose(s("yt", SessionMood.PAUSED), s("ytm", SessionMood.PLAYING), shown = "ytm"))
    }

    @Test fun `paused song stays rather than the first listed`() {
        assertEquals("ytm", choose(s("yt", SessionMood.PAUSED), s("ytm", SessionMood.PLAYING)))
        now += 1_000
        assertEquals("ytm", choose(s("yt", SessionMood.PAUSED), s("ytm", SessionMood.PAUSED), shown = "ytm"))
    }

    @Test fun `the paused one that played last wins when none is shown`() {
        choose(s("a", SessionMood.PLAYING), s("b", SessionMood.PAUSED))
        now += 1_000
        choose(s("a", SessionMood.PAUSED), s("b", SessionMood.PLAYING))
        now += 1_000
        assertEquals("b", choose(s("a", SessionMood.PAUSED), s("b", SessionMood.PAUSED)))
    }

    @Test fun `the most recently started player wins`() {
        assertEquals("yt", choose(s("yt", SessionMood.PLAYING), s("ytm", SessionMood.PAUSED)))
        now += 5_000
        // YouTube Music started while the YouTube video still reports playing.
        assertEquals("ytm", choose(s("yt", SessionMood.PLAYING), s("ytm", SessionMood.PLAYING), shown = "yt"))
        now += 1_000
        assertEquals("ytm", choose(s("yt", SessionMood.PLAYING), s("ytm", SessionMood.PLAYING), shown = "ytm"))
    }

    @Test fun `stuck buffering loses to a player that plays`() {
        assertEquals("b", choose(s("a", SessionMood.PAUSED), s("b", SessionMood.PLAYING)))
        now += 1_000
        // A newer one starts buffering: it takes the tile, as a player starting would...
        assertEquals("a", choose(s("a", SessionMood.BETWEEN), s("b", SessionMood.PLAYING), shown = "b"))
        now += 12_000
        // ...but never gets to play: the one playing all along comes back.
        assertEquals("b", choose(s("a", SessionMood.BETWEEN), s("b", SessionMood.PLAYING), shown = "a"))
    }

    @Test fun `a stale stopped session is not shown`() {
        assertNull(choose(s("old", SessionMood.IDLE), s("empty", SessionMood.PAUSED, meta = false)))
    }

    @Test fun `a stopped session is kept when it was on screen or is the last app used`() {
        assertEquals("ytm", choose(s("ytm", SessionMood.IDLE), shown = "ytm"))
        assertEquals("ytm", choose(s("old", SessionMood.IDLE), s("ytm", SessionMood.IDLE), last = "ytm"))
    }

    @Test fun `the unit's own player with its empty session is still picked`() {
        assertEquals("bt", choose(s("x", SessionMood.IDLE, meta = false), s("bt", SessionMood.IDLE, meta = false, stock = true)))
    }

    @Test fun `pausing keeps the song over a stale untitled session that says it plays`() {
        // FYT-style unit: com.tw.bt keeps an "Unknown" session playing for hours.
        assertEquals("ytm", choose(s("bt", SessionMood.PLAYING, meta = false), s("ytm", SessionMood.PAUSED)))
        now += 1_000
        assertEquals("ytm", choose(s("bt", SessionMood.PLAYING, meta = false), s("ytm", SessionMood.PLAYING), shown = "ytm"))
        now += 30_000
        assertEquals("ytm", choose(s("bt", SessionMood.PLAYING, meta = false), s("ytm", SessionMood.PAUSED), shown = "ytm"))
        now += 60_000
        assertEquals("ytm", choose(s("bt", SessionMood.PLAYING, meta = false), s("ytm", SessionMood.PAUSED), shown = "ytm"))
    }

    @Test fun `at start a paused song beats an untitled session already playing`() {
        assertEquals("ytm", choose(s("bt", SessionMood.PLAYING, meta = false), s("ytm", SessionMood.PAUSED)))
    }

    @Test fun `an untitled player started after the pause still takes the tile`() {
        assertEquals("ytm", choose(s("x", SessionMood.IDLE, meta = false), s("ytm", SessionMood.PLAYING)))
        now += 1_000
        assertEquals("ytm", choose(s("x", SessionMood.IDLE, meta = false), s("ytm", SessionMood.PAUSED), shown = "ytm"))
        now += 5_000
        assertEquals("x", choose(s("x", SessionMood.PLAYING, meta = false), s("ytm", SessionMood.PAUSED), shown = "ytm"))
    }

    @Test fun `real bluetooth music with a title still wins`() {
        assertEquals("ytm", choose(s("bt", SessionMood.PLAYING, meta = false), s("ytm", SessionMood.PAUSED)))
        now += 1_000
        // The phone starts playing over Bluetooth: the unit's session gets the track's title.
        assertEquals("bt", choose(s("bt", SessionMood.PLAYING), s("ytm", SessionMood.PAUSED), shown = "ytm"))
    }

    @Test fun `pausing keeps the song over an old titled session that says it plays`() {
        // Topway unit: a com.tw.bt session keeps "playing" an old track for hours.
        choose(s("bt", SessionMood.PLAYING), s("ytm", SessionMood.PAUSED))
        now += 1_000
        assertEquals("ytm", choose(s("bt", SessionMood.PLAYING), s("ytm", SessionMood.PLAYING)))
        now += 30_000
        assertEquals("ytm", choose(s("bt", SessionMood.PLAYING), s("ytm", SessionMood.PAUSED), shown = "ytm"))
        now += 60_000
        assertEquals("ytm", choose(s("bt", SessionMood.PLAYING), s("ytm", SessionMood.PAUSED), shown = "ytm"))
        // The phone goes on to another song over Bluetooth: that is a start, and it takes the tile.
        now += 1_000
        assertEquals("bt", choose(s("bt", SessionMood.PLAYING, track = "next song"), s("ytm", SessionMood.PAUSED), shown = "ytm"))
    }

    @Test fun `a song playing at start-up beats a paused one never seen playing`() {
        assertEquals("bt", choose(s("bt", SessionMood.PLAYING), s("ytm", SessionMood.PAUSED)))
    }

    @Test fun `placeholder titles name nothing`() {
        assertEquals(true, isPlaceholderTitle("Unknown"))
        assertEquals(true, isPlaceholderTitle(" unknown "))
        assertEquals(true, isPlaceholderTitle("<unknown>"))
        assertEquals(true, isPlaceholderTitle(null))
        assertEquals(false, isPlaceholderTitle("Unknown Pleasures"))
    }

    @Test fun `playback states map to moods`() {
        assertEquals(SessionMood.PLAYING, SessionMood.of(PlaybackState.STATE_PLAYING))
        assertEquals(SessionMood.BETWEEN, SessionMood.of(PlaybackState.STATE_BUFFERING))
        assertEquals(SessionMood.BETWEEN, SessionMood.of(PlaybackState.STATE_SKIPPING_TO_NEXT))
        assertEquals(SessionMood.PAUSED, SessionMood.of(PlaybackState.STATE_PAUSED))
        assertEquals(SessionMood.IDLE, SessionMood.of(PlaybackState.STATE_STOPPED))
        assertEquals(SessionMood.IDLE, SessionMood.of(null))
    }
}
