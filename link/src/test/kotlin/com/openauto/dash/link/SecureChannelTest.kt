package com.openauto.dash.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class SecureChannelTest {

    private val offer = PairingOffer.create("Test unit")

    /** Runs the phone side on a thread; returns its session (or its failure). */
    private fun phone(server: ServerSocket, secretFor: (String) -> ByteArray?): CompletableFuture<LinkSession> =
        CompletableFuture.supplyAsync {
            val s = server.accept()
            s.soTimeout = 5_000
            SecureChannel.server(s.getInputStream(), s.getOutputStream(), secretFor, onClose = s::close)
        }

    private fun headUnit(port: Int, id: String, secret: ByteArray): LinkSession {
        val s = Socket(InetAddress.getLoopbackAddress(), port)
        s.soTimeout = 5_000
        return SecureChannel.client(s.getInputStream(), s.getOutputStream(), id, secret, onClose = s::close)
    }

    @Test
    fun messagesFlowBothWaysOnceBothProveTheSecret() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val phoneSide = phone(server) { if (it == offer.id) offer.secret else null }
            val unit = headUnit(server.localPort, offer.id, offer.secret)
            val phone = phoneSide.get(5, TimeUnit.SECONDS)
            assertEquals(offer.id, phone.pairingId)

            unit.send(Hello("Head unit", "1.0"))
            assertEquals(Hello("Head unit", "1.0"), phone.receive())

            val n = PhoneNotification(
                key = "0|com.whatsapp|1|null|10", packageName = "com.whatsapp", appName = "WhatsApp",
                title = "Alice", text = "On se voit à 18h ?", postedAt = 1_700_000_000_000,
                messages = listOf(ConversationLine("Alice", "On se voit à 18h ?", 1_700_000_000_000)),
                canReply = true, canMarkRead = true
            )
            // Several frames each way keep the per-direction counters in step.
            repeat(3) { phone.send(NotificationPosted(n.copy(postedAt = n.postedAt + it))) }
            repeat(3) { assertEquals(NotificationPosted(n.copy(postedAt = n.postedAt + it)), unit.receive()) }
            unit.send(Reply(n.key, "J'arrive"))
            unit.send(Ping)
            assertEquals(Reply(n.key, "J'arrive"), phone.receive())
            assertEquals(Ping, phone.receive())
            unit.close()
            phone.close()
        }
    }

    @Test
    fun wrongSecretIsRefusedByTheHeadUnit() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val other = PairingOffer.create("Other")
            // The phone holds a different secret for this id: it can't produce MAC_s.
            val phoneSide = phone(server) { other.secret }
            try {
                headUnit(server.localPort, offer.id, offer.secret)
                fail("handshake should fail")
            } catch (e: HandshakeException) {
                // expected
            }
            runCatching { phoneSide.get(5, TimeUnit.SECONDS) }
        }
    }

    @Test
    fun unknownPairingIsReportedToTheHeadUnit() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val phoneSide = phone(server) { null }
            try {
                headUnit(server.localPort, offer.id, offer.secret)
                fail("handshake should fail")
            } catch (e: UnknownPairingException) {
                // expected
            }
            assertTrue(runCatching { phoneSide.get(5, TimeUnit.SECONDS) }.isFailure)
        }
    }

    /** A sender and a receiver sharing keys, the wire being a byte buffer a test can edit. */
    private fun wire(frames: ByteArray): LinkSession {
        val k1 = ByteArray(32) { 1 }
        val k2 = ByteArray(32) { 2 }
        return LinkSession(frames.inputStream(), java.io.ByteArrayOutputStream(), k2, k1, "id", onClose = {})
    }

    private fun sent(vararg messages: LinkMessage): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val sender = LinkSession(ByteArray(0).inputStream(), out, ByteArray(32) { 1 }, ByteArray(32) { 2 }, "id", onClose = {})
        messages.forEach(sender::send)
        return out.toByteArray()
    }

    @Test
    fun tamperedFrameIsRejected() {
        val frames = sent(Ping)
        assertEquals(Ping, wire(frames).receive())
        frames[frames.size - 1] = (frames[frames.size - 1].toInt() xor 1).toByte()
        try {
            wire(frames).receive()
            fail("tampered frame accepted")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun replayedFrameIsRejected() {
        val one = sent(Ping)
        // The same frame twice: the second is checked against the next counter.
        val receiver = wire(one + one)
        assertEquals(Ping, receiver.receive())
        try {
            receiver.receive()
            fail("replayed frame accepted")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun oversizedFrameIsRefusedBeforeAllocating() {
        val frames = java.nio.ByteBuffer.allocate(4).putInt(LinkSession.MAX_FRAME + 1).array()
        try {
            wire(frames).receive()
            fail("oversized frame accepted")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun unknownMessageTypesAreSkipped() {
        assertNull(LinkCodec.decode("""{"t":"sms_thread","id":3}""".encodeToByteArray()))
        // A newer phase this side doesn't know drops the message rather than the link.
        assertNull(LinkCodec.decode("""{"t":"call","phase":"ON_HOLD"}""".encodeToByteArray()))
        assertNull(LinkCodec.decode("not json".encodeToByteArray()))
        // Unknown fields on a known message are ignored too.
        assertEquals(NotificationRemoved("k"), LinkCodec.decode("""{"t":"notif_removed","key":"k","extra":1}""".encodeToByteArray()))
    }

    @Test
    fun callMessagesRoundTrip() {
        val ringing = CallState(CallState.Phase.RINGING, number = "+33 6 12 34 56 78", name = "Alice", canControl = true)
        assertEquals(ringing, LinkCodec.decode(LinkCodec.encode(ringing)))
        assertEquals(CallState(CallState.Phase.IDLE), LinkCodec.decode("""{"t":"call","phase":"IDLE"}""".encodeToByteArray()))
        // An app's call names the app and no number; an older head unit reads it as a plain call.
        val whatsapp = CallState(CallState.Phase.RINGING, name = "Alice", app = "WhatsApp", packageName = "com.whatsapp")
        assertEquals(whatsapp, LinkCodec.decode(LinkCodec.encode(whatsapp)))
        assertEquals(
            CallState(CallState.Phase.ACTIVE, name = "Alice"),
            LinkCodec.decode("""{"t":"call","phase":"ACTIVE","name":"Alice"}""".encodeToByteArray())
        )
        val answer = CallCommand(CallCommand.Action.ANSWER)
        assertEquals(answer, LinkCodec.decode(LinkCodec.encode(answer)))
    }

    @Test
    fun rawFramesTravelBetweenMessages() {
        val out = java.io.ByteArrayOutputStream()
        val sender = LinkSession(ByteArray(0).inputStream(), out, ByteArray(32) { 1 }, ByteArray(32) { 2 }, "id", onClose = {})
        val au = ByteArray(40_000) { (it * 7).toByte() }
        sender.send(Hello("unit", "1"))
        sender.sendBinary(au)
        sender.send(Ping)
        val receiver = wire(out.toByteArray())
        assertEquals(Incoming.Message(Hello("unit", "1")), receiver.receiveAny())
        val raw = receiver.receiveAny() as Incoming.Binary
        assertTrue(raw.bytes.contentEquals(au))
        assertEquals(Incoming.Message(Ping), receiver.receiveAny())
    }

    @Test
    fun receiveSkipsRawFramesLikeAnOlderPeer() {
        val out = java.io.ByteArrayOutputStream()
        val sender = LinkSession(ByteArray(0).inputStream(), out, ByteArray(32) { 1 }, ByteArray(32) { 2 }, "id", onClose = {})
        sender.sendBinary(byteArrayOf(1, 2, 3))
        sender.send(Ping)
        val receiver = wire(out.toByteArray())
        assertNull(receiver.receive())
        assertEquals(Ping, receiver.receive())
    }

    @Test
    fun oversizedRawFrameIsNotSent() {
        val out = java.io.ByteArrayOutputStream()
        val sender = LinkSession(ByteArray(0).inputStream(), out, ByteArray(32) { 1 }, ByteArray(32) { 2 }, "id", onClose = {})
        assertEquals(false, sender.sendBinary(ByteArray(LinkSession.MAX_MESSAGE)))
        assertEquals(0, out.size())
        assertTrue(sender.sendBinary(ByteArray(LinkSession.MAX_MESSAGE - 1)))
    }

    @Test
    fun messageJsonCannotHoldIsNotSentAndTheNextOneStillGoes() {
        val out = java.io.ByteArrayOutputStream()
        val sender = LinkSession(ByteArray(0).inputStream(), out, ByteArray(32) { 1 }, ByteArray(32) { 2 }, "id", onClose = {})
        // A NaN thrown out of send() ended the head unit's only writer: every later message, the call included, was lost (#118).
        assertEquals(false, sender.sendOrClose(CarLocation(Double.NaN, 2.0, 0)))
        assertEquals(0, out.size())
        assertTrue(sender.sendOrClose(Ping))
        assertEquals(Ping, wire(out.toByteArray()).receive())
    }
}
