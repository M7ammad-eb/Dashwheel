package com.openauto.dash

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenStreetMap's Overpass servers, for the speed limits, the speed cameras
 * and the fuel stations' names. The main server is shared by everyone and
 * goes down for hours at a time; the day it did, both speed tiles went blank
 * at once and a tester took it for the update. So a query goes to the first
 * server that answers ([SERVERS], the main one first), a server that failed
 * is left alone for a while ([SKIP_MS]), and whether the last query found
 * any server at all is said ([down]) so a tile can say so too. A server of
 * the driver's own ([MapServers.Servers.overpass]) goes before them all.
 */
internal object Overpass {
    private const val TAG = "Overpass"

    /** The main server, then public mirrors of the same data. */
    internal val SERVERS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    )

    /** A server that failed is not asked again for this long, while another answers. */
    internal const val SKIP_MS = 5 * 60_000L

    /** When each server last failed (elapsedRealtime), by address; absent or 0 for never. */
    private val failedAt = HashMap<String, Long>()
    @Volatile private var preferred: String? = null

    /**
     * The shared client, given longer to read: the queries ask the server for
     * up to 25 s of work ([timeout:25]), and a client that hangs up at 15 s
     * marks a server that was about to answer as failed, then sends the same
     * heavy query to the next one, which is as slow for the same reason.
     */
    private val client by lazy { Http.client.newBuilder().readTimeout(35, TimeUnit.SECONDS).build() }
    /** For a whole country's cameras ([SpeedCameraStore.download]): minutes of work, asked for once a week. */
    private val patientClient by lazy { Http.client.newBuilder().readTimeout(170, TimeUnit.SECONDS).build() }

    private val _down = MutableStateFlow(false)
    /** The last query found no server answering: the tiles' blank is the server's, not the road's. */
    val down: StateFlow<Boolean> = _down

    /**
     * Runs [query] (Overpass QL) on the first server that answers and gives
     * back its JSON; throws when none does. Blocking: from an IO thread.
     * A [patient] query is given minutes, not seconds, to be answered.
     */
    /** A coordinate as Overpass QL wants it: plain decimals (Double.toString gives "1.0E-4" near zero, which it refuses). */
    fun coord(deg: Double): String = String.format(java.util.Locale.US, "%.6f", deg)

    fun query(query: String, patient: Boolean = false): JSONObject {
        var last: Throwable? = null
        val servers = servers(MapServers.current.value.overpass)
        val failed = synchronized(failedAt) { HashMap(failedAt) }
        for (server in order(servers, preferred, failed, SystemClock.elapsedRealtime())) {
            val result = runCatching { ask(server, query, if (patient) patientClient else client) }
            result.onSuccess {
                synchronized(failedAt) { failedAt.remove(server) }
                if (preferred != server) Log.i(TAG, "answered by $server")
                preferred = server
                _down.value = false
                return it
            }
            last = result.exceptionOrNull()
            synchronized(failedAt) { failedAt[server] = SystemClock.elapsedRealtime() }
            Log.w(TAG, "$server: ${last?.message}")
        }
        _down.value = true
        throw IllegalStateException("no Overpass server answered: ${last?.message}", last)
    }

    /** The servers to ask: the driver's own first when there is one, then the public ones. */
    internal fun servers(own: String): List<String> {
        val custom = own.trim()
        return if (custom.isEmpty() || custom in SERVERS) SERVERS else listOf(custom) + SERVERS
    }

    private fun ask(server: String, query: String, client: okhttp3.OkHttpClient): JSONObject {
        val request = Request.Builder().url(server)
            .header("User-Agent", OVERPASS_AGENT)
            .post(FormBody.Builder().add("data", query).build()).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return JSONObject(resp.body?.string() ?: "{}")
        }
    }

    /**
     * The [servers] to try, in order: from [preferred] round the list, the
     * ones that have not failed within [SKIP_MS] of [now] first; when every
     * one has, all of them again, since nothing else is left to try.
     */
    internal fun order(servers: List<String>, preferred: String?, failedAt: Map<String, Long>, now: Long): List<String> {
        val start = servers.indexOf(preferred).coerceAtLeast(0)
        val round = servers.indices.map { servers[(start + it) % servers.size] }
        val fresh = round.filter { (failedAt[it] ?: 0L).let { t -> t == 0L || now - t >= SKIP_MS } }
        return fresh.ifEmpty { round }
    }
}

/** How Dashwheel names itself to OpenStreetMap's servers, as their usage policy asks. */
internal const val OVERPASS_AGENT = "Dashwheel/1.0 (https://github.com/deviloufr-ai/Dashwheel)"
