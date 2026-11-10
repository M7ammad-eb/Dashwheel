package com.openauto.dash.companion

import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.openauto.dash.link.ConversationLine
import com.openauto.dash.link.NotificationPosted
import com.openauto.dash.link.NotificationRemoved
import com.openauto.dash.link.NotificationSync
import com.openauto.dash.link.PhoneNotification
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Reads the phone's notifications (the driver grants Notification access) and
 * forwards them over the link. Replies go back through each app's own reply
 * action, the same one Android Auto and smartwatches use, so WhatsApp,
 * Messages, Signal, Telegram... all work without anything app-specific.
 * An app's call notification (a WhatsApp call…) goes to [PhoneCalls] instead,
 * to show as a call.
 */
class PhoneNotificationListener : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        // The listener lives for days: without this, an updated app kept its old icon in the car.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        // A protected system broadcast: only the system can send it.
        ContextCompat.registerReceiver(this, packageChanged, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onListenerConnected() {
        instance = this
        PhoneCalls.onListenerConnected(this)
        LinkServer.send(syncMessage())
        // Granted while the car was already linked: it stops asking for the access.
        LinkServer.send(LinkServer.abilities(this))
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        PhoneCalls.onListenerDisconnected()
        // Android lets go of a listener now and then (memory, an update, a battery saver) while the
        // access stays granted, and doesn't always come back by itself: asked back at once (issue #123).
        rebind(this)
        LinkServer.send(LinkServer.abilities(this))
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        runCatching { unregisterReceiver(packageChanged) }
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (PhoneCalls.onNotificationPosted(this, sbn)) return
        val item = toPhoneNotification(sbn) ?: return
        // The app's icon (a few KB, encrypted and sent) goes with its first notification
        // only: the head unit keeps one per app, as the sync on connecting already does.
        val first = synchronized(sent) { sent.keys.add(item.key); sent.icons.add(item.packageName) }
        LinkServer.send(NotificationPosted(if (first) item else item.copy(iconPng = null)))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        PhoneCalls.onNotificationRemoved(sbn.key)
        // Only for one the car was told of: most removals are of ongoing, summary or media notifications it never saw.
        if (synchronized(sent) { sent.keys.remove(sbn.key) }) LinkServer.send(NotificationRemoved(sbn.key))
    }

    /** What the car has been sent since the last sync: the notifications by key, and the apps whose icon went with one. */
    private class Sent(val keys: HashSet<String> = HashSet(), val icons: HashSet<String> = HashSet())
    private val sent = Sent()

    /** Answers the conversation [key] with [text]. False when it can't be answered. */
    fun reply(key: String, text: String): Boolean {
        val n = find(key)?.notification ?: return false
        val action = replyAction(n) ?: return false
        val inputs = action.remoteInputs ?: return false
        val results = Bundle().apply {
            inputs.filter { it.allowFreeFormInput }.forEach { putCharSequence(it.resultKey, text) }
        }
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, results)
        RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        action.actionIntent.send(this, 0, intent)
        return true
    }

    fun markRead(key: String): Boolean {
        val n = find(key)?.notification ?: return false
        val action = allActions(n).firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ } ?: return false
        action.actionIntent.send()
        return true
    }

    fun dismiss(key: String): Boolean {
        find(key) ?: return false
        cancelNotification(key)
        return true
    }

    private fun find(key: String): StatusBarNotification? =
        runCatching { getActiveNotifications(arrayOf(key)) }.getOrNull()?.firstOrNull()

    /**
     * What the phone itself would not show on its lock screen (the app or
     * the driver marked it secret), and what it shows nowhere but folded away
     * in the shade (the lowest importance), is not for the car's screen
     * either, where passengers read along.
     */
    private fun keptPrivate(sbn: StatusBarNotification): Boolean {
        if (sbn.notification.visibility == Notification.VISIBILITY_SECRET) return true
        val ranking = Ranking()
        if (runCatching { currentRanking.getRanking(sbn.key, ranking) }.getOrDefault(false) != true) return false
        // The driver's own choice for this app's channel; asked of the channel before Android 12.
        val onLockScreen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ranking.lockscreenVisibilityOverride
        else ranking.channel?.lockscreenVisibility
        return onLockScreen == Notification.VISIBILITY_SECRET || ranking.importance == NotificationManager.IMPORTANCE_MIN
    }

    private fun toPhoneNotification(sbn: StatusBarNotification): PhoneNotification? {
        if (sbn.packageName == packageName) return null
        val n = sbn.notification ?: return null
        if (sbn.isOngoing || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return null
        // WhatsApp & co post one summary over the per-chat notifications: the chats are enough.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_PROGRESS) return null
        // A call shows as a call (PhoneCalls), not in the notifications.
        if (CallNotification.isCall(n)) return null
        if (keptPrivate(sbn)) return null

        val extras = n.extras
        val style = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull()
        val me = style?.user?.name?.toString().orEmpty()
        val lines = style?.messages.orEmpty().takeLast(MAX_LINES).mapNotNull { m ->
            val text = m.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) null
            else ConversationLine((m.person?.name?.toString() ?: me).take(MAX_TITLE), text, m.timestamp)
        }
        val title = (style?.conversationTitle ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()
        val text = lines.lastOrNull()?.text
            ?: (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return null

        val (appName, icon) = appIdentity(sbn.packageName)
        return PhoneNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = appName,
            title = title.take(MAX_TITLE),
            text = text.take(MAX_TEXT),
            postedAt = sbn.postTime,
            messages = lines.map { it.copy(text = it.text.take(MAX_TEXT)) },
            canReply = replyAction(n) != null,
            canMarkRead = allActions(n).any { it.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ },
            iconPng = icon
        )
    }

    private val identityCache = HashMap<String, Pair<String, String?>>()

    /** An app updated or changed: its name and icon are read again at its next notification, and the icon sent again. */
    private val packageChanged = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            synchronized(this@PhoneNotificationListener) { identityCache.remove(pkg) }
            synchronized(sent) { sent.icons.remove(pkg) }
        }
    }

    /** App name + icon (small PNG, base64), once per package. */
    @Synchronized
    private fun appIdentity(pkg: String): Pair<String, String?> = identityCache.getOrPut(pkg) {
        val pm = packageManager
        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        val icon = runCatching {
            val bytes = ByteArrayOutputStream()
            pm.getApplicationIcon(pkg).toBitmap(ICON_PX, ICON_PX).compress(Bitmap.CompressFormat.PNG, 100, bytes)
            Base64.getEncoder().encodeToString(bytes.toByteArray())
        }.getOrNull()
        label to icon
    }

    companion object {
        private const val MAX_LINES = 6
        private const val MAX_TEXT = 1_000
        private const val MAX_TITLE = 200
        private const val ICON_PX = 64

        /** The bound listener, null until the driver grants Notification access. */
        @Volatile
        var instance: PhoneNotificationListener? = null
            private set

        /**
         * The driver granted Notification access. Not the same as [instance] being
         * there: Android unbinds a granted listener for a while now and then, and
         * telling the car "not granted" then had it ask the driver again (issue #123).
         */
        fun granted(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        /** Granted but not bound: asks Android to bind the listener again. */
        fun rebind(context: Context) {
            if (instance != null || !granted(context)) return
            runCatching { NotificationListenerService.requestRebind(ComponentName(context, PhoneNotificationListener::class.java)) }
        }

        /**
         * The newest notifications worth showing in the car, as one sync that
         * fits the link's frame limit (see [NotificationSync.of]).
         */
        fun syncMessage(): NotificationSync {
            val listener = instance ?: return NotificationSync(emptyList())
            val active = runCatching { listener.activeNotifications }.getOrNull() ?: return NotificationSync(emptyList())
            // Only as many as the car shows are read out of the notifications.
            val newest = active.sortedByDescending { it.postTime }.asSequence()
                .mapNotNull { listener.toPhoneNotification(it) }
                .take(NotificationSync.MAX_ITEMS)
                .toList()
            val sync = NotificationSync.of(newest)
            // A fresh start for the link: what this sync carries is what the car has.
            synchronized(listener.sent) {
                listener.sent.keys.clear()
                listener.sent.keys.addAll(sync.notifications.map { it.key })
                listener.sent.icons.clear()
                listener.sent.icons.addAll(sync.notifications.filter { it.iconPng != null }.map { it.packageName })
            }
            return sync
        }

        /** The action that takes a typed answer, as Android Auto finds it. */
        private fun replyAction(n: Notification): Notification.Action? {
            val actions = allActions(n).filter { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
            return actions.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY } ?: actions.firstOrNull()
        }

        /** The notification's own actions, then the ones some apps only give smartwatches. */
        private fun allActions(n: Notification): List<Notification.Action> =
            n.actions.orEmpty().toList() + Notification.WearableExtender(n).actions
    }
}
