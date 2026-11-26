package com.mossab.focusguard

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private const val REQ_VPN = 10
        private const val UNLOCK_MINUTES = 10
        private const val SETUP_MINUTES = 30
    }

    private lateinit var box: LinearLayout
    private val green = Color.rgb(46, 160, 67)
    private val red = Color.rgb(207, 34, 46)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(box) })
    }

    override fun onResume() {
        super.onResume()
        render()
        if (!Prefs.hasPin(this)) askNewPin(firstTime = true)
    }

    // ------------------------------------------------------------------ screen

    private fun render() {
        box.removeAllViews()
        heading(Rules.APP_NAME, 26f)

        if (Penalty.isLockedOut(this)) {
            val min = (Penalty.remainingMs(this) / 60_000) + 1
            note("Locked for about $min more minute(s): ${Prefs.lockoutReason(this)}", red)
        }

        heading("Setup", 18f)
        val admin = ComponentName(this, AdminReceiver::class.java)
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val power = getSystemService(PowerManager::class.java)

        row("1. Accessibility (sees which app is open)", GuardAccessibilityService.instance != null, "Open") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("Tap Installed apps → Focus Guard → turn it on. If it is greyed out: App info → ⋮ → Allow restricted settings.")
        }
        row("2. Device admin (locks the phone, blocks uninstall)", dpm.isAdminActive(admin), "Turn on") {
            startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Needed to lock the phone as a penalty and to stop Focus Guard being uninstalled.",
                    ),
            )
        }
        row("3. Web filter (adult sites, also incognito)", FilterVpnService.running, "Start") { startVpn() }
        row("4. Always-on VPN (so the filter can't be switched off)", null, "Open") {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
            toast("Tap the ⚙ next to Focus Guard → turn on Always-on VPN. Leave Block connections without VPN off, or banking apps lose internet.")
        }
        row(
            "5. No battery limits (Honor: App launch → Manage manually, all on)",
            power.isIgnoringBatteryOptimizations(packageName), "Allow",
        ) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        }
        row("6. Automatic date, time and time zone", Rules.isAutoTime(this), "Open") {
            startActivity(Intent(Settings.ACTION_DATE_SETTINGS))
        }

        heading("Facebook today", 18f)
        val now = Calendar.getInstance()
        val current = Rules.currentSlot(now)
        val slots = Rules.FACEBOOK_SLOTS.joinToString("   ") {
            if (it == current) "▶${it.label()}" else it.label()
        }
        note(slots)
        note(
            if (current != null && Rules.isAutoTime(this)) "Open now until ${endOf(current)}."
            else "Closed now. Next slot: ${Rules.nextSlot(now).label()} (5 minutes each).",
        )
        note("Attempts outside a slot today: ${Prefs.attempts(this, "facebook")} (the 3rd locks the phone).")

        heading("Blocked chat", 18f)
        note("Chats with “${Rules.BLOCKED_CONTACT_NAMES.joinToString()}” close right away. Attempts today: ${Prefs.attempts(this, "chat")} (the 3rd locks the phone).")

        heading("Always blocked", 18f)
        note("Adult sites and dating apps: very hard vibration, then the phone locks for ${Rules.LOCKOUT_MINUTES} minutes.")
        note("Penalties today: ${Prefs.penaltiesToday(this)}")

        heading("Trusted apps", 18f)
        note("Banking and payment apps (InstaPay, wallets, banks) skip the web filter so they keep working. Apps skipped now: ${FilterVpnService.excludedApps(this).size}.")
        button("Choose more trusted apps") {
            if (Prefs.isUnlocked(this)) pickTrustedApps() else askPin { pickTrustedApps() }
        }

        heading("PIN", 18f)
        if (Prefs.isUnlocked(this)) {
            val until = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(Prefs.unlockedUntil(this)))
            note("Protection is unlocked until $until. Settings for Focus Guard can be changed now.", green)
            button("Lock protection now") { Prefs.setUnlockedUntil(this, 0); render() }
            button("Change PIN") { askNewPin(firstTime = false) }
            if (FilterVpnService.running) button("Stop web filter") { stopVpn() }
        } else {
            button("Unlock protection for $UNLOCK_MINUTES minutes") {
                askPin { Prefs.setUnlockedUntil(this, System.currentTimeMillis() + UNLOCK_MINUTES * 60_000L); render() }
            }
        }
        button("Test the hard vibration") { Penalty.vibrateHard(this) }
    }

    private fun endOf(s: Rules.Slot): String {
        val m = s.startMinute + Rules.SLOT_MINUTES
        return String.format("%02d:%02d", m / 60, m % 60)
    }

    // ------------------------------------------------------------------ VPN

    private fun startVpn() {
        val consent = VpnService.prepare(this)
        if (consent != null) startActivityForResult(consent, REQ_VPN)
        else onActivityResult(REQ_VPN, RESULT_OK, null)
    }

    @Deprecated("Plain Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) {
            Prefs.setVpnWanted(this, true)
            startService(Intent(this, FilterVpnService::class.java))
            box.postDelayed({ render() }, 800)
        }
    }

    private fun stopVpn() {
        Prefs.setVpnWanted(this, false)
        startService(Intent(this, FilterVpnService::class.java).setAction(FilterVpnService.ACTION_STOP))
        box.postDelayed({ render() }, 800)
    }

    // ------------------------------------------------------------------ trusted apps

    private fun pickTrustedApps() {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { (pkg, _) ->
                pkg != packageName && pkg !in Rules.KNOWN_BROWSERS && !Rules.isDatingApp(this, pkg)
            }
            .sortedBy { it.second.lowercase() }
        val chosen = HashSet(Prefs.trustedApps(this))
        val checked = BooleanArray(apps.size) { apps[it].first in chosen }
        AlertDialog.Builder(this)
            .setTitle("Apps the web filter should skip")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, on ->
                if (on) chosen.add(apps[i].first) else chosen.remove(apps[i].first)
            }
            .setPositiveButton("Save") { _, _ ->
                Prefs.setTrustedApps(this, chosen)
                if (FilterVpnService.running) {
                    startService(Intent(this, FilterVpnService::class.java).setAction(FilterVpnService.ACTION_RESTART))
                }
                toast("Saved. Restart the trusted app if it was open.")
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ PIN

    private fun pinField(hint: String) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun askPin(onOk: () -> Unit) {
        val f = pinField("PIN")
        AlertDialog.Builder(this)
            .setTitle("Enter your PIN")
            .setView(wrap(f))
            .setPositiveButton("OK") { _, _ ->
                if (Prefs.checkPin(this, f.text.toString())) onOk() else toast("Wrong PIN")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askNewPin(firstTime: Boolean) {
        val a = pinField("New PIN (4–8 digits)")
        val b = pinField("Repeat PIN")
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(a)
            addView(b)
        }
        AlertDialog.Builder(this)
            .setTitle(if (firstTime) "Create your PIN" else "Change PIN")
            .setMessage(
                if (firstTime) "The PIN unlocks Focus Guard's own settings. Give it to someone you trust if you want it even harder." else null,
            )
            .setView(layout)
            .setCancelable(!firstTime)
            .setPositiveButton("Save") { _, _ ->
                val p = a.text.toString()
                when {
                    p.length !in 4..8 -> { toast("Use 4 to 8 digits"); askNewPin(firstTime) }
                    p != b.text.toString() -> { toast("The PINs don't match"); askNewPin(firstTime) }
                    else -> {
                        Prefs.setPin(this, p)
                        if (firstTime) {
                            Prefs.setUnlockedUntil(this, System.currentTimeMillis() + SETUP_MINUTES * 60_000L)
                            toast("PIN saved. You have $SETUP_MINUTES minutes to finish the setup steps.")
                        } else toast("PIN changed")
                        render()
                    }
                }
            }
            .apply { if (!firstTime) setNegativeButton("Cancel", null) }
            .show()
    }

    private fun wrap(v: View) = LinearLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    // ------------------------------------------------------------------ views

    private fun heading(text: String, sp: Float) {
        box.addView(TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(18), 0, dp(6))
        })
    }

    private fun note(text: String, color: Int? = null) {
        box.addView(TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            color?.let { setTextColor(it) }
            setPadding(0, dp(4), 0, dp(4))
        })
    }

    private fun row(label: String, ok: Boolean?, action: String, onClick: () -> Unit) {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val mark = when (ok) { true -> "✅ "; false -> "❌ "; null -> "• " }
        line.addView(
            TextView(this).apply {
                text = mark + label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (ok != true) {
            line.addView(Button(this).apply {
                text = action
                setOnClickListener { onClick() }
            })
        }
        box.addView(line)
    }

    private fun button(text: String, onClick: () -> Unit) {
        box.addView(Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        })
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
