package com.hyperisland.pro.services

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.widget.Toast
import com.hyperisland.pro.core.AppSettings

class HyperNotificationListenerService : NotificationListenerService() {

    companion object {
        @Volatile
        var isConnected: Boolean = false
            private set

        @Volatile
        var lastDebugMessage: String = "Notification listener not connected"
            private set
    }

    private val keyLastShownAt = HashMap<String, Long>()
    private val packageLastShownAt = HashMap<String, Long>()
    private val fingerprintLastShownAt = HashMap<String, Long>()
    private val messageFingerprintCache = HashMap<String, String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        lastDebugMessage = "Notification listener connected"

        try {
            Toast.makeText(
                this,
                "Hyper Island notification listener connected",
                Toast.LENGTH_SHORT
            ).show()
        } catch (_: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        lastDebugMessage = "Notification listener disconnected"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val pkg = sbn.packageName ?: return
        val notification = sbn.notification ?: return
        val now = System.currentTimeMillis()

        if (pkg == packageName) {
            lastDebugMessage = "Ignored own app notification"
            return
        }

        if (!AppSettings.isIslandEnabled(this)) {
            lastDebugMessage = "Ignored because island OFF: $pkg"
            return
        }

        val title = extractTitle(notification)
        val message = extractMessage(notification)
        val appName = getAppName(pkg)
        val category = notification.category ?: "null"
        val flags = notification.flags
        val isMessage = isMessageNotification(pkg, notification)
        val fingerprint = "$pkg|$title|$message"

        lastDebugMessage =
            "RAW: $appName\npkg=$pkg\ncat=$category flags=$flags\ntitle=$title\nmsg=$message"

        if ((flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            lastDebugMessage = "Ignored group summary: $appName"
            return
        }

        if (title.isBlank() && message.isBlank()) {
            lastDebugMessage = "Ignored empty notification: $appName pkg=$pkg cat=$category flags=$flags"
            return
        }

        if (isMessage) {
            handleMessageNotification(
                sbn = sbn,
                pkg = pkg,
                appName = appName,
                title = title,
                message = message,
                fingerprint = fingerprint,
                now = now,
                notification = notification
            )
            return
        }

        val noisyStatusApp = isKnownNoisyStatusApp(pkg, appName)

        if (isProgressNotification(notification)) {
            lastDebugMessage = "Ignored progress/status notification: $appName"
            return
        }

        if (noisyStatusApp && isOngoingOrSticky(notification)) {
            lastDebugMessage = "Ignored known noisy ongoing/sticky: $appName"
            return
        }

        val lastKeyShown = keyLastShownAt[sbn.key] ?: 0L
        val sameKeyRecentlyShown = now - lastKeyShown < 60_000L

        if (sameKeyRecentlyShown && isOngoingOrSticky(notification)) {
            lastDebugMessage = "Suppressed ongoing same-key update: $appName"
            return
        }

        val lastFingerprintShown = fingerprintLastShownAt[fingerprint] ?: 0L
        if (now - lastFingerprintShown < 30_000L) {
            lastDebugMessage = "Suppressed duplicate/zombie fingerprint: $appName"
            return
        }

        val lastPackageShown = packageLastShownAt[pkg] ?: 0L
        if (now - lastPackageShown < 5_000L) {
            lastDebugMessage = "Suppressed package cooldown: $appName"
            return
        }

        keyLastShownAt[sbn.key] = now
        fingerprintLastShownAt[fingerprint] = now
        packageLastShownAt[pkg] = now

        lastDebugMessage = "SHOWN: $appName | $title | $message"

        sendToIsland(
            sbn = sbn,
            pkg = pkg,
            appName = appName,
            title = title,
            message = message,
            notification = notification
        )
    }

    private fun handleMessageNotification(
        sbn: StatusBarNotification,
        pkg: String,
        appName: String,
        title: String,
        message: String,
        fingerprint: String,
        now: Long,
        notification: Notification
    ) {
        val previousFingerprint = messageFingerprintCache[pkg]
        val lastFingerprintShown = fingerprintLastShownAt[fingerprint] ?: 0L

        if (previousFingerprint == fingerprint && now - lastFingerprintShown < 1_500L) {
            lastDebugMessage = "Ignored immediate duplicate message: $appName"
            return
        }

        messageFingerprintCache[pkg] = fingerprint
        fingerprintLastShownAt[fingerprint] = now
        keyLastShownAt[sbn.key] = now
        packageLastShownAt[pkg] = now

        lastDebugMessage = "SHOWN MESSAGE: $appName | $title | $message"

        sendToIsland(
            sbn = sbn,
            pkg = pkg,
            appName = appName,
            title = title,
            message = message,
            notification = notification
        )
    }

    private fun sendToIsland(
        sbn: StatusBarNotification,
        pkg: String,
        appName: String,
        title: String,
        message: String,
        notification: Notification
    ) {
        HyperAccessibilityService.showNotificationFromApp(
            context = this,
            packageName = pkg,
            appName = appName,
            title = title,
            message = message,
            postTime = sbn.postTime,
            contentIntent = notification.contentIntent
        )
    }

    private fun isMessageNotification(pkg: String, notification: Notification): Boolean {
        if (notification.category == Notification.CATEGORY_MESSAGE) return true

        val lower = pkg.lowercase()

        return lower.contains("whatsapp") ||
            lower.contains("telegram") ||
            lower.contains("signal") ||
            lower.contains("mms") ||
            lower.contains("sms") ||
            lower.contains("messaging") ||
            lower.contains("messages")
    }

    private fun isKnownNoisyStatusApp(pkg: String, appName: String): Boolean {
        val p = pkg.lowercase()
        val n = appName.lowercase()

        return p.contains("ampere") ||
            n.contains("ampere") ||
            p.contains("honeygain") ||
            n.contains("honeygain") ||
            p.contains("battery") ||
            n.contains("battery") ||
            p.contains("batterymeter") ||
            n.contains("battery meter") ||
            p.contains("accubattery") ||
            n.contains("accubattery") ||
            p.contains("vpn") ||
            n.contains("vpn") ||
            p.contains("netguard") ||
            n.contains("netguard")
    }

    private fun isOngoingOrSticky(notification: Notification): Boolean {
        val flags = notification.flags

        return (flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
            (flags and Notification.FLAG_FOREGROUND_SERVICE) != 0 ||
            (flags and Notification.FLAG_NO_CLEAR) != 0
    }

    private fun isProgressNotification(notification: Notification): Boolean {
        val extras = notification.extras ?: return false

        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val progress = extras.getInt(Notification.EXTRA_PROGRESS, 0)

        return max > 0 ||
            progress > 0 ||
            extras.containsKey(Notification.EXTRA_PROGRESS_INDETERMINATE)
    }

    private fun extractTitle(notification: Notification): String {
        val extras = notification.extras

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        if (!title.isNullOrBlank()) return title

        val titleBig = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.trim()
        if (!titleBig.isNullOrBlank()) return titleBig

        val conversationTitle =
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()
        if (!conversationTitle.isNullOrBlank()) return conversationTitle

        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
        if (!subText.isNullOrBlank()) return subText

        return ""
    }

    private fun extractMessage(notification: Notification): String {
        val extras = notification.extras

        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (!messages.isNullOrEmpty()) {
            val last = messages.lastOrNull()
            val text = try {
                val bundle = last as? android.os.Bundle
                bundle?.getCharSequence("text")?.toString()?.trim()
            } catch (_: Exception) {
                null
            }

            if (!text.isNullOrBlank()) return text
        }

        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
        if (!bigText.isNullOrBlank()) return bigText

        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        if (!text.isNullOrBlank()) return text

        val summary = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()?.trim()
        if (!summary.isNullOrBlank()) return summary

        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (!lines.isNullOrEmpty()) {
            return lines.joinToString("\n") { it.toString() }.trim()
        }

        return ""
    }

    private fun getAppName(pkg: String): String {
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            pkg
        }
    }
}
