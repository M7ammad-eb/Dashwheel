package com.openauto.dash.companion

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Lets the driver's own computer fetch the kept clips over Tailscale, off
 * until turned on. Only connections reaching this phone on its Tailscale
 * address and carrying the key get an answer: GET /clips lists the clips,
 * GET /clips/<name>/clip.mp4 (or clip.json) sends one, from a byte offset
 * when asked with a Range header, so a fetch cut off on mobile data resumes.
 * GET /cameras says what the phone's cameras can do ([CameraReport]), GET /roadwatch how
 * Road watch last ran ([RoadStats]), GET /signs the signs it tried to read
 * (GET /signs/<name> one of them, a picture).
 * Started by [LinkService] with the sharing, or by its switch.
 */
object ClipServer {
    const val PORT = 47_712
    private const val TAG = "ClipServer"
    private const val PREFS = "clip_server"
    private const val KEY_ON = "on"
    private const val KEY_KEY = "key"
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_HEADERS = 50
    private val FILES = setOf("clip.mp4", "clip.json")

    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on

    @Volatile private var key = ""
    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(2)

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        key = prefs.getString(KEY_KEY, null) ?: ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
            .also { prefs.edit().putString(KEY_KEY, it).apply() }
        _on.value = prefs.getBoolean(KEY_ON, false)
    }

    fun setOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        _on.value = on
        if (on) start(context) else stop()
    }

    @Synchronized
    fun start(context: Context) {
        load(context)
        if (!_on.value || server != null) return
        val app = context.applicationContext
        val dir = RoadClips.dir(app)
        val signs = SignReader.dir(app)
        val socket = try {
            ServerSocket(PORT)
        } catch (e: IOException) {
            Log.w(TAG, "port $PORT unavailable", e)
            return
        }
        server = socket
        thread(name = "clip-server", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (e: IOException) { break }
                pool.execute { serve(client, app, dir, signs) }
            }
        }
    }

    @Synchronized
    fun stop() {
        server?.close()
        server = null
    }

    /** Where the computer fetches from, key included, or null while Tailscale is off on this phone. */
    fun address(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.firstOrNull { onTailnet(it) }
    }.getOrNull()?.let { "http://${it.hostAddress}:$PORT/clips?k=$key" }

    /** Tailscale's 100.64.0.0/10 on a VPN interface; a mobile network's own address in that range is not on a tun. */
    private fun onTailnet(address: InetAddress): Boolean {
        val b = address.address
        if (b.size != 4 || b[0] != 100.toByte() || (b[1].toInt() and 0xC0) != 64) return false
        return NetworkInterface.getByInetAddress(address)?.name?.startsWith("tun") == true
    }

    private fun serve(client: Socket, context: Context, dir: File, signs: File) = client.use { s ->
        runCatching {
            // The phone's Wi-Fi, hotspot or mobile network never get an answer, not even a refusal.
            if (!onTailnet(s.localAddress)) return@runCatching
            s.soTimeout = READ_TIMEOUT_MS
            val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val line = input.readLine() ?: return@runCatching
            var range: String? = null
            for (i in 0 until MAX_HEADERS) {
                val header = input.readLine()
                if (header.isNullOrEmpty()) break
                if (header.startsWith("range:", ignoreCase = true)) range = header.substringAfter(':').trim()
            }
            val out = s.getOutputStream().buffered()
            when (val r = route(line, range, key, dir, signs)) {
                is Route.Listing -> {
                    val body = JSONArray(r.clips.map { JSONObject().put("name", it.name).put("bytes", File(it, "clip.mp4").length()) })
                        .toString().toByteArray()
                    head(out, "200 OK", "application/json", body.size.toLong())
                    out.write(body)
                }
                is Route.Signs -> {
                    val body = JSONArray(r.faces.map { it.name }).toString().toByteArray()
                    head(out, "200 OK", "application/json", body.size.toLong())
                    out.write(body)
                }
                Route.Cameras, Route.RoadWatch -> {
                    val json = if (r == Route.Cameras) CameraReport.json(context) else RoadStats.json()
                    val body = json.toString(2).toByteArray()
                    head(out, "200 OK", "application/json", body.size.toLong())
                    out.write(body)
                }
                is Route.Send -> {
                    val length = r.file.length()
                    val type = when {
                        r.file.name.endsWith(".json") -> "application/json"
                        r.file.name.endsWith(".png") -> "image/png"
                        else -> "video/mp4"
                    }
                    if (r.from > 0) {
                        head(out, "206 Partial Content", type, length - r.from, "Content-Range: bytes ${r.from}-${length - 1}/$length")
                    } else {
                        head(out, "200 OK", type, length)
                    }
                    FileInputStream(r.file).use { it.channel.position(r.from); it.copyTo(out) }
                }
                is Route.Refused -> head(out, r.status, "text/plain", 0)
            }
            out.flush()
        }.onFailure { Log.d(TAG, "fetch dropped", it) }
    }

    private fun head(out: OutputStream, status: String, type: String, length: Long, extra: String? = null) {
        val lines = listOfNotNull(
            "HTTP/1.1 $status", "Content-Type: $type", "Content-Length: $length", "Accept-Ranges: bytes", "Connection: close", extra
        )
        out.write((lines.joinToString("\r\n") + "\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
    }

    internal sealed interface Route {
        data class Listing(val clips: List<File>) : Route
        data object Cameras : Route
        data object RoadWatch : Route
        data class Signs(val faces: List<File>) : Route
        data class Send(val file: File, val from: Long) : Route
        data class Refused(val status: String) : Route
    }

    internal fun route(requestLine: String, range: String?, key: String, dir: File, signs: File? = null): Route {
        val parts = requestLine.split(' ')
        if (parts.size < 2 || parts[0] != "GET") return Route.Refused("405 Method Not Allowed")
        val path = parts[1].substringBefore('?')
        val given = parts[1].substringAfter('?', "").split('&').firstOrNull { it.startsWith("k=") }?.removePrefix("k=")
        if (key.isEmpty() || given == null || !MessageDigest.isEqual(given.toByteArray(), key.toByteArray())) {
            return Route.Refused("403 Forbidden")
        }
        if (path == "/cameras") return Route.Cameras
        if (path == "/roadwatch") return Route.RoadWatch
        if (signs != null && (path == "/signs" || path.startsWith("/signs/"))) {
            // The faces Road watch tried to read; a name from the listing, never a path.
            val faces = signs.listFiles { f -> f.isFile && f.name.endsWith(".png") }?.sortedBy { it.name } ?: emptyList()
            if (path == "/signs") return Route.Signs(faces)
            val face = faces.firstOrNull { it.name == path.removePrefix("/signs/") } ?: return Route.Refused("404 Not Found")
            return Route.Send(face, 0)
        }
        // Both files, or the clip is still being built (RoadRecorder.build) or broken.
        val clips = dir.listFiles { f -> File(f, "clip.mp4").isFile && File(f, "clip.json").isFile }?.sortedBy { it.name } ?: emptyList()
        if (path == "/clips") return Route.Listing(clips)
        // Only a listed clip's own two files: the path picks among them, it never names a file itself.
        val bits = path.removePrefix("/clips/").split('/')
        val clip = clips.firstOrNull { it.name == bits.getOrNull(0) }
        val name = bits.getOrNull(1)
        if (!path.startsWith("/clips/") || bits.size != 2 || clip == null || name !in FILES) return Route.Refused("404 Not Found")
        val file = File(clip, name!!)
        if (!file.isFile) return Route.Refused("404 Not Found")
        val from = range?.let { Regex("bytes=(\\d+)-").matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
        if (from > 0 && from >= file.length()) return Route.Refused("416 Range Not Satisfiable")
        return Route.Send(file, from)
    }
}
