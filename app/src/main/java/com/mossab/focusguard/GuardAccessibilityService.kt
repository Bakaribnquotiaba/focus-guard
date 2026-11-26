package com.mossab.focusguard

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GuardAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: GuardAccessibilityService? = null
            private set

        private const val MAX_NODES = 2500
    }

    private val handler = Handler(Looper.getMainLooper())
    private var browsers: Set<String> = emptySet()
    private val datingCache = HashMap<String, Boolean>()
    private val lastRun = HashMap<String, Long>()
    private var lastUninstallPrompt = 0L
    @Volatile private var foregroundPackage: String? = null

    // ---------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (Prefs.bankingPause(this)) Prefs.setBankingPause(this, false)
        if (FilterVpnService.bankingOnly) FilterVpnService.restart(this)
        browsers = findBrowsers()

        val pkgFilter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
        register(packageReceiver, pkgFilter)
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        register(screenReceiver, screenFilter)

        FilterVpnService.startIfPermitted(this)
        findInstalledDatingApp()?.let { Prefs.setPendingUninstall(this, it) }
        handler.post(tick)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(packageReceiver) } catch (_: Exception) { }
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) { }
        super.onDestroy()
    }

    override fun onInterrupt() {}

    private fun register(r: BroadcastReceiver, f: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(r, f)
    }

    private fun findBrowsers(): Set<String> {
        val set = HashSet(Rules.KNOWN_BROWSERS)
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com"))
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(i, PackageManager.MATCH_ALL)
                .forEach { set.add(it.activityInfo.packageName) }
        } catch (_: Exception) {
        }
        set.removeAll(Rules.FACEBOOK_PACKAGES)
        set.remove(packageName)
        return set
    }

    private fun findInstalledDatingApp(): String? = try {
        @Suppress("DEPRECATION")
        packageManager.getInstalledApplications(0)
            .map { it.packageName }
            .firstOrNull { it != packageName && isDating(it) }
    } catch (_: Exception) {
        null
    }

    private val bankingCache = HashMap<String, Boolean>()

    private fun isBanking(pkg: String): Boolean =
        bankingCache.getOrPut(pkg) { pkg != packageName && Rules.isFinanceApp(this, pkg) }

    private fun isDating(pkg: String): Boolean =
        datingCache.getOrPut(pkg) { Rules.isDatingApp(this, pkg) }

    /** Banking apps refuse to run while an accessibility app is on, so switch off for a while. */
    fun pauseForBanking() {
        handler.removeCallbacksAndMessages(null)
        instance = null
        disableSelf()
    }

    // ---------------------------------------------------------------- receivers

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pkg = i.data?.schemeSpecificPart ?: return
            datingCache.remove(pkg)
            if (pkg in Prefs.trustedApps(c)) return
            if (isDating(pkg)) {
                Prefs.setPendingUninstall(c, pkg)
                Penalty.trigger(c, "A dating app was installed")
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (Penalty.isLockedOut(c)) handler.postDelayed({ enforceLockout() }, 300)
        }
    }

    // ---------------------------------------------------------------- periodic check

    private val tick = object : Runnable {
        override fun run() {
            try {
                periodic()
            } catch (_: Throwable) {
            }
            handler.postDelayed(this, 2000)
        }
    }

    private fun periodic() {
        if (Penalty.isLockedOut(this)) {
            enforceLockout()
            return
        }
        promptPendingUninstall()
        val top = rootInActiveWindow?.packageName?.toString() ?: return
        // Facebook slot ran out while it was open: close it (this is not counted as an attempt).
        if (top in Rules.FACEBOOK_PACKAGES && !Rules.facebookAllowed(this)) {
            goHome()
            BlockActivity.show(
                this, "Facebook time is over",
                "Your 5 minutes are up. Next slot: ${Rules.nextSlot().label()}.",
            )
        }
    }

    private fun promptPendingUninstall() {
        val pkg = Prefs.pendingUninstall(this) ?: return
        val installed = try {
            packageManager.getApplicationInfo(pkg, 0); true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        if (!installed) {
            Prefs.setPendingUninstall(this, null)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastUninstallPrompt < 120_000) return
        lastUninstallPrompt = now
        try {
            startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
        }
    }

    private fun enforceLockout() {
        val pm = getSystemService(PowerManager::class.java)
        val km = getSystemService(KeyguardManager::class.java)
        if (pm == null || km == null) return
        // Screen off or still on the lock screen (where emergency calls work): nothing to do.
        if (!pm.isInteractive || km.isKeyguardLocked) return
        val top = rootInActiveWindow?.packageName?.toString()
        if (top != packageName) LockoutActivity.show(this)
    }

    // ---------------------------------------------------------------- events

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return
        try {
            handle(e, pkg)
        } catch (_: Throwable) {
        }
    }

    private fun handle(e: AccessibilityEvent, pkg: String) {
        val stateChanged = e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (stateChanged) foregroundPackage = pkg

        if (Penalty.isLockedOut(this)) {
            if (stateChanged) enforceLockout()
            return
        }

        if (stateChanged && isBanking(pkg)) {
            if (once("banking", 3000)) BankingLaunchActivity.start(this, pkg)
            return
        }

        if (pkg in Prefs.trustedApps(this)) return

        if (isDating(pkg)) {
            if (once("dating", 5000)) {
                goHome()
                Prefs.setPendingUninstall(this, pkg)
                Penalty.trigger(this, "A dating app was opened")
            }
            return
        }

        if (pkg in Rules.FACEBOOK_PACKAGES) {
            if (stateChanged && !Rules.facebookAllowed(this)) facebookAttempt()
            return
        }

        if (pkg in Rules.CHAT_PACKAGES) {
            if (throttle(pkg, 600)) checkChat(pkg)
            return
        }

        if (pkg in browsers) {
            if (throttle(pkg, 500)) checkBrowser(pkg)
            return
        }

        if (!Prefs.isUnlocked(this)) {
            if (pkg == "com.android.vpndialogs") {
                if (throttle(pkg, 400)) checkOtherVpn(pkg)
            } else if (isSettingsLike(pkg)) {
                if (throttle("settings", 400)) checkSettings(pkg)
            }
        }
    }

    /** Called by the web filter when it blocked an adult or dating site. */
    fun onBlockedDomain(domain: String, cat: Blocklist.Category) {
        if (Penalty.isLockedOut(this)) return
        val fg = rootInActiveWindow?.packageName?.toString() ?: foregroundPackage ?: return
        // Only punish what you opened yourself in a browser, not link previews in other apps.
        if (fg !in browsers) return
        if (!once("domain", 5000)) return
        goHome()
        val what = if (cat == Blocklist.Category.DATING) "Dating site" else "Adult site"
        Penalty.trigger(this, "$what blocked: $domain")
    }

    // ---------------------------------------------------------------- rules

    private fun facebookAttempt() {
        goHome()
        if (!once("facebook", 4000)) return
        val n = Prefs.addAttempt(this, "facebook")
        if (n >= Rules.FACEBOOK_ATTEMPTS_FOR_PENALTY) {
            Prefs.resetAttempts(this, "facebook")
            Penalty.trigger(this, "Facebook opened $n times outside your time slots")
        } else {
            Penalty.vibrateWarning(this)
            val reason = if (!Rules.isAutoTime(this)) {
                "Turn on automatic date, time and time zone. Facebook stays closed until you do."
            } else {
                "Next slot: ${Rules.nextSlot().label()}."
            }
            BlockActivity.show(
                this, "Facebook is closed now",
                "$reason\n\nWarning $n of ${Rules.FACEBOOK_ATTEMPTS_FOR_PENALTY - 1}. " +
                    "Next time the phone locks for ${Rules.LOCKOUT_MINUTES} minutes.".takeIf {
                        n == Rules.FACEBOOK_ATTEMPTS_FOR_PENALTY - 1
                    }.orEmpty(),
            )
        }
    }

    private fun checkChat(pkg: String) {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        var nameFound = false
        var hasInput = false
        walk(root) { n ->
            if (!nameFound) {
                val t = "${n.text ?: ""} ${n.contentDescription ?: ""}"
                if (Rules.mentionsBlockedContact(t)) nameFound = true
            }
            if (n.isEditable) hasInput = true
            nameFound && hasInput
        }
        // A name plus a message box means the conversation itself is open, not just the chat list.
        if (nameFound && hasInput) chatAttempt()
    }

    private fun chatAttempt() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({ goHome() }, 150)
        if (!once("chat", 4000)) return
        val n = Prefs.addAttempt(this, "chat")
        if (n >= Rules.CHAT_ATTEMPTS_FOR_PENALTY) {
            Prefs.resetAttempts(this, "chat")
            Penalty.trigger(this, "Tried to open a blocked chat $n times")
        } else {
            Penalty.vibrateHard(this)
            BlockActivity.show(
                this, "This chat is blocked",
                "Warning $n of ${Rules.CHAT_ATTEMPTS_FOR_PENALTY - 1}. " +
                    "After that, the phone locks for ${Rules.LOCKOUT_MINUTES} minutes.",
            )
        }
    }

    private fun checkBrowser(pkg: String) {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val url = findUrl(root) ?: return
        val host = hostOf(url)
        val cat = host?.let { Blocklist.check(it) } ?: Blocklist.Category.NONE
        if (Rules.isAdultText(url) || cat == Blocklist.Category.ADULT) {
            if (once("domain", 5000)) {
                goHome()
                Penalty.trigger(this, "Adult content in the browser")
            }
            return
        }
        if (cat == Blocklist.Category.DATING) {
            if (once("domain", 5000)) {
                goHome()
                Penalty.trigger(this, "Dating site in the browser")
            }
            return
        }
        if (Rules.isFacebookUrl(url) && !Rules.facebookAllowed(this)) facebookAttempt()
    }

    private fun findUrl(root: AccessibilityNodeInfo): String? {
        var found: String? = null
        walk(root) { n ->
            val id = n.viewIdResourceName?.lowercase()
            if (id != null && Rules.URL_BAR_ID_HINTS.any { id.contains(it) }) {
                val t = n.text?.toString()
                if (!t.isNullOrBlank()) found = t
            }
            found != null
        }
        return found
    }

    private fun hostOf(url: String): String? {
        var s = url.trim().lowercase()
        if (s.contains(' ')) return null
        s = s.substringAfter("://")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        return if (s.contains('.')) s else null
    }

    private fun isSettingsLike(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("settings") || p.contains("packageinstaller") ||
            p.contains("permissioncontroller") || p.contains("systemmanager") ||
            p.contains("securitycenter") || p.contains("devicemanager")
    }

    /** Any settings screen that shows Focus Guard (app info, accessibility, device admin, VPN). */
    private fun checkSettings(pkg: String) {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        if (!containsText(root, Rules.APP_NAME)) return
        // Let updates through (installed from Obtainium); still block uninstalling.
        if (pkg.lowercase().contains("packageinstaller") &&
            (containsText(root, "update") || containsText(root, "تحديث")) &&
            !containsText(root, "uninstall") && !containsText(root, "إلغاء التثبيت")
        ) return
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({ goHome() }, 150)
        if (once("settings-msg", 3000)) {
            BlockActivity.show(
                this, "Focus Guard is protected",
                "Its settings are locked. Open Focus Guard and enter your PIN to unlock them for 10 minutes.",
            )
        }
    }

    /** Another VPN app asking to connect would switch the web filter off. */
    private fun checkOtherVpn(pkg: String) {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        if (containsText(root, Rules.APP_NAME)) return
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    // ---------------------------------------------------------------- helpers

    private fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun throttle(key: String, ms: Long): Boolean {
        val now = System.currentTimeMillis()
        val last = lastRun[key] ?: 0L
        if (now - last < ms) return false
        lastRun[key] = now
        return true
    }

    private fun once(key: String, ms: Long) = throttle("once:$key", ms)

    private fun containsText(root: AccessibilityNodeInfo, text: String): Boolean {
        val needle = text.lowercase()
        var hit = false
        walk(root) { n ->
            val t = "${n.text ?: ""} ${n.contentDescription ?: ""}".lowercase()
            if (t.contains(needle)) hit = true
            hit
        }
        return hit
    }

    /** Breadth-first walk over the screen; stops when [visit] returns true. */
    private fun walk(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Boolean) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        while (queue.isNotEmpty() && count < MAX_NODES) {
            val n = queue.removeFirst()
            count++
            if (visit(n)) return
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
    }
}
