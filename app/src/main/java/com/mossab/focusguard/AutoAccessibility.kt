package com.mossab.focusguard

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings

/**
 * Turns Focus Guard's accessibility service back on by itself.
 * Needs WRITE_SECURE_SETTINGS, which Android only grants once from a computer (adb) or wireless debugging.
 */
object AutoAccessibility {

    const val ADB_COMMAND =
        "adb shell pm grant com.mossab.focusguard android.permission.WRITE_SECURE_SETTINGS"

    fun canSwitch(c: Context) =
        c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    private fun component(c: Context) =
        ComponentName(c, GuardAccessibilityService::class.java).flattenToString()

    /** Returns true if it wrote the setting. */
    fun enable(c: Context): Boolean {
        if (!canSwitch(c)) return false
        return try {
            val cr = c.contentResolver
            val me = component(c)
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val list = current.split(':').filter { it.isNotBlank() && it != me }.toMutableList()
            list.add(me)
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun hasUsageAccess(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** The app that came to the front most recently, or null if unknown. */
    fun lastForegroundApp(c: Context): String? {
        if (!hasUsageAccess(c)) return null
        val usm = c.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 10 * 60_000L, now)
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
        }
        return last
    }
}
