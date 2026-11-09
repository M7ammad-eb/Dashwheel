package com.openauto.dash.display

import com.openauto.dash.link.ClusterArt
import com.openauto.dash.link.ClusterRoute
import com.openauto.dash.link.ClusterState
import com.openauto.dash.link.DisplayBrightness
import com.openauto.dash.link.DisplayCommand
import com.openauto.dash.link.DisplayHello
import com.openauto.dash.link.DisplayMode
import com.openauto.dash.link.DisplayWords
import com.openauto.dash.link.DisplayStats
import com.openauto.dash.link.DisplayTime
import com.openauto.dash.link.Hello
import com.openauto.dash.link.Incoming
import com.openauto.dash.link.LinkSession
import com.openauto.dash.link.Ping
import com.openauto.dash.link.Pong
import com.openauto.dash.link.SecureChannel
import com.openauto.dash.link.VideoConfig
import com.openauto.dash.link.VideoAck
import com.openauto.dash.link.VideoPacket
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Answers the head unit. One session at a time: a new connection (the head
 * unit redialling after the hotspot dropped) replaces the old one, whose
 * socket may not have noticed yet.
 */
class DisplayServer(
    private val config: DisplayConfig,
    private val pairing: DisplayPairing,
    private val hello: DisplayHello,
    /** The monitor's brightness buttons, when wired. */
    private val buttons: BrightnessButtons? = null,
    /** Time that only moves forward: LocalClock steps the wall clock after every Hello. */
    private val uptimeMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val screenFor: (requestKeyFrame: () -> Unit) -> Screen = { request -> Screen(config, ScreenMode(hello.width, hello.height), pairing, request) }
) {
    @Volatile private var current: LinkSession? = null
    @Volatile private var lastKeyRequest = Long.MIN_VALUE / 2
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "display-stats").apply { isDaemon = true } }
    val screen: Screen = screenFor(::requestKeyFrame)

    fun run(server: ServerSocket = ServerSocket(config.port)) {
        Beacon(pairing.offer.id) { current != null }.start()
        // The first picture takes a few seconds on a Pi 3 (fonts, GStreamer): a head unit is
        // let in meanwhile, and waits only where it needs the screen (its methods are locked).
        Thread({ screen.start() }, "display-screen-start").apply { isDaemon = true }.start()
        timer.scheduleAtFixedRate(::sendStats, STATS_MS, STATS_MS, TimeUnit.MILLISECONDS)
        log("listening on port ${server.localPort}")
        server.use {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    if (server.isClosed) break else continue
                }
                Thread({ serve(socket) }, "display-session").apply { isDaemon = true }.start()
            }
        }
    }

    private fun serve(socket: Socket) {
        val session = try {
            socket.tcpNoDelay = true
            socket.soTimeout = READ_TIMEOUT_MS
            SecureChannel.server(socket.getInputStream(), socket.getOutputStream(), pairing::secretFor, onClose = socket::close)
        } catch (e: IOException) {
            log("refused ${socket.inetAddress.hostAddress}: ${e.message}")
            runCatching { socket.close() }
            return
        }
        synchronized(this) {
            current?.close()
            current = session
        }
        log("head unit connected from ${socket.inetAddress.hostAddress}")
        pairing.markUsed()
        if (config.readOnlyAfterPairing) ReadOnlyCard.turnOnFromNextStart(pairing.dir.parentFile ?: pairing.dir)
        try {
            session.send(hello)
            // Here before it has said a word: the idle screen stops offering the pairing code.
            screen.heard()
            screen.idle(linked = true)
            while (true) {
                val frame = session.receiveAny()
                if (frame != null) screen.heard()
                when (frame) {
                    is Incoming.Binary -> VideoPacket.decode(frame.bytes)?.let { packet ->
                        // Here: the head unit keeps no more than a moment of video unconfirmed (VideoWindow).
                        session.sendOrClose(VideoAck(packet.ptsUs))
                        screen.feed(packet)
                    }
                    is Incoming.Message -> handle(session, frame)
                    null -> Unit
                }
            }
        } catch (e: IOException) {
            log("head unit gone: ${e.message}")
        } finally {
            session.close()
            val wasCurrent = synchronized(this) {
                (current === session).also { if (it) current = null }
            }
            if (wasCurrent) screen.idle()
        }
    }

    private fun handle(session: LinkSession, frame: Incoming.Message) {
        when (val message = frame.message) {
            is DisplayMode -> {
                screen.turn(message.rotate180)
                when (message.mode) {
                DisplayMode.Mode.IDLE -> screen.idle(linked = true)
                DisplayMode.Mode.DATA -> screen.data(null)
                DisplayMode.Mode.VIDEO -> screen.video()
                }
            }
            is VideoConfig -> screen.configureVideo(message)
            is ClusterState -> screen.data(message)
            is ClusterRoute -> screen.route(message.points)
            is ClusterArt -> screen.art(message)
            is DisplayBrightness -> buttons?.set(message.level, message.from, message.fresh)
            is DisplayTime -> if (LocalClock.set(pairing.dir, message)) screen.wordsChanged()
            is DisplayWords -> if (Words.set(pairing.dir, message)) {
                log("words now in ${message.language.ifEmpty { "?" }}")
                screen.wordsChanged()
            }
            is Ping -> session.sendOrClose(Pong)
            is Hello -> log("head unit: ${message.deviceName} ${message.appVersion}, link protocol ${message.protocol}")
            else -> Unit
        }
    }

    private fun requestKeyFrame() {
        val now = uptimeMs()
        // The decoder may ask on every dropped frame; one request per half second is plenty.
        if (now - lastKeyRequest < 500) return
        lastKeyRequest = now
        // Asked from under the screen's and the decoder's locks: the socket write goes on the
        // stats thread, so a head unit that stops reading can't freeze the drawing behind it.
        timer.execute { current?.sendOrClose(DisplayCommand(DisplayCommand.Action.KEYFRAME_PLEASE)) }
    }

    /** Every few seconds, in every mode: the video's counts (zero without video) and the board's health; a heartbeat too. */
    private fun sendStats() {
        val video = screen.takeVideoStats()
        val board = BoardHealth.readAndLog()
        val session = current ?: return
        val counts = if (screen.showing == Screen.Showing.VIDEO) video else VideoSink.Stats(0, 0, 0, 0)
        session.sendOrClose(
            DisplayStats(
                counts.shown, counts.dropped, (counts.bytes * 8 / STATS_MS).toInt(), STATS_MS,
                droppedLate = counts.droppedLate, throttled = board.throttled, tempC = board.tempC,
                cpuPct = board.cpuPct, ramUsedMb = board.ramUsedMb, ramTotalMb = board.ramTotalMb, cpuMhz = board.cpuMhz
            )
        )
    }

    private companion object {
        const val STATS_MS = 5_000L
        /**
         * The head unit pings every 5 s (an older one every 15 s): a link silent
         * this long is dead, and the next connection is let in sooner.
         */
        const val READ_TIMEOUT_MS = 20_000
    }
}

internal fun log(message: String) = println("[dashwheel-display] $message")
