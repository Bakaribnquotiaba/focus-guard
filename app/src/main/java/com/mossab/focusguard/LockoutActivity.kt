package com.mossab.focusguard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Shown during the 15-minute lockout. It locks the screen again a few seconds after it appears. */
class LockoutActivity : Activity() {

    companion object {
        fun show(c: Context) {
            try {
                c.startActivity(
                    Intent(c, LockoutActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
                )
            } catch (_: Exception) {
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var timer: TextView
    private lateinit var reason: TextView

    private val lockAgain = Runnable {
        if (Penalty.isLockedOut(this)) Penalty.lockScreen(this)
    }

    private val update = object : Runnable {
        override fun run() {
            val ms = Penalty.remainingMs(this@LockoutActivity)
            if (ms <= 0) {
                finish()
                return
            }
            val s = ms / 1000
            timer.text = String.format("%02d:%02d", s / 60, s % 60)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(90, 10, 16))
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        box.addView(bigText("🔒", 72f))
        box.addView(bigText("Phone locked", 30f, bold = true))
        timer = bigText("", 56f, bold = true)
        box.addView(timer)
        reason = bigText("", 17f, Color.rgb(255, 210, 210))
        box.addView(reason)
        box.addView(
            bigText(
                "Emergency calls still work from the lock screen.",
                14f, Color.rgb(255, 180, 180),
            ),
        )
        setContentView(box)
    }

    override fun onResume() {
        super.onResume()
        if (!Penalty.isLockedOut(this)) {
            finish()
            return
        }
        reason.text = Prefs.lockoutReason(this)
        handler.post(update)
        handler.postDelayed(lockAgain, 6000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(update)
        handler.removeCallbacks(lockAgain)
    }

    @Deprecated("Back does nothing while locked.")
    override fun onBackPressed() {
        if (!Penalty.isLockedOut(this)) finish()
    }
}
