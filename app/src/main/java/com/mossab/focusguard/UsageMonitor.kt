package com.mossab.focusguard

import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
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
import android.provider.Settings

/**
 * Bank-safe mode: does Focus Guard's job without Accessibility (which InstaPay refuses to run beside).
 * Sees the app in front through Usage access and shows block screens through "Display over other apps".
 * Runs inside the web filter's process; does nothing while the Accessibility service is on.
 */
object UsageMonitor {

    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private lateinit var app: Context
    private var lastQuery = 0L
    private var lastOpened: String? = null
    private var lastUninstallPrompt = 0L
    private var lastAction = HashMap<String, Long>()
    private val datingCache = HashMap<String, Boolean>()
    private var browsers: Set<String> = emptySet()

    @Volatile var foreground: String? = null
        private set

    /** Both one-time switches are on, so it can do its job. */
    fun capable(c: Context) = AutoAccessibility.hasUsageAccess(c) && Settings.canDrawOverlays(c)

    /** Something is watching the screen: Accessibility, or this monitor. */
    fun watching(c: Context) = GuardAccessibilityService.instance != null || capable(c)

    private fun accessibilityOn() = GuardAccessibilityService.instance != null

    fun start(c: Context) {
        if (started) return
        started = true
        app = c.applicationContext
        browsers = Rules.findBrowsers(app)
        val f = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(packageReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(packageReceiver, f)
        main.post(tick)
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (accessibilityOn()) return // the Accessibility service handles it
            val pkg = i.data?.schemeSpecificPart ?: return
            datingCache.remove(pkg)
            if (isDating(pkg)) {
                Prefs.setPendingUninstall(c, pkg)
                Penalty.trigger(c, "A dating app was installed")
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            try {
                step()
            } catch (_: Throwable) {
            }
            main.postDelayed(this, 1000)
        }
    }

    private fun isDating(pkg: String) = datingCache.getOrPut(pkg) { Rules.isDatingApp(app, pkg) }

    private fun throttle(key: String, ms: Long): Boolean {
        val now = System.currentTimeMillis()
        if (now - (lastAction[key] ?: 0L) < ms) return false
        lastAction[key] = now
        return true
    }

    private fun goHome() {
        try {
            app.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
        }
    }

    /** Reads which app came to the front since the last check. Returns the newly opened app, if any. */
    private fun refreshForeground(): String? {
        val usm = app.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val from = if (lastQuery == 0L) now - 60_000L else lastQuery - 2_000L
        lastQuery = now
        val events = usm.queryEvents(from, now)
        val e = UsageEvents.Event()
        var opened: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) opened = e.packageName
        }
        if (opened != null) foreground = opened
        return opened
    }

    private fun step() {
        if (accessibilityOn() || !capable(app)) return
        val pm = app.getSystemService(PowerManager::class.java) ?: return
        if (!pm.isInteractive) return
        val opened = refreshForeground()
        val fg = foreground ?: return
        val km = app.getSystemService(KeyguardManager::class.java)

        if (Penalty.isLockedOut(app)) {
            if (km != null && !km.isKeyguardLocked && fg != app.packageName && throttle("lockout", 1500)) {
                LockoutActivity.show(app)
            }
            return
        }
        if (fg == app.packageName) return

        val newlyOpened = opened != null && opened == fg && opened != lastOpened
        if (opened != null) lastOpened = opened

        promptPendingUninstall()

        if (isDating(fg)) {
            if (throttle("dating", 5000)) {
                goHome()
                Prefs.setPendingUninstall(app, fg)
                Penalty.trigger(app, "A dating app was opened")
            }
            return
        }

        if (fg in Rules.FACEBOOK_PACKAGES && !Rules.facebookAllowed(app)) {
            goHome()
            if (newlyOpened && throttle("facebook", 4000)) {
                facebookAttempt()
            } else if (throttle("fb-over", 4000)) {
                BlockActivity.show(
                    app, "Facebook is closed now",
                    "Next slot: ${Rules.nextSlot().label()}.",
                )
            }
        }
    }

    private fun facebookAttempt() {
        val n = Prefs.addAttempt(app, "facebook")
        if (n >= Rules.FACEBOOK_ATTEMPTS_FOR_PENALTY) {
            Prefs.resetAttempts(app, "facebook")
            Penalty.trigger(app, "Facebook opened $n times outside your time slots")
        } else {
            Penalty.vibrateWarning(app)
            val reason = if (!Rules.isAutoTime(app)) {
                "Turn on automatic date, time and time zone. Facebook stays closed until you do."
            } else {
                "Next slot: ${Rules.nextSlot().label()}."
            }
            BlockActivity.show(
                app, "Facebook is closed now",
                "$reason\n\nWarning $n of ${Rules.FACEBOOK_ATTEMPTS_FOR_PENALTY - 1}.",
            )
        }
    }

    private fun promptPendingUninstall() {
        val pkg = Prefs.pendingUninstall(app) ?: return
        val installed = try {
            app.packageManager.getApplicationInfo(pkg, 0); true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        if (!installed) {
            Prefs.setPendingUninstall(app, null)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastUninstallPrompt < 120_000) return
        lastUninstallPrompt = now
        try {
            app.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
        }
    }

    /** The web filter blocked an adult or dating site. */
    fun onBlockedDomain(domain: String, cat: Blocklist.Category) {
        if (!started || accessibilityOn() || !capable(app)) return
        if (Penalty.isLockedOut(app)) return
        val fg = foreground ?: return
        if (fg !in browsers) return
        if (!throttle("domain", 5000)) return
        goHome()
        val what = if (cat == Blocklist.Category.DATING) "Dating site" else "Adult site"
        Penalty.trigger(app, "$what blocked: $domain")
    }
}
