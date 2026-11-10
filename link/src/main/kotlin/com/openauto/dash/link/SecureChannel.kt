package com.openauto.dash.link

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * The encrypted channel between the head unit (client) and the phone (server),
 * or a second-screen display (also a server, see DisplayMessages.kt).
 *
 * Handshake, over length-prefixed frames:
 *   1. client → server  "DWL1" · pairing id · client nonce · client ephemeral P-256 key
 *   2. server → client  status · server nonce · server ephemeral key · MAC_s
 *   3. client → server  MAC_c
 * with th = SHA-256(frame 1 ‖ frame 2 without its MAC) and
 *   MAC_s = HMAC(secret, "server" ‖ th), MAC_c = HMAC(secret, "client" ‖ th).
 * Each side proves it holds the pairing secret from the QR code; the fresh
 * ECDH keys give every connection its own session keys (forward secrecy):
 *   HKDF-SHA256(salt = secret, ikm = ECDH, info = "dashwheel link v1" ‖ th)
 *     → 32 bytes client→server key ‖ 32 bytes server→client key.
 * Then every frame is AES-256-GCM with a per-direction counter as the nonce,
 * so a frame replayed, dropped or reordered fails to decrypt.
 *
 * Only standard JCA algorithms, present on the JVM and on every Android the
 * apps support.
 */

/** The phone doesn't know this pairing (it was removed there): pair again. */
class UnknownPairingException : IOException("pairing not known by the phone")

/** The other side failed to prove it holds the pairing secret. */
class HandshakeException(message: String) : IOException(message)

/** An open, authenticated channel. [send] is thread-safe; [receive] / [receiveAny] are for one reader thread. */
class LinkSession internal constructor(
    input: InputStream,
    output: OutputStream,
    sendKey: ByteArray,
    receiveKey: ByteArray,
    /** Which pairing this connection proved it holds. */
    val pairingId: String,
    private val onClose: () -> Unit
) : Closeable {
    private val input = DataInputStream(input)
    private val output = DataOutputStream(output)
    private val sendKey = SecretKeySpec(sendKey, "AES")
    private val receiveKey = SecretKeySpec(receiveKey, "AES")
    private var sendCounter = 0L
    private var receiveCounter = 0L

    /**
     * Sends [message]. False, with nothing sent, when it is too big for one
     * frame (the other side would refuse the frame and drop the whole link),
     * or when JSON can't hold it (a NaN): thrown, it ended the caller's writer.
     */
    fun send(message: LinkMessage): Boolean {
        val plain = try {
            LinkCodec.encode(message)
        } catch (_: IllegalArgumentException) { // kotlinx SerializationException is one
            return false
        }
        if (plain.size > MAX_MESSAGE) return false
        seal(plain)
        return true
    }

    /**
     * Sends raw bytes (a video access unit for a display), in a frame of its
     * own. False, with nothing sent, when it is too big for one frame. Only
     * sent to a peer that asked for them: [receive] on an older peer skips them.
     */
    fun sendBinary(payload: ByteArray): Boolean {
        if (payload.size + 1 > MAX_MESSAGE) return false
        // A JSON message always starts with '{', so a leading 0 marks the bytes as raw.
        val plain = ByteArray(payload.size + 1)
        System.arraycopy(payload, 0, plain, 1, payload.size)
        plain[0] = BINARY_MARK
        seal(plain)
        return true
    }

    /** [send], closing the link when it is broken. True when the message went. */
    fun sendOrClose(message: LinkMessage): Boolean = try {
        send(message)
    } catch (e: IOException) {
        close()
        false
    }

    /**
     * The next message, or null for one this side doesn't understand (skip it);
     * raw frames ([sendBinary]) are skipped too.
     * Throws [IOException] when the link is gone or a frame was tampered with.
     */
    fun receive(): LinkMessage? = (receiveAny() as? Incoming.Message)?.message

    /**
     * The next frame: a message, raw bytes, or null for a message this side
     * doesn't understand (skip it). Throws like [receive].
     */
    fun receiveAny(): Incoming? {
        val frame = readFrame(input, MAX_FRAME)
        val cipher = Cipher.getInstance(AES_GCM)
        val plain = try {
            cipher.init(Cipher.DECRYPT_MODE, receiveKey, GCMParameterSpec(128, nonce(receiveCounter++)))
            cipher.doFinal(frame)
        } catch (e: GeneralSecurityException) {
            throw IOException("frame failed authentication", e)
        }
        if (plain.isNotEmpty() && plain[0] == BINARY_MARK) return Incoming.Binary(plain.copyOfRange(1, plain.size))
        return LinkCodec.decode(plain)?.let(Incoming::Message)
    }

    private fun seal(plain: ByteArray) {
        synchronized(output) {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(128, nonce(sendCounter++)))
            writeFrame(output, cipher.doFinal(plain))
        }
    }

    override fun close() = onClose()

    private fun nonce(counter: Long): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()

    companion object {
        /** A generous cap for one message (a notification with its icon is a few kB). */
        const val MAX_FRAME = 512 * 1024
        /** The largest encoded message that fits in a frame, next to the GCM tag. */
        const val MAX_MESSAGE = MAX_FRAME - GCM_TAG_BYTES

        private const val BINARY_MARK: Byte = 0
    }
}

/** One frame read by [LinkSession.receiveAny]. */
sealed class Incoming {
    data class Message(val message: LinkMessage) : Incoming()

    class Binary(val bytes: ByteArray) : Incoming()
}

object SecureChannel {
    private const val MAGIC = "DWL1"
    private const val NONCE_BYTES = 32
    private const val MAX_HANDSHAKE_FRAME = 4 * 1024
    private const val STATUS_OK: Byte = 0
    private const val STATUS_UNKNOWN_PAIRING: Byte = 1
    private val random = SecureRandom()

    /** Head unit side: open the channel for pairing [pairingId] holding [secret]. */
    fun client(input: InputStream, output: OutputStream, pairingId: String, secret: ByteArray, onClose: () -> Unit = {}): LinkSession {
        val din = DataInputStream(input)
        val dout = DataOutputStream(output)
        val ephemeral = newEphemeral()
        val clientNonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val clientHello = buildFrame {
            write(MAGIC.encodeToByteArray())
            writeUTF(pairingId)
            write(clientNonce)
            writeBytes16(ephemeral.public.encoded)
        }
        writeFrame(dout, clientHello)

        val serverHello = readFrame(din, MAX_HANDSHAKE_FRAME)
        val reader = DataInputStream(serverHello.inputStream())
        when (reader.readByte()) {
            STATUS_OK -> Unit
            STATUS_UNKNOWN_PAIRING -> throw UnknownPairingException()
            else -> throw HandshakeException("unexpected server status")
        }
        val serverNonce = ByteArray(NONCE_BYTES).also(reader::readFully)
        val serverKey = decodeKey(reader.readBytes16())
        val serverMac = ByteArray(32).also(reader::readFully)
        val th = sha256(clientHello, serverHello.copyOf(serverHello.size - serverMac.size))
        if (!MessageDigest.isEqual(serverMac, hmac(secret, "server".encodeToByteArray(), th))) {
            throw HandshakeException("phone failed to prove the pairing")
        }
        writeFrame(dout, hmac(secret, "client".encodeToByteArray(), th))

        val keys = sessionKeys(secret, agree(ephemeral.private, serverKey), th)
        return LinkSession(input, output, keys.first, keys.second, pairingId, onClose)
    }

    /**
     * Phone side: answer a head unit. [secretFor] gives the secret of a known
     * pairing id, or null (the head unit is then told to pair again).
     */
    fun server(input: InputStream, output: OutputStream, secretFor: (String) -> ByteArray?, onClose: () -> Unit = {}): LinkSession {
        val din = DataInputStream(input)
        val dout = DataOutputStream(output)
        val clientHello = readFrame(din, MAX_HANDSHAKE_FRAME)
        val reader = DataInputStream(clientHello.inputStream())
        val magic = ByteArray(MAGIC.length).also(reader::readFully)
        if (magic.decodeToString() != MAGIC) throw HandshakeException("not a Dashwheel link")
        val pairingId = reader.readUTF()
        val clientNonce = ByteArray(NONCE_BYTES).also(reader::readFully)
        val clientKey = decodeKey(reader.readBytes16())

        val secret = secretFor(pairingId)
        if (secret == null) {
            writeFrame(dout, byteArrayOf(STATUS_UNKNOWN_PAIRING))
            throw UnknownPairingException()
        }
        val ephemeral = newEphemeral()
        val serverNonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val serverHelloBody = buildFrame {
            writeByte(STATUS_OK.toInt())
            write(serverNonce)
            writeBytes16(ephemeral.public.encoded)
        }
        val th = sha256(clientHello, serverHelloBody)
        writeFrame(dout, serverHelloBody + hmac(secret, "server".encodeToByteArray(), th))

        val clientMac = readFrame(din, MAX_HANDSHAKE_FRAME)
        if (!MessageDigest.isEqual(clientMac, hmac(secret, "client".encodeToByteArray(), th))) {
            throw HandshakeException("head unit failed to prove the pairing")
        }

        val keys = sessionKeys(secret, agree(ephemeral.private, clientKey), th)
        return LinkSession(input, output, keys.second, keys.first, pairingId, onClose)
    }

    private fun newEphemeral() = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"), random)
        generateKeyPair()
    }

    private fun decodeKey(encoded: ByteArray): PublicKey = try {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
    } catch (e: GeneralSecurityException) {
        throw HandshakeException("bad ephemeral key")
    }

    private fun agree(own: java.security.PrivateKey, peer: PublicKey): ByteArray = try {
        KeyAgreement.getInstance("ECDH").run {
            init(own)
            doPhase(peer, true)
            generateSecret()
        }
    } catch (e: GeneralSecurityException) {
        throw HandshakeException("key agreement failed")
    }

    /** (client→server key, server→client key). */
    private fun sessionKeys(secret: ByteArray, shared: ByteArray, th: ByteArray): Pair<ByteArray, ByteArray> {
        val prk = hmac(secret, shared)
        val info = "dashwheel link v1".encodeToByteArray() + th
        // HKDF-Expand for two blocks (64 bytes).
        val t1 = hmac(prk, info, byteArrayOf(1))
        val t2 = hmac(prk, t1, info, byteArrayOf(2))
        return t1 to t2
    }

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            parts.forEach(::update)
            doFinal()
        }

    private fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            parts.forEach(::update)
            digest()
        }

    private inline fun buildFrame(block: DataOutputStream.() -> Unit): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        DataOutputStream(bytes).apply(block).flush()
        return bytes.toByteArray()
    }

    private fun DataOutputStream.writeBytes16(bytes: ByteArray) {
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBytes16(): ByteArray {
        val size = readUnsignedShort()
        if (size > MAX_HANDSHAKE_FRAME) throw HandshakeException("oversized field")
        return ByteArray(size).also(::readFully)
    }
}

private const val AES_GCM = "AES/GCM/NoPadding"
private const val GCM_TAG_BYTES = 16

internal fun writeFrame(output: DataOutputStream, bytes: ByteArray) {
    output.writeInt(bytes.size)
    output.write(bytes)
    output.flush()
}

internal fun readFrame(input: DataInputStream, max: Int): ByteArray {
    val size = input.readInt()
    if (size < 0 || size > max) throw IOException("frame of $size bytes refused")
    return ByteArray(size).also(input::readFully)
}
