package com.hyperisland.pro.services

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.service.notification.NotificationListenerService.RankingMap
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.hyperisland.pro.core.AppSettings
import com.hyperisland.pro.core.TraceLog
import com.hyperisland.pro.core.V2SeenEngine
import com.hyperisland.pro.core.V2SeenWatermark

class HyperNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TRACE_TAG = "HIP_TRACE"
        @Volatile var isConnected: Boolean = false
        @Volatile var lastDebugMessage: String = "Notification listener not connected"
        @Volatile private var listenerInstance: HyperNotificationListenerService? = null
        /** Service-side shade-open wipe calls into the listener to sweep the whole SHELF. */
        fun markShadeAllSeenFromApp() { listenerInstance?.mainHandler?.post { listenerInstance?.markAllActivesSeenFromShade() } }

        /**
         * b1500 (his ops report: after APK updates the listener shows ON in settings but nothing
         * arrives, and only a manual OFF->ON revives it; MIUI drops the binding on package
         * replace). requestRebind is the supported way to ask Android for a fresh binding -
         * free when already bound, so BootReceiver and MainActivity can ask liberally.
         */
        fun requestRebindNow(context: Context) {
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 24)
                    requestRebind(ComponentName(context, HyperNotificationListenerService::class.java))
                TraceLog.line("BOOT", "binding heal: listener rebind requested")
            }.onFailure { TraceLog.line("BOOT", "binding heal: rebind failed " + it.javaClass.simpleName) }
        }

        /** How long a re-posted notification stays blocked as a zombie repeat. */
        private const val ZOMBIE_BLOCK_MS = 20_000L
        private const val ZOMBIE_SWEEP_AFTER = 64

        // b1501 / Round-I P1-4 REASON AUDIT (AOSP master truth; his brief table and these hand-typed
        // values were BOTH wrong): CLICK=1 CANCEL=2 CANCEL_ALL=3 APP_CANCEL=8 APP_CANCEL_ALL=9
        // LISTENER_CANCEL=10/11. The old CANCEL_ALL=1 was actually CLICK, so a real clear-all (3) fell
        // into "not a read: page kept" and never forwarded - which is why 18:40's peel volley hurt:
        // the b1498 silencer simply never armed on clear-all. The 2026-09-19 reason=8 capture
        // (app opening its own chat cancels the notification) stays honoured via APP_CANCEL.
        private val REASON_CLICK get() = NotificationListenerService.REASON_CLICK
        private val REASON_CANCEL get() = NotificationListenerService.REASON_CANCEL
        private val REASON_CANCEL_ALL get() = NotificationListenerService.REASON_CANCEL_ALL
        private val REASON_APP_CANCEL get() = NotificationListenerService.REASON_APP_CANCEL
        private val REASON_APP_CANCEL_ALL get() = NotificationListenerService.REASON_APP_CANCEL_ALL

        /** Instagram cancelled a notification 559 ms after posting it (measured). 1.5 s is the floor. */
        private const val REMOVAL_GRACE_MS = 1_500L
    }

    private data class RepeatInfo(var count: Int, var firstSeenAt: Long, var lastSeenAt: Long, var blockedUntil: Long)
    private val repeatMap = HashMap<String, RepeatInfo>()

    // Threads seen since the last connect, so a re-sync cannot double-post what already arrived.
    private val recentlyHandled = HashMap<String, Long>()

    // Removals are delayed a beat: cancel-then-re-post during an update must not blink the card out.
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingRemovals = HashMap<String, Runnable>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        lastDebugMessage = "Notification listener connected"
        listenerInstance = this
        // Forensics FIRST (brief-itself was wrong here once): print the true symbolic values.
        TraceLog.line("V2SEEN", "v2 reasons: CLICK=$REASON_CLICK CANCEL=$REASON_CANCEL " +
            "CANCEL_ALL=$REASON_CANCEL_ALL APP_CANCEL=$REASON_APP_CANCEL APP_CANCEL_ALL=$REASON_APP_CANCEL_ALL " +
            (runCatching { "LISTENER_CANCEL=" + NotificationListenerService.REASON_LISTENER_CANCEL }.getOrDefault("LISTENER_CANCEL=?")) +
            " " + (runCatching { "LISTENER_CANCEL_ALL=" + NotificationListenerService.REASON_LISTENER_CANCEL_ALL }.getOrDefault("LISTENER_CANCEL_ALL=?")))
        // P1-5 connect baseline BEFORE the volley is judged: actives WITHOUT state are treated as
        // seen (kills the 19:12 re-inflation + fresh-install chat inflation). WITH state keep H, so
        // messages that arrived while we were dead (ts > H) still appear instantly.
        runCatching {
            val actives = getActiveNotifications()?.toList().orEmpty().filter { it.packageName != packageName }
            val entries = actives.mapNotNull { asbn -> v2SeenStampFor(asbn, msgKindFallback = false) }
            val (k, m, j) = V2SeenEngine.connectBaseline(this, entries)
            TraceLog.line("V2SEEN", "v2 seen-guard connect: active=${actives.size} baselined=$k withState=$m newWhileDead=$j")
            V2SeenEngine.persistNow(this)
        }.onFailure { TraceLog.line("V2SEEN", "baseline failed: " + it.javaClass.simpleName) }
        V2SeenEngine.livePageChecker = { key -> HyperAccessibilityService.hasLiveRingPageForApp(key) }
        V2SeenEngine.activeKeysSupplier = { activeConversationKeys() }
        mainHandler.removeCallbacks(v2StatsRunnable)
        mainHandler.post(v2StatsRunnable)
        resyncActiveNotifications()
    }

    // b1501: the shade-open wipe sweeps the WHOLE SHELF, not only what the island displayed.
    fun markAllActivesSeenFromShade() {
        runCatching {
            val actives = getActiveNotifications()?.toList().orEmpty().filter { it.packageName != packageName }
            val entries = actives.mapNotNull { asbn -> v2SeenStampFor(asbn, msgKindFallback = true) }
            if (entries.isNotEmpty()) V2SeenEngine.markSeenNow(this, entries, "shade-open sweep n=" + entries.size)
        }
    }

    /** One SeenStamp for an active SBN: per-message clock when the style gives one, Notification.when else. */
    private fun v2SeenStampFor(asbn: android.service.notification.StatusBarNotification, msgKindFallback: Boolean): V2SeenWatermark.SeenStamp? = runCatching {
        val ex = NotificationContentExtractor.extract(this, asbn) ?: return@runCatching null
        val stamps = NotificationContentExtractor.messageStamps(asbn)
        val whenMs = asbn.notification?.`when` ?: 0L
        if (stamps.isNotEmpty()) {
            val newest = stamps.maxBy { it.tsMs }.tsMs
            val csv = stamps.filter { it.tsMs == newest }.joinToString(",") { V2SeenWatermark.fp(it.tsMs, it.text, it.sender) }
            V2SeenWatermark.SeenStamp(ex.conversationKey, asbn.packageName ?: "", newest, csv, 1, 0L, "")
        } else {
            val sub = asbn.notification?.extras?.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
            val kind = if (ex.isMessagingStyle && msgKindFallback) 1 else 2
            V2SeenWatermark.SeenStamp(ex.conversationKey, asbn.packageName ?: "", whenMs, "", kind, whenMs,
                V2SeenWatermark.nonMsgFp(ex.conversationTitle, ex.latestMessage, sub))
        }
    }.getOrNull()

    private fun activeConversationKeys(): Set<String> = runCatching {
        getActiveNotifications()?.mapNotNull { asbn ->
            if (asbn.packageName == packageName) null else NotificationContentExtractor.extract(this, asbn)?.conversationKey
        }?.toSet() ?: emptySet()
    }.getOrDefault(emptySet())

    private val v2StatsRunnable = object : Runnable {
        override fun run() {
            TraceLog.line("V2SEEN", V2SeenEngine.stats())
            runCatching { V2SeenEngine.activeKeysSupplier?.invoke() }?.onSuccess { }
            mainHandler.postDelayed(this, 60_000L)
        }
    }

    /**
     * Anything posted while the listener was dead was gone for good — the OEM kills this service on
     * memory pressure and Android rebinds it later, silently. Re-reading the live shelf on connect is
     * the only way to recover those, and it is why whole batches of DMs used to vanish rather than one.
     */
    private fun resyncActiveNotifications() {
        val active = try {
            getActiveNotifications()
        } catch (e: Exception) {
            Log.w(TRACE_TAG, "RESYNC unavailable: ${e.javaClass.simpleName}")
            return
        } ?: return
        var replayed = 0
        for (sbn in active) {
            if (sbn.packageName == packageName) continue
            // CATEGORY_MESSAGE alone is the wrong filter and this is where the capture paid for it:
            // WhatsApp's real chat notifications carry NO category at all (only their bundle summary
            // says "21 messages from 3 chats"), so a re-sync used to replay the summary and skip every
            // actual chat - WhatsApp looked permanently broken after the OEM killed the service.
            val n = sbn.notification ?: continue
            val isChat = NotificationContentExtractor.looksLikeConversation(n) ||
                n.category == Notification.CATEGORY_MESSAGE
            if (!isChat) continue
            Log.i(TRACE_TAG, "RESYNC replay ${sbn.packageName} ${sbn.key}")
            handlePosted(sbn, resync = true)
            replayed++
        }
        if (replayed > 0) lastDebugMessage = "Re-synced $replayed live notification(s)"
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        lastDebugMessage = "Notification listener disconnected"
        mainHandler.removeCallbacks(v2StatsRunnable)
        runCatching { V2SeenEngine.persistNow(this) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        handlePosted(sbn, resync = false)
    }

    /**
     * Every rejection is logged with a reason. That is the point of this function: the previous
     * version had 15 bare `return`s, so "Instagram messages never show up" was undiagnosable except
     * by guessing. Filter decisions belong in the log, not in the user's patience.
     */
    private fun handlePosted(sbn: StatusBarNotification, resync: Boolean) {
        // RE-BINDING HOOK for system stability (unchanged: not touched in this fix)
        try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            if (am.isEnabled) { am.interrupt() }
        } catch (_: Exception) {}

        val pkg = sbn.packageName ?: return drop("no-package", sbn)
        val notification = sbn.notification ?: return drop("no-notification", sbn)

        // Anything posted right now wins over a removal that is still inside its grace window.
        cancelPendingRemoval(sbn.key)

        if (pkg == packageName) return drop("own-notification", sbn)
        if (!AppSettings.isIslandEnabled(this)) return drop("island-disabled", sbn)

        // A group summary is a container, not a message. If it is all we got (the app bundles DMs and
        // hides the children), pulling the children from the live shelf still shows the real chat.
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            val children = try { getActiveNotifications() } catch (_: Exception) { null }
            val groupName = notification.group
            // Children carry the group NAME, the summary carries the same name in Notification.getGroup();
            // StatusBarNotification.key ("0|com.whatsapp|1|null|10289") never equals it, which is why the
            // first version of this branch matched nothing. Same package is not optional either: WhatsApp
            // and the ShareKaro app both use a plain group name ("group_key_messages" / "group").
            val members: List<StatusBarNotification> = if (groupName.isNullOrBlank()) emptyList() else children
                ?.filter {
                    it.key != sbn.key &&
                        it.packageName == sbn.packageName &&
                        it.notification?.group == groupName &&
                        (it.notification?.flags?.and(Notification.FLAG_GROUP_SUMMARY) ?: 0) == 0
                }
                ?.sortedByDescending { it.postTime }
                .orEmpty()
            if (members.isEmpty()) return drop("group-summary-without-children", sbn)
            var handled = false
            for (child in members) {
                if (child.notification?.let { isPermanentNonRemovable(it) } == true) continue
                handlePosted(child, resync = resync)
                handled = true
                break // newest readable child is enough; the ring does the rest
            }
            if (!handled) return drop("group-summary-children-filtered", sbn)
            return
        }

        PillIconCache.record(pkg, notification.smallIcon, notification.icon) // cache real icon data for Pill Icon Lab

        val extracted = NotificationContentExtractor.extract(this, sbn)
            ?: return drop("extractor-null-empty-notification", sbn)
        val appName = extracted.appName
        val title = extracted.conversationTitle
        var message = extracted.latestMessage

        cancelPendingRemoval(extracted.conversationKey)

        if (title.isBlank() && message.isBlank()) return drop("blank-title-and-body", sbn)

        if (isPermanentNonRemovable(notification)) return drop("ongoing-or-persistent", sbn, "msgStyle=${extracted.isMessagingStyle}")

        // ---- b1501 / Round-I P1 seen-watermark: dead until a NEW message (his R3 pick via Claude) ----
        // Judged per-MESSAGE timestamp, never by a window. ECHO = silent, forever. Anything at-or-below
        // the watermark is a re-fire (chains A/B/C). Legacy guards stay ON as drop-only backstops.
        val v2Mode = V2SeenEngine.mode(this)
        val v2Stamps = if (extracted.isMessagingStyle) NotificationContentExtractor.messageStamps(sbn) else emptyList()
        var v2Verdict = "off"
        var v2NewestTs = 0L
        var v2FpsCsv = ""
        var v2NonFp = ""
        if (v2Mode != V2SeenWatermark.Mode.LEGACY_ONLY) {
            if (v2Stamps.isNotEmpty()) {
                val jr = V2SeenEngine.judgeMessaging(this, extracted.conversationKey, pkg, v2Stamps, sbn.postTime)
                v2Verdict = jr.verdict.name
                v2NewestTs = jr.newestTs
                v2FpsCsv = jr.boundaryCsv
                if (jr.verdict == V2SeenWatermark.Verdict.ECHO) {
                    lastDebugMessage = "silent echo: $appName | $title"
                    return // silent forever: no page, no sound, no haptic; nothing old ever returns
                }
                if (jr.verdict == V2SeenWatermark.Verdict.NEW && jr.newestUnseenText.isNotBlank()) {
                    message = jr.newestUnseenText // show ONLY the new message (R1/R3)
                }
            } else if (notification.`when` > 0L) {
                val sub = notification.extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
                v2NonFp = V2SeenWatermark.nonMsgFp(title, message, sub)
                val jr = V2SeenEngine.judgeNonMessaging(this, extracted.conversationKey, notification.`when`, v2NonFp)
                v2Verdict = jr.verdict.name
                v2NewestTs = notification.`when`
                if (jr.verdict == V2SeenWatermark.Verdict.ECHO) {
                    lastDebugMessage = "silent echo: $appName | $title"
                    return
                }
            }
        }

        if (ReplyEchoSuppressor.shouldSuppress(pkg, title, message, notification, sbn.key)) {
            if (v2Verdict == "NEW") V2SeenEngine.legacyConflict(this, "reply-echo", pkg, extracted.conversationKey, v2NewestTs)
            lastDebugMessage = "SUPPRESSED REPLY ECHO: $appName | $title"
            return // already logged with detail inside the suppressor
        }

        val now = System.currentTimeMillis()
        val fingerprint = "${sbn.key.orEmpty()}|${sbn.postTime}"
        if (isZombieRepeat(fingerprint, now)) {
            if (v2Verdict == "NEW") V2SeenEngine.legacyConflict(this, "listener-zombie", pkg, extracted.conversationKey, v2NewestTs)
            return drop("zombie-repeat", sbn, "fp=$fingerprint")
        }

        if (resync) {
            val seenAt = recentlyHandled[sbn.key] ?: 0L
            if (now - seenAt < 10_000L) {
                if (v2Verdict == "NEW") V2SeenEngine.legacyConflict(this, "listener-resync-dedup", pkg, extracted.conversationKey, v2NewestTs)
                return drop("already-shown-before-resync", sbn)
            }
        }
        recentlyHandled[sbn.key] = now
        if (recentlyHandled.size > 128) {
            val stale = recentlyHandled.filterValues { now - it > 60_000L }.keys
            if (stale.isNotEmpty()) recentlyHandled.keys.removeAll(stale)
        }

        lastDebugMessage = "SHOWN: $appName | $title"
        Log.i(TRACE_TAG, "SHOW $pkg thread=\"$title\" unread=${extracted.unreadCount} " +
            "identity=${extracted.conversationKeySource} resync=$resync")

        val actionList = notification.actions?.toList() ?: emptyList()

        val accepted = HyperAccessibilityService.showNotificationFromApp(
            context = this,
            packageName = pkg,
            notificationKey = sbn.key,
            appName = appName,
            title = title,
            message = message,
            unreadCount = extracted.unreadCount,
            conversationKey = extracted.conversationKey,
            conversationKeySource = extracted.conversationKeySource,
            postTime = sbn.postTime,
            contentIntent = notification.contentIntent,
            actions = actionList,
            smallIcon = notification.smallIcon,
            isMessagingStyle = NotificationContentExtractor.looksLikeConversation(notification),
            displayTimeMs = if (v2NewestTs > 0L) v2NewestTs else extracted.displayTimeMs,
            v2Verdict = v2Verdict, v2NewestTs = v2NewestTs, v2FpsCsv = v2FpsCsv
        )
        // P1-4a: the watermark advances only for what the island actually SHOWED.
        if (accepted && v2Mode != V2SeenWatermark.Mode.LEGACY_ONLY) {
            when {
                v2Stamps.isNotEmpty() && v2NewestTs > 0L ->
                    V2SeenEngine.markAdmittedAndSchedule(this, V2SeenWatermark.SeenStamp(extracted.conversationKey, pkg, v2NewestTs, v2FpsCsv, 1, 0L, ""))
                v2Stamps.isEmpty() && v2NewestTs > 0L ->
                    V2SeenEngine.markAdmittedAndSchedule(this, V2SeenWatermark.SeenStamp(extracted.conversationKey, pkg, v2NewestTs, "", 2, v2NewestTs, v2NonFp))
            }
        }
        TraceLog.ingest(
            "listener show ${sbn.packageName} key=${extracted.conversationKeySource} " +
                "title='${extracted.conversationTitle}' rule=${extracted.titleRule} unread=${extracted.unreadCount} " +
                "actions=${notification.actions?.size ?: 0}"
        )
    }

    /**
     * "The app removed this notification" finally means something. Two reasons are honoured: the user
     * swiped it away in the shade, or the app cancelled it (opening the chat in WhatsApp/Instagram does
     * this, capture reason = 8). Everything else - a group summary being torn down, a re-post - is
     * ignored, because those are not the user reading anything.
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        super.onNotificationRemoved(sbn, rankingMap, reason)
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        // P1-4d: USER-initiated removals mark the conversation seen HERE and NOW (works even when the
        // accessibility service missed the shade event). Never for app/system/listener reasons.
        if (reason == REASON_CLICK || reason == REASON_CANCEL || reason == REASON_CANCEL_ALL) {
            runCatching {
                v2SeenStampFor(sbn, msgKindFallback = true)?.let { stamp ->
                    V2SeenEngine.markSeenNow(this, listOf(stamp), "user-removal r" + reason)
                }
            }
        }
        if (reason == REASON_CANCEL_ALL || reason == REASON_APP_CANCEL_ALL) {
            // Clearing the whole shelf is already owned by the shade-open path in the island, which
            // marks everything seen; doing it twice would fight over the same state.
            TraceLog.count("ignored removal reason=${removalReasonName(reason)} pkg=$pkg (bulk: shade owns this)")
            return
        }
        if (reason != REASON_CANCEL && reason != REASON_APP_CANCEL) {
            // These are the lines that answer "who took the notification back": a group-summary tear-down
            // or a re-post must NOT cost a page, and a refusal that is invisible in the log reads exactly
            // like a page that vanished. Same words go to logcat for anyone attached with adb.
            TraceLog.count("ignored removal reason=${removalReasonName(reason)} pkg=$pkg (not a read: page kept)")
            Log.i(TRACE_TAG, "IGNORE removal reason=$reason pkg=$pkg")
            return
        }
        val extracted = sbn.notification?.let { NotificationContentExtractor.extract(this, sbn) }
        val key = extracted?.conversationKey
        if (key.isNullOrBlank()) {
            drop("removal-without-conversation-key", sbn, "reason=$reason")
            return
        }
        val notificationKey = sbn.key
        val task = Runnable {
            pendingRemovals.remove(key)
            pendingRemovals.remove(notificationKey)
            Log.i(TRACE_TAG, "REMOVE reason=$reason pkg=$pkg thread=\"${extracted.conversationTitle}\"")
            HyperAccessibilityService.dismissConversationFromApp(this, key, reason)
        }
        pendingRemovals[key]?.let { mainHandler.removeCallbacks(it) }
        pendingRemovals[key] = task
        notificationKey?.let { pendingRemovals[it] = task }
        mainHandler.postDelayed(task, REMOVAL_GRACE_MS)
    }

    /** Same names as the island logs, so one word means one thing in both places. */
    private fun removalReasonName(reason: Int): String = when (reason) {
        REASON_CLICK -> "he-tapped-the-row"
        REASON_CANCEL -> "he-swiped-it-away"
        REASON_CANCEL_ALL -> "he-cleared-the-shelf"
        REASON_APP_CANCEL -> "app-cancelled-it-itself"
        REASON_APP_CANCEL_ALL -> "app-cleared-everything"
        else -> "reason=$reason"
    }

    private fun cancelPendingRemoval(key: String?) {
        if (key.isNullOrBlank()) return
        pendingRemovals.remove(key)?.let { mainHandler.removeCallbacks(it) }
    }

    private fun drop(reason: String, sbn: StatusBarNotification?, extra: String = "") {
        TraceLog.ingest("listener drop $reason pkg=${sbn?.packageName} key=${sbn?.key} $extra")
    }

    /**
     * An ongoing/foreground marker means "not swipeable away", it does NOT mean "not a message".
     * Chat apps legitimately mark a live conversation ongoing (chat open, typing, delivery pending),
     * so exempting messaging notifications is what stopped real DMs from being silently discarded.
     */
    private fun isPermanentNonRemovable(notification: Notification): Boolean {
        val flags = notification.flags
        val messaging = notification.category == Notification.CATEGORY_MESSAGE
        if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return true
        if (flags and Notification.FLAG_NO_CLEAR != 0 && !messaging) return true
        return flags and Notification.FLAG_ONGOING_EVENT != 0 && !messaging
    }

    /**
     * Repeat suppression, re-keyed on notification identity + post time instead of the message text.
     * Keying on text meant a second "hi" from the same thread within the block window was invisible
     * for 2 minutes. A genuine new message always carries a fresh postTime, so it now passes.
     */
    private fun isZombieRepeat(fingerprint: String, now: Long): Boolean {
        val info = repeatMap[fingerprint] ?: RepeatInfo(0, now, now, 0L).also { repeatMap[fingerprint] = it }
        if (now < info.blockedUntil) return true
        if (now - info.lastSeenAt <= 10_000L) {
            info.count += 1
            info.lastSeenAt = now
        } else {
            info.count = 1
            info.firstSeenAt = now
            info.lastSeenAt = now
            info.blockedUntil = 0L
            return false
        }
        if (info.count >= 3 && now - info.firstSeenAt <= 30_000L) {
            info.blockedUntil = now + ZOMBIE_BLOCK_MS
            return true
        }
        // The map used to only ever grow; entries for dead notifications are worthless.
        if (repeatMap.size > ZOMBIE_SWEEP_AFTER) {
            val stale = repeatMap.filterValues { now - it.lastSeenAt > ZOMBIE_BLOCK_MS }.keys
            if (stale.isNotEmpty()) repeatMap.keys.removeAll(stale)
        }
        return false
    }
}
