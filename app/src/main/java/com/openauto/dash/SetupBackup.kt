package com.openauto.dash

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/*
 * The driver's whole setup in one file: dashboards, theme, settings, places,
 * wheel buttons, car profile, all of Dashwheel's own preferences. Saved to a
 * file the driver picks (a USB stick, the unit), restored from one, on this
 * unit after a reset or on another. What belongs to this device or is a
 * secret stays out ([SKIP_FILES], [SKIP_KEYS]).
 */
internal object SetupBackup {
    const val FORMAT = 1
    const val MIME = "application/json"

    /**
     * Preferences never saved: the phone pairings (the phone holds the other
     * half, and a copy on a USB stick would let anyone link to it), the
     * ignition and briefing clocks, the updater's and PMPatch's state (this
     * unit's own past), and the AI's answers cache (asked again when needed).
     */
    private val SKIP_FILES = setOf(
        "phone_link", "car_power", "startup_briefing", "updates", "head_unit_phone",
        "compile_after_update", "pmpatch", "pmpatch_steps", "ai_diagnoses",
        // The second screen's pairing secret, like the phone's; and this unit's own
        // system state: the navigation bar policy it found, the widget ids it let go of.
        "display_link", "docked_nav_bar", "widget_host",
        // How this unit's floating home screen was dealt with (StuckSplit).
        "floating_home"
    )

    /** Keys left out of a file that is otherwise saved: the Gemini key is a secret. */
    private val SKIP_KEYS = mapOf("ai_prefs" to setOf("api_key", "key_from_code"))

    /** Every preference file of the app, by name, as it is now. */
    private fun readAll(context: Context): Map<String, Map<String, Any?>> {
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        val names = dir.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".xml") }.map { it.removeSuffix(".xml") }
        return names.filter { it !in SKIP_FILES }.associateWith { name ->
            val skip = SKIP_KEYS[name].orEmpty()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.filterKeys { it !in skip }
        }
    }

    /** The setup as the file's text. */
    fun export(context: Context): String = encode(readAll(context), BuildConfig.VERSION_NAME).toString(2)

    /**
     * Replaces the preferences saved in [text] (the others stay as they are).
     * The number of files restored; a failure when the text isn't a setup.
     */
    fun restore(context: Context, text: String): Result<Int> = runCatching {
        val files = decode(text)
        for ((name, values) in files) {
            if (name in SKIP_FILES) continue
            val skip = SKIP_KEYS[name].orEmpty()
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            // The secrets this file never carries stay as they are on this unit.
            val kept = prefs.all.filterKeys { it in skip }
            val edit = prefs.edit().clear()
            (kept + values.filterKeys { it !in skip }).forEach { (key, value) -> put(edit, key, value) }
            check(edit.commit()) { "could not write $name" }
        }
        files.size
    }

    /** Starts the launcher afresh, so every part reads the restored setup (they keep it in memory). */
    fun restart(context: Context) {
        val intent = Intent().setComponent(FloatingHome.component(context)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        Process.killProcess(Process.myPid())
    }

    /** Writes the setup to [uri] (a file the driver picked). */
    fun save(context: Context, uri: Uri): Result<Unit> = runCatching {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("no output")
        out.use { it.write(export(context).toByteArray()) }
    }

    /** Reads a setup from [uri]. */
    fun load(context: Context, uri: Uri): Result<String> = runCatching {
        val input = context.contentResolver.openInputStream(uri) ?: error("no input")
        input.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /**
     * Where the setup goes on a unit with no file picker: the app's own folder
     * on the storage (Android/data/…/files), where a file manager or a PC reaches it.
     */
    fun fallbackFile(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "dashwheel-setup.json")

    // --- The file ----------------------------------------------------------------------

    private fun put(edit: android.content.SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is String -> edit.putString(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> Unit
        }
    }

    /**
     * The preferences as JSON. Each value carries its type ("b", "i", "l",
     * "f", "s", "set"): JSON alone can't tell an Int from a Long, and the
     * preferences must come back exactly as they were written.
     */
    internal fun encode(files: Map<String, Map<String, Any?>>, version: String): JSONObject {
        val prefs = JSONObject()
        for ((name, values) in files) {
            val o = JSONObject()
            for ((key, value) in values) {
                val (type, v) = when (value) {
                    is Boolean -> "b" to value
                    is Int -> "i" to value
                    is Long -> "l" to value
                    is Float -> "f" to value.toDouble()
                    is String -> "s" to value
                    is Set<*> -> "set" to JSONArray(value.filterIsInstance<String>().sorted())
                    else -> continue
                }
                o.put(key, JSONObject().put("t", type).put("v", v))
            }
            prefs.put(name, o)
        }
        return JSONObject().put("dashwheel_setup", FORMAT).put("version", version).put("prefs", prefs)
    }

    /** The preferences in a setup file; throws when it is not one. */
    internal fun decode(text: String): Map<String, Map<String, Any?>> {
        val root = JSONObject(text)
        require(root.optInt("dashwheel_setup", 0) in 1..FORMAT) { "not a Dashwheel setup" }
        val prefs = root.getJSONObject("prefs")
        return prefs.keys().asSequence().associateWith { name ->
            val o = prefs.getJSONObject(name)
            o.keys().asSequence().mapNotNull { key ->
                val e = o.getJSONObject(key)
                val value: Any? = when (e.optString("t")) {
                    "b" -> e.getBoolean("v")
                    "i" -> e.getInt("v")
                    "l" -> e.getLong("v")
                    "f" -> e.getDouble("v").toFloat()
                    "s" -> e.getString("v")
                    "set" -> e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    else -> return@mapNotNull null
                }
                key to value
            }.toMap()
        }
    }
}
