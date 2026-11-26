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

/**
 * Opening a banking app lands here first: Focus Guard pauses its screen watching
 * (banking apps refuse to run while it is on), then reopens the banking app.
 */
class BankingLaunchActivity : Activity() {

    companion object {
        fun start(c: Context, pkg: String) {
            try {
                c.startActivity(
                    Intent(c, BankingLaunchActivity::class.java)
                        .putExtra("pkg", pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            } catch (_: Exception) {
            }
        }

        fun pause(c: Context, pkg: String) {
            Prefs.setBankingPause(c, true)
            Prefs.setBankingApp(c, pkg)
            Prefs.setBankingPausedAt(c, System.currentTimeMillis())
            FilterVpnService.restart(c)
            GuardAccessibilityService.instance?.pauseForBanking()
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(20, 60, 40))
        }
        box.addView(bigText("🏦", 64f))
        box.addView(bigText("Opening your bank app…", 22f, bold = true))
        setContentView(box)

        handleLaunch(intent)
    }

    // A second open of the bank app reaches here too if this screen is still around.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunch(intent)
    }

    private fun handleLaunch(i: Intent) {
        val pkg = i.getStringExtra("pkg")
        if (pkg == null) {
            finish()
            return
        }
        handler.removeCallbacksAndMessages(null)
        pause(this, pkg)
        // The bank app may have already checked for accessibility and cached the answer:
        // end its process so it starts fresh and checks again with Focus Guard paused.
        handler.postDelayed({ killBank(pkg) }, 500)
        handler.postDelayed({ killBank(pkg) }, 1100)
        handler.postDelayed({
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                try {
                    startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                } catch (_: Exception) {
                }
            }
            finish()
        }, 1600)
    }

    private fun killBank(pkg: String) {
        try {
            getSystemService(android.app.ActivityManager::class.java)?.killBackgroundProcesses(pkg)
        } catch (_: Exception) {
        }
    }
}
