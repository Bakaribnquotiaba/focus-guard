package com.mossab.focusguard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout

/** A full-screen "this is blocked" message. */
class BlockActivity : Activity() {

    companion object {
        fun show(c: Context, title: String, message: String) {
            try {
                c.startActivity(
                    Intent(c, BlockActivity::class.java)
                        .putExtra("title", title)
                        .putExtra("message", message)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            } catch (_: Exception) {
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        render(intent)
    }

    private fun render(i: Intent) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(27, 31, 42))
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        box.addView(bigText("⛔", 64f))
        box.addView(bigText(i.getStringExtra("title") ?: "Blocked", 26f, bold = true))
        box.addView(bigText(i.getStringExtra("message") ?: "", 17f, Color.rgb(200, 205, 215)))
        box.addView(Button(this).apply {
            text = "OK"
            setOnClickListener { goHomeAndFinish() }
        })
        setContentView(box)
    }

    private fun goHomeAndFinish() {
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    @Deprecated("Back goes home, never back into the blocked app.")
    override fun onBackPressed() {
        goHomeAndFinish()
    }
}
