package com.openauto.dash

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Details of the newest release found on GitHub. */
data class UpdateInfo(
    val versionName: String,
    val buildNumber: Long,
    val apkUrl: String,
    val notes: String,
    /** Release day, "2026-10-29"; empty when GitHub gave none. */
    val date: String = ""
)

/** Update lifecycle observed by the UI. */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    /** A newer build exists and the driver has not put it off. */
    data class Available(val info: UpdateInfo) : UpdateStatus
    /** A newer build exists but the driver said "later": only Settings still offers it. */
    data class Dismissed(val info: UpdateInfo) : UpdateStatus
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateStatus
    /** Downloaded and waiting for the go-ahead to install. */
    data class Ready(val info: UpdateInfo, val file: File) : UpdateStatus
    data object Installing : UpdateStatus
    data class Error(@StringRes val messageRes: Int) : UpdateStatus
}

/** The update a status is about, if any. */
val UpdateStatus.updateInfo: UpdateInfo?
    get() = when (this) {
        is UpdateStatus.Available -> info
        is UpdateStatus.Dismissed -> info
        is UpdateStatus.Downloading -> info
        is UpdateStatus.Ready -> info
        else -> null
    }

/**
 * In-app updater that tracks the installed version against the latest GitHub
 * Release and downloads/installs a newer APK.
 *
 * Two channels: stable follows GitHub's "latest" release (a beta promoted by
 * hand); beta ([beta]) takes the newest build of main, pre-releases included,
 * as soon as it passed its tests.
 *
 * Version tracking: the installed [currentVersionCode] comes from the APK
 * (set by CI from main's history); the latest build number is parsed from
 * the release tag (e.g. `v1.0.42` → 42). A newer build number means an update.
 *
 * Note: Android always shows its own install confirmation, and a downloaded APK
 * can only replace the installed one if both are signed with the same key.
 */
class UpdateManager(private val context: Context) {

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    val currentVersionName: String = BuildConfig.VERSION_NAME
    val currentVersionCode: Long = BuildConfig.VERSION_CODE.toLong()

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Where downloads run, whoever asked for them (see [download]). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var downloadJob: Deferred<Boolean>? = null
    private var downloadBuild = -1L

    /**
     * Queries GitHub for the latest release and updates [status]. The Play
     * edition never checks: its updates come from Google Play, and [status]
     * stays [UpdateStatus.Idle] for good, so no row, dot or prompt ever shows.
     */
    suspend fun checkForUpdate() {
        if (Edition.play) return
        // A download under way or done keeps its state: the check is for news.
        val keep = _status.value
        if (keep is UpdateStatus.Downloading || keep is UpdateStatus.Ready || keep is UpdateStatus.Installing) return
        _status.value = UpdateStatus.Checking
        val info = withContext(Dispatchers.IO) { fetchLatestRelease() }
        val downloaded = info?.let { downloadedFile(it) }
        if (info != null && info.buildNumber <= currentVersionCode) withContext(Dispatchers.IO) { forgetDownloads() }
        _status.value = when {
            info == null -> UpdateStatus.Error(R.string.sys_update_check_failed)
            info.buildNumber <= currentVersionCode -> UpdateStatus.UpToDate
            downloaded != null -> UpdateStatus.Ready(info, downloaded)
            prefs.getLong(KEY_DISMISSED, -1L) == info.buildNumber -> UpdateStatus.Dismissed(info)
            else -> UpdateStatus.Available(info)
        }
    }

    /** The APK of [info] if an earlier download finished and is still there. */
    private fun downloadedFile(info: UpdateInfo): File? {
        if (prefs.getLong(KEY_DOWNLOADED, -1L) != info.buildNumber) return null
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_NAME)
        return file.takeIf { it.exists() && it.length() > 0L }
    }

    /**
     * True on a connection that costs nothing per byte (home Wi-Fi rather
     * than a phone's hotspot): the only kind an update downloads on by itself.
     */
    fun onUnmeteredNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** The newest release of the driver's channel: GitHub's "latest" (stable), or the newest build of main (beta). */
    private fun fetchLatestRelease(): UpdateInfo? {
        val base = "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases"
        if (!beta(context)) return fetchJson("$base/latest")?.let { runCatching { releaseInfo(JSONObject(it)) }.getOrNull() }
        val list = fetchJson("$base?per_page=30")?.let { runCatching { org.json.JSONArray(it) }.getOrNull() } ?: return null
        return (0 until list.length())
            .mapNotNull { list.optJSONObject(it) }
            .filter { !it.optBoolean("draft") && isMainTag(it.optString("tag_name")) }
            .mapNotNull { releaseInfo(it) }
            .maxByOrNull { it.buildNumber }
    }

    /** A release's version, notes and launcher APK; null when it has no APK for this edition. */
    private fun releaseInfo(json: JSONObject): UpdateInfo? {
        val tag = json.optString("tag_name")
        val buildNumber = parseBuildNumber(tag) ?: return null
        val apkUrl = firstApkAssetUrl(json) ?: return null
        return UpdateInfo(
            versionName = tag, buildNumber = buildNumber, apkUrl = apkUrl, notes = json.optString("body"),
            date = json.optString("published_at").take(10)
        )
    }

    /**
     * The way back after a bad build: the channel's newest builds older than
     * this one, newest first (stable leaves the pre-releases out). Empty when
     * GitHub did not answer or nothing older is published.
     */
    suspend fun previousReleases(): List<UpdateInfo> = withContext(Dispatchers.IO) {
        if (Edition.play) return@withContext emptyList()
        val base = "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases"
        val list = fetchJson("$base?per_page=30")?.let { runCatching { org.json.JSONArray(it) }.getOrNull() } ?: return@withContext emptyList()
        val beta = beta(context)
        (0 until list.length())
            .mapNotNull { list.optJSONObject(it) }
            .filter { !it.optBoolean("draft") && (beta || !it.optBoolean("prerelease")) && isMainTag(it.optString("tag_name")) }
            .mapNotNull { releaseInfo(it) }
            .filter { it.buildNumber < currentVersionCode }
            .sortedByDescending { it.buildNumber }
            .take(PREVIOUS_COUNT)
    }

    /**
     * Goes back to [info], an older build ([previousReleases]). Android refuses
     * an older APK over a newer one, so the file lands in the shared
     * Download/Dashwheel folder (where a shell user can read it too) and a
     * shell installs it with `pm install -d`, which keeps the settings and ends
     * this process. Without a shell [status] stays [UpdateStatus.Ready] on
     * that file: the driver uninstalls Dashwheel, then opens it by hand.
     */
    suspend fun installPrevious(info: UpdateInfo) {
        if (Edition.play) return
        if (!download(info, shared = true)) return
        val file = (_status.value as? UpdateStatus.Ready)?.file ?: return
        if (!withContext(Dispatchers.IO) { isOurUpdate(file, older = true) }) {
            Log.w(TAG, "the downloaded file is not this app: dropped")
            withContext(Dispatchers.IO) { runCatching { file.delete() } }
            _status.value = UpdateStatus.Error(R.string.sys_update_download_failed)
            return
        }
        if (!PrivilegedShell.access.value.shell) return
        _status.value = UpdateStatus.Installing
        val out = runCatching { DockShell.shell(context, "pm install -r -d '${file.path}'") }.getOrElse { it.message.orEmpty() }
        // A success replaces the app and ends this process; still here, it was refused.
        if (out.contains("Success")) return
        Log.w(TAG, "going back to ${info.versionName} refused: $out")
        _status.value = UpdateStatus.Error(R.string.update_previous_failed)
    }

    private fun fetchJson(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val endpoint = URL(url)
            connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "OpenAutoDash-Updater")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.close()
                return null
            }

            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun firstApkAssetUrl(release: JSONObject): String? {
        val assets = release.optJSONArray("assets") ?: return null
        val named = buildList {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                add(asset.optString("name") to asset.optString("browser_download_url"))
            }
        }
        return pickLauncherApk(named)
    }

    /** True if the app may install APKs (Android 8+ requires a per-app grant). */
    fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Sends the user to enable "install unknown apps" for this app. */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // A ROM stripped of that settings screen must not take the launcher down with the Update tap.
            val shown = context.launchSafely(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            if (!shown) _status.value = UpdateStatus.Error(R.string.sys_update_install_failed)
        }
    }

    /** Downloads the update APK (unless it already is), then launches the system installer. */
    suspend fun downloadAndInstall(info: UpdateInfo) {
        if (Edition.play) return
        val file = downloadedFile(info) ?: (if (download(info)) downloadedFile(info) else null) ?: return
        install(file)
    }

    /** Launches the system installer on a downloaded [file]. */
    fun install(file: File) {
        if (Edition.play) return
        val ready = _status.value as? UpdateStatus.Ready
        // The update is on offer again, to try once more, or nothing is if it was lost.
        fun offerAgain() {
            _status.value = ready?.takeIf { it.file.exists() } ?: UpdateStatus.Idle
        }
        _status.value = UpdateStatus.Installing
        scope.launch {
            if (!withContext(Dispatchers.IO) { isOurUpdate(file) }) {
                Log.w(TAG, "the downloaded file is not this app's update: dropped")
                withContext(Dispatchers.IO) { runCatching { file.delete() } }
                prefs.edit().remove(KEY_DOWNLOADED).apply()
                _status.value = UpdateStatus.Error(R.string.sys_update_download_failed)
                return@launch
            }
            if (!launchInstaller(file)) return@launch offerAgain()
            // An install that goes through replaces the app and ends this process.
            // Still here a while later: Android's own confirmation was cancelled, or
            // the install refused. "Installing" used to stay for good then (the unit
            // sleeps rather than restarts), with no further check and no way to retry.
            delay(INSTALL_WAIT_MS)
            if (_status.value is UpdateStatus.Installing) offerAgain()
        }
    }

    /**
     * Whether [apk] is this app, in a newer build, signed as this one is. The
     * system's installer checks the signature too, but not on a unit where
     * PMPatch switched that check off, and the download waits in a folder
     * other apps can write to. A debug build, signed with another key than
     * the releases, is let through to update to one as before.
     */
    @Suppress("DEPRECATION") // GET_SIGNATURES: the one flag Android 10 reads an APK file's signers with.
    private fun isOurUpdate(apk: File, older: Boolean = false): Boolean = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES) ?: return false
        if (info.packageName != context.packageName) return false
        if (!older && PackageInfoCompat.getLongVersionCode(info) <= currentVersionCode) return false
        val theirs = info.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        // Not read (a system that doesn't tell): left to the installer's own check.
        if (BuildConfig.DEBUG || theirs.isEmpty()) return true
        val ours = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        theirs == ours
    }.getOrDefault(false)

    /**
     * Downloads [info]'s APK into the app's own downloads folder; [status]
     * follows the progress and ends [UpdateStatus.Ready] or an error. True on success.
     *
     * The download runs in the updater's own scope, not the caller's: the
     * dashboard starts it from an effect keyed on [status], which the download
     * itself changes, and the effect's restart used to cancel it a second in,
     * remove it from the system's downloader and leave "Downloading 0 %" for
     * good. A caller that goes away now only stops waiting. A second call for
     * the same build joins the download under way.
     *
     * [shared]: into the shared Download/Dashwheel folder instead, named
     * after the build, to outlive the app ([installPrevious]).
     */
    suspend fun download(info: UpdateInfo, shared: Boolean = false): Boolean {
        if (Edition.play) return false
        val running = downloadJob?.takeIf { it.isActive && downloadBuild == info.buildNumber }
        val job = running ?: scope.async { runDownload(info, shared) }.also {
            downloadJob = it
            downloadBuild = info.buildNumber
        }
        return job.await()
    }

    @Suppress("DEPRECATION") // getExternalStoragePublicDirectory: the path the system's downloader writes the shared folder at.
    private suspend fun runDownload(info: UpdateInfo, shared: Boolean): Boolean {
        val publicName = "Dashwheel/Dashwheel-${info.versionName}.apk"
        val apkFile = if (shared) File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), publicName)
        else File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_NAME)
        // shortcut: a leftover in the shared folder another app wrote cannot be deleted on Android 10+, and the downloader then refuses; a new name per attempt if that ever shows.
        withContext(Dispatchers.IO) { if (apkFile.exists()) apkFile.delete() }
        prefs.edit().remove(KEY_DOWNLOADED).apply()

        withContext(Dispatchers.IO) { forgetDownloads() }
        // A unit with the system's downloader switched off, or no storage to
        // write to, refuses here: a failed download, not a crash of the launcher
        // at every start while an update is on offer.
        val downloadManager: DownloadManager
        val downloadId: Long
        try {
            downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(info.apkUrl))
                .setTitle("Dashwheel ${info.versionName}")
                .setDescription(context.getString(R.string.sys_update_downloading))
                .setMimeType("application/vnd.android.package-archive")
                // The progress only: the dashboard itself says when it is ready.
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            if (shared) request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, publicName)
            else request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_NAME)
            downloadId = downloadManager.enqueue(request)
            activeDownloads += downloadId
        } catch (e: RuntimeException) {
            Log.w(TAG, "the download could not start", e)
            _status.value = UpdateStatus.Error(R.string.sys_update_download_failed)
            return false
        }
        _status.value = UpdateStatus.Downloading(info, 0)

        var success = false
        try {
            success = withContext(Dispatchers.IO) {
                withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) { awaitDownload(downloadManager, info, downloadId) } ?: false
            }
        } finally {
            activeDownloads -= downloadId
            if (!success) {
                // Failed, stuck or abandoned: nothing left queued in the system's
                // downloader, and never a percentage left standing still.
                downloadManager.remove(downloadId)
                if (_status.value is UpdateStatus.Downloading) _status.value = UpdateStatus.Error(R.string.sys_update_download_failed)
            }
        }
        if (!success) {
            _status.value = UpdateStatus.Error(R.string.sys_update_download_failed)
            return false
        }
        prefs.edit().putLong(KEY_DOWNLOADED, info.buildNumber).apply()
        _status.value = UpdateStatus.Ready(info, apkFile)
        return true
    }

    /**
     * Every earlier download of an update taken out of the system's downloader,
     * files with them. Each one stayed there with its notification, and the
     * downloader posted them all again at every step of the next download: 83
     * of them on the unit, the notification shade flooded while it ran.
     */
    private fun forgetDownloads() {
        runCatching {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            // A query without a filter lists this app's own downloads only.
            // Not one another instance is downloading now: the "previous version" row and the
            // dashboard's updater each have their own, and one took the other's download away.
            val ids = downloadManager.query(DownloadManager.Query())?.use { cursor ->
                val column = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                buildList { while (cursor.moveToNext()) add(cursor.getLong(column)) }
            }.orEmpty().filter { it !in activeDownloads }
            if (ids.isNotEmpty()) downloadManager.remove(*ids.toLongArray())
        }
    }

    /**
     * Follows the download to its end. A download paused or pending (no
     * network, the server gone quiet) that makes no progress for [STALL_MS]
     * counts as failed rather than showing the same percentage forever.
     */
    private suspend fun awaitDownload(downloadManager: DownloadManager, info: UpdateInfo, id: Long): Boolean {
        var lastBytes = -1L
        var lastProgressAt = SystemClock.elapsedRealtime()
        while (true) {
            val query = DownloadManager.Query().setFilterById(id)
            downloadManager.query(query)?.use { cursor ->
                if (!cursor.moveToFirst()) return false
                when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> return true
                    DownloadManager.STATUS_FAILED -> return false
                    else -> {
                        val soFar =
                            cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total =
                            cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        if (total > 0) {
                            _status.value = UpdateStatus.Downloading(info, ((soFar * 100) / total).toInt())
                        }
                        if (soFar != lastBytes) {
                            lastBytes = soFar
                            lastProgressAt = SystemClock.elapsedRealtime()
                        } else if (SystemClock.elapsedRealtime() - lastProgressAt > STALL_MS) {
                            return false
                        }
                    }
                }
            } ?: return false
            // A content-provider query each time: once a second is plenty for a percentage.
            delay(1_000)
        }
    }

    /** False when the system's installer could not be opened on [apkFile]. */
    private fun launchInstaller(apkFile: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }.onFailure { Log.w(TAG, "the installer could not be opened", it) }.isSuccess

    /**
     * "Later": the update on offer stops asking (no dot, no prompt) until a
     * newer build comes along; Settings still lists it. A check's own outcome
     * (up to date, failed) just clears.
     */
    fun dismiss() {
        val info = _status.value.updateInfo
        if (info == null) {
            _status.value = UpdateStatus.Idle
            return
        }
        prefs.edit().putLong(KEY_DISMISSED, info.buildNumber).apply()
        _status.value = UpdateStatus.Dismissed(info)
    }

    /** Offers a dismissed update again (Settings → Advanced). */
    fun offerAgain() {
        val d = _status.value as? UpdateStatus.Dismissed ?: return
        prefs.edit().remove(KEY_DISMISSED).apply()
        _status.value = UpdateStatus.Available(d.info)
    }

    companion object {
        private const val TAG = "UpdateManager"
        /** Downloads under way in this process, by any instance: [forgetDownloads] leaves them alone. */
        private val activeDownloads = java.util.concurrent.CopyOnWriteArraySet<Long>()
        private const val PREFS = "updates"
        /** Android's confirmation and the install itself, before an update still not installed is offered again. */
        private const val INSTALL_WAIT_MS = 45_000L
        private const val KEY_DISMISSED = "dismissed_build"
        private const val KEY_DOWNLOADED = "downloaded_build"
        private const val KEY_BETA = "beta"
        /** How many earlier builds "Previous version" lists. */
        private const val PREVIOUS_COUNT = 5

        /** The driver takes beta builds: every build of main that passed its tests, before it is promoted. */
        fun beta(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BETA, false)

        fun setBeta(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_BETA, on).apply()
        }

        /**
         * A build of main ("v1.0.712"), stable or beta: not a branch's test
         * build ("my-branch-v1.0.700") nor the second screen's card image.
         */
        internal fun isMainTag(tag: String): Boolean = Regex("^v\\d+\\.\\d+\\.\\d+$").matches(tag)

        /** Build number from a release tag or name: "v1.0.42" -> 42 (the last number wins). */
        internal fun parseBuildNumber(text: String): Long? =
            Regex("(\\d+)").findAll(text).lastOrNull()?.value?.toLongOrNull()

        /**
         * The launcher's own APK among a release's assets, as (name, URL)
         * pairs in the release's order: the first `.apk` that is neither the
         * phone companion, nor the Play edition's APK (a "-play" name, kept
         * for testers; it would not install over the GitHub edition's key,
         * and must never be offered to it), nor a Play bundle (`.aab`). The
         * URL comes from a document fetched over the network: only GitHub's
         * own release hosts, over https, are accepted.
         */
        internal fun pickLauncherApk(assets: List<Pair<String, String>>): String? {
            for ((name, url) in assets) {
                if (!isLauncherApk(name)) continue
                if (url.isBlank() || !url.startsWith("https://")) continue
                // java.net, not android.net.Uri: the choice is unit-tested on the JVM.
                val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
                if (host in ALLOWED_DOWNLOAD_HOSTS) return url
            }
            return null
        }

        /** True for the GitHub edition's own APK (see [pickLauncherApk]). */
        internal fun isLauncherApk(name: String): Boolean =
            name.endsWith(".apk", ignoreCase = true) &&
                !name.equals(COMPANION_APK_NAME, ignoreCase = true) &&
                !name.contains("-play", ignoreCase = true)

        private const val APK_NAME = "openauto-dash-update.apk"
        /** The whole download, however slowly it still moves. */
        private const val DOWNLOAD_TIMEOUT_MS = 30 * 60_000L
        /**
         * No byte arrived for this long: paused for good, as far as the driver is
         * concerned. Generous, as car Wi-Fi (a phone hotspot) drops out in tunnels.
         */
        private const val STALL_MS = 5 * 60_000L
        /** The phone companion app's asset in each release (see build.yml). */
        const val COMPANION_APK_NAME = "dashwheel-companion.apk"
        private val ALLOWED_DOWNLOAD_HOSTS = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
    }
}
