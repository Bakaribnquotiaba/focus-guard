package com.mossab.focusguard

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Prefs {
    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("guard", Context.MODE_PRIVATE)

    private fun hash(pin: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(("focus-guard:" + pin).toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    // ---- PIN ----
    fun hasPin(c: Context) = sp(c).contains("pin")
    fun setPin(c: Context, pin: String) = sp(c).edit().putString("pin", hash(pin)).apply()
    fun checkPin(c: Context, pin: String) = sp(c).getString("pin", null) == hash(pin)

    // ---- Protection unlocked with the PIN ----
    fun unlockedUntil(c: Context) = sp(c).getLong("unlockedUntil", 0L)
    fun setUnlockedUntil(c: Context, t: Long) = sp(c).edit().putLong("unlockedUntil", t).apply()
    fun isUnlocked(c: Context) = System.currentTimeMillis() < unlockedUntil(c)
    fun facebookFreeUntil(c: Context) = sp(c).getLong("facebookFreeUntil", 0L)
    fun setFacebookFreeUntil(c: Context, t: Long) = sp(c).edit().putLong("facebookFreeUntil", t).apply()

    // ---- Lockout ----
    fun lockoutUntil(c: Context) = sp(c).getLong("lockoutUntil", 0L)
    fun lockoutReason(c: Context) = sp(c).getString("lockoutReason", "") ?: ""
    fun setLockout(c: Context, until: Long, reason: String) =
        sp(c).edit().putLong("lockoutUntil", until).putString("lockoutReason", reason).apply()

    // ---- Daily attempt counters ----
    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    fun attempts(c: Context, kind: String) = sp(c).getInt("attempts_${kind}_${today()}", 0)
    fun addAttempt(c: Context, kind: String): Int {
        val n = attempts(c, kind) + 1
        sp(c).edit().putInt("attempts_${kind}_${today()}", n).apply()
        return n
    }
    fun resetAttempts(c: Context, kind: String) =
        sp(c).edit().putInt("attempts_${kind}_${today()}", 0).apply()

    // ---- Penalties today (shown in the app) ----
    fun penaltiesToday(c: Context) = sp(c).getInt("penalties_${today()}", 0)
    fun addPenalty(c: Context) =
        sp(c).edit().putInt("penalties_${today()}", penaltiesToday(c) + 1).apply()

    // ---- Web filter wanted on ----
    fun vpnWanted(c: Context) = sp(c).getBoolean("vpnWanted", false)
    fun setVpnWanted(c: Context, on: Boolean) = sp(c).edit().putBoolean("vpnWanted", on).apply()

    // ---- Apps the web filter leaves alone (banking apps that refuse to run with a VPN) ----
    fun trustedApps(c: Context): Set<String> = sp(c).getStringSet("trustedApps", emptySet()) ?: emptySet()
    fun setTrustedApps(c: Context, pkgs: Set<String>) =
        sp(c).edit().putStringSet("trustedApps", HashSet(pkgs)).apply()

    // ---- Banking pause: screen watching off, only banking apps online ----
    fun bankingPause(c: Context) = sp(c).getBoolean("bankingPause", false)
    fun setBankingPause(c: Context, on: Boolean) = sp(c).edit().putBoolean("bankingPause", on).apply()
    fun bankingApp(c: Context): String? = sp(c).getString("bankingApp", null)
    fun setBankingApp(c: Context, pkg: String?) = sp(c).edit().putString("bankingApp", pkg).apply()
    fun bankingPausedAt(c: Context) = sp(c).getLong("bankingPausedAt", 0L)
    fun setBankingPausedAt(c: Context, t: Long) = sp(c).edit().putLong("bankingPausedAt", t).apply()

    // ---- Dating app waiting to be uninstalled ----
    fun pendingUninstall(c: Context): String? = sp(c).getString("pendingUninstall", null)
    fun setPendingUninstall(c: Context, pkg: String?) =
        sp(c).edit().putString("pendingUninstall", pkg).apply()
}
