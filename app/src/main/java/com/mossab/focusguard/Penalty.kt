package com.mossab.focusguard

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Very hard vibration, then lock the phone and keep it locked for 15 minutes. */
object Penalty {
    private val main = Handler(Looper.getMainLooper())

    fun isLockedOut(c: Context) = System.currentTimeMillis() < Prefs.lockoutUntil(c)

    fun remainingMs(c: Context) = (Prefs.lockoutUntil(c) - System.currentTimeMillis()).coerceAtLeast(0L)

    fun trigger(context: Context, reason: String) {
        val c = context.applicationContext
        main.post {
            Prefs.setLockout(c, System.currentTimeMillis() + Rules.LOCKOUT_MINUTES * 60_000L, reason)
            Prefs.addPenalty(c)
            vibrateHard(c)
            LockoutActivity.show(c)
            // Lock once the vibration has played.
            main.postDelayed({ lockScreen(c) }, 5_500)
        }
    }

    private fun vibrator(c: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) {
            c.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    /** About 5 seconds of maximum-strength vibration. */
    fun vibrateHard(c: Context) {
        val timings = longArrayOf(0, 1200, 120, 1200, 120, 1200, 120, 1500)
        val amps = intArrayOf(0, 255, 0, 255, 0, 255, 0, 255)
        vibrate(c, timings, amps)
    }

    /** A short double buzz for warnings. */
    fun vibrateWarning(c: Context) {
        vibrate(c, longArrayOf(0, 400, 150, 400), intArrayOf(0, 255, 0, 255))
    }

    private fun vibrate(c: Context, timings: LongArray, amps: IntArray) {
        try {
            val v = vibrator(c)
            if (!v.hasVibrator()) return
            val effect = if (v.hasAmplitudeControl()) {
                VibrationEffect.createWaveform(timings, amps, -1)
            } else {
                VibrationEffect.createWaveform(timings, -1)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(
                    effect,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
                )
            }
        } catch (_: Throwable) {
        }
    }

    fun lockScreen(c: Context) {
        val dpm = c.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(c, AdminReceiver::class.java)
        if (dpm != null && dpm.isAdminActive(admin)) {
            try {
                dpm.lockNow()
                return
            } catch (_: SecurityException) {
            }
        }
        GuardAccessibilityService.instance
            ?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
    }
}
