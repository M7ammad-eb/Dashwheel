package com.openauto.dash

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/*
 * A dashboard, and its look if the driver wants, as one short code
 * ("DW1:…") to pass on: a QR code, a forum post, a file on a USB stick, or
 * the phone (the companion scans the code or takes it from the share menu
 * and sends it to the car). The code holds the tiles and the look only:
 * never a place, a key, a name or the Android widgets bound to this unit.
 */

/** The look a dashboard is shared with: the skin and how the bar reads. */
data class SharedLook(
    val mode: DashThemeMode,
    val appearance: DashAppearance,
    val effects: DashEffects,
    /** The side rail; null follows the skin. */
    val rail: Boolean?,
    val tabs: Boolean,
    /** The bar's readouts; null keeps the skin's own bar. */
    val barItems: List<BarItem>?,
    val barLook: BarLook
)

/** One dashboard as shared: its tiles laid out for a wide or [upright] screen. */
data class SharedDashboard(
    val name: String?,
    val upright: Boolean,
    val items: List<DashboardItem>,
    val look: SharedLook? = null
)

object DashShare {
    const val PREFIX = "DW1:"
    private const val VERSION = 1
    /** The variant the shared page is parsed under: never one of the store's own. */
    private val CODE = Regex("DW1:[A-Za-z0-9_-]+")

    /** Only what another unit can show: an Android widget is bound to this one. */
    fun shareable(items: List<DashboardItem>): List<DashboardItem> = items.filterNot { it is DashboardItem.SystemWidget }

    fun encode(share: SharedDashboard): String {
        val json = JSONObject()
            .put("v", VERSION)
            // The layout schema the cells are in: a code from before the finer grid has none, and is read as v1.
            .put("sv", DashboardStore.SCHEMA_VERSION)
            .put("up", share.upright)
            .put("p", JSONObject(DashboardStore.serializePagesPlain(listOf(shareable(share.items)))).getJSONArray("pages").getJSONArray(0))
        share.name?.takeIf { it.isNotBlank() }?.let { json.put("n", it.take(MAX_NAME)) }
        share.look?.let { l ->
            json.put("look", JSONObject()
                .put("m", l.mode.name).put("a", l.appearance.name).put("e", l.effects.name)
                .put("t", l.tabs).put("bl", l.barLook.name)
                .apply {
                    l.rail?.let { put("r", it) }
                    l.barItems?.let { put("b", JSONArray(it.map { i -> i.name })) }
                })
        }
        return PREFIX + base64(deflate(json.toString().toByteArray(Charsets.UTF_8)))
    }

    /** The first code in [text] (a pasted message, a file, a scanned QR), or null when there is none that reads. */
    fun decode(text: String): SharedDashboard? = runCatching {
        val code = CODE.find(text)?.value ?: return null
        val json = JSONObject(String(inflate(unbase64(code.removePrefix(PREFIX))), Charsets.UTF_8))
        if (json.optInt("v") > VERSION) return null
        val page = json.getJSONArray("p")
        val items = DashboardStore.parsePagesPlain(JSONObject().put("schema", json.optInt("sv", 1)).put("pages", JSONArray().put(page)).toString())
            ?.firstOrNull() ?: return null
        val look = json.optJSONObject("look")?.let { l ->
            SharedLook(
                mode = enumOr(l.optString("m"), DashThemeMode.ORIGINAL),
                appearance = enumOr(l.optString("a"), DashAppearance.AUTO),
                effects = enumOr(l.optString("e"), DashEffects.FULL),
                rail = if (l.has("r")) l.optBoolean("r") else null,
                tabs = l.optBoolean("t"),
                barItems = l.optJSONArray("b")?.let { a -> (0 until a.length()).mapNotNull { i -> BarItem.entries.firstOrNull { it.name == a.optString(i) } } },
                barLook = enumOr(l.optString("bl"), BarLook.COMPACT)
            )
        }
        SharedDashboard(
            name = json.optString("n").takeIf { it.isNotBlank() }?.take(MAX_NAME),
            upright = json.optBoolean("up"),
            items = shareable(items),
            look = look
        )
    }.getOrNull()

    /** The apps the dashboard opens that aren't on this unit: their tiles stay, and say so. */
    fun missingApps(items: List<DashboardItem>, installed: Set<String>): List<String> = items.flatMap { item ->
        when (item) {
            is DashboardItem.AppShortcut -> listOf(item.packageName)
            is DashboardItem.AppWindow -> listOf(item.packageName)
            is DashboardItem.SplitPair -> listOf(item.primaryPackage, item.secondaryPackage)
            is DashboardItem.LaunchBar -> item.packages
            else -> emptyList()
        }
    }.filter { it !in installed }.distinct()

    /** [share]'s tiles for this screen's direction: laid out again when it was made for the other one. */
    fun itemsFor(share: SharedDashboard, upright: Boolean): List<DashboardItem> =
        if (share.upright == upright) share.items
        else DashboardStore.reflow(List(DashboardStore.PAGE_COUNT) { if (it == DashboardStore.CENTER) share.items else emptyList() })[DashboardStore.CENTER]

    /** The look on this unit now, to share with a dashboard. */
    fun currentLook(context: Context): SharedLook {
        BarItems.setContext(context)
        return SharedLook(
            mode = DashThemeStore.load(context),
            appearance = DashThemeStore.loadAppearance(context),
            effects = DashThemeStore.loadEffects(context),
            rail = DashThemeStore.loadRail(context),
            tabs = DashThemeStore.loadTabs(context),
            barItems = BarItems.items.value,
            barLook = BarItems.look.value
        )
    }

    // A code the phone sent ([PhoneLink]), waiting for the dashboard to show it.
    private val _incoming = MutableStateFlow<String?>(null)
    val incoming: StateFlow<String?> = _incoming

    fun receive(code: String) {
        _incoming.value = code
    }

    fun taken() {
        _incoming.value = null
    }

    private const val MAX_NAME = 40

    private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback

    private fun deflate(bytes: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true).apply { setInput(bytes); finish() }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun inflate(bytes: ByteArray): ByteArray {
        val i = Inflater(true).apply { setInput(bytes) }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!i.finished()) {
            val n = i.inflate(buf)
            if (n == 0 && (i.needsInput() || i.needsDictionary())) break
            out.write(buf, 0, n)
            // A code can't be that big: a crafted one never fills the memory.
            if (out.size() > MAX_JSON) error("too big")
        }
        i.end()
        return out.toByteArray()
    }

    private const val MAX_JSON = 256 * 1024

    // java.util.Base64, not android.util: the codec is unit-tested on the JVM.
    private fun base64(bytes: ByteArray) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun unbase64(text: String) = java.util.Base64.getUrlDecoder().decode(text)
}
