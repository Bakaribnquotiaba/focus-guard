package com.mossab.focusguard

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import java.util.Calendar

/** Everything the app enforces, in one place. */
object Rules {

    const val APP_NAME = "Focus Guard"

    // ---- Facebook: only open in short daily slots -------------------------------------------

    val FACEBOOK_PACKAGES = setOf("com.facebook.katana", "com.facebook.lite")
    private val FACEBOOK_URL_MARKERS = listOf("facebook.com", "fb.com/", "fb.watch")

    data class Slot(val hour: Int, val minute: Int) {
        val startMinute: Int get() = hour * 60 + minute
        fun label(): String = String.format("%02d:%02d", hour, minute)
    }

    const val SLOT_MINUTES = 5

    /** 12 slots x 5 minutes = 1 hour of Facebook per day. */
    val FACEBOOK_SLOTS = listOf(
        Slot(9, 30), Slot(11, 0), Slot(12, 30), Slot(13, 30), Slot(15, 0), Slot(16, 30),
        Slot(17, 30), Slot(18, 30), Slot(19, 30), Slot(20, 30), Slot(21, 30), Slot(22, 30),
    )

    /** On this attempt outside a slot, the penalty starts. */
    const val FACEBOOK_ATTEMPTS_FOR_PENALTY = 3

    // ---- Chat with a blocked contact --------------------------------------------------------

    val BLOCKED_CONTACT_NAMES = listOf("blue sea")

    /** "More than 2 times" means the 3rd attempt starts the penalty. */
    const val CHAT_ATTEMPTS_FOR_PENALTY = 3

    val CHAT_PACKAGES = setOf(
        "com.google.android.apps.dynamite", // Google Chat
        "com.google.android.gm",            // Gmail (has Chat inside)
        "com.google.android.apps.messaging",
        "com.whatsapp", "com.whatsapp.w4b",
        "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram",
        "com.facebook.orca", "com.facebook.mlite",
        "com.instagram.android",
        "com.viber.voip", "com.snapchat.android", "com.discord", "com.skype.raider",
        "com.microsoft.teams", "jp.naver.line.android", "com.imo.android.imoim",
        "org.thoughtcrime.securesms", "com.android.mms", "com.hihonor.mms", "com.huawei.message",
        "com.samsung.android.messaging",
    )

    // ---- Penalty ------------------------------------------------------------------------------

    const val LOCKOUT_MINUTES = 15

    // ---- Dating apps ------------------------------------------------------------------------

    private val DATING_PACKAGES = setOf(
        "com.tinder", "com.bumble.app", "com.badoo.mobile", "co.hinge.app", "com.okcupid.okcupid",
        "com.pof.android", "com.match.android.matchmobile", "com.grindrapp.android",
        "com.ftw_and_co.happn", "com.hily.app", "com.p1.mobile.putong", "com.azarlive.android",
        "com.jaumo", "net.lovoo.android", "com.muzmatch.muzmatchapp", "com.coffeemeetsbagel",
        "co.feeld", "com.myyearbook.m", "com.skout.android", "com.waplog.social", "com.mico",
        "com.taggedapp", "com.zoosk.zoosk", "com.eharmony",
    )
    private val DATING_PACKAGE_WORDS = listOf(
        "tinder", "bumble", "badoo", "hinge", "okcupid", "grindr", "dating", "happn", "hily",
        "putong", "tantan", "azarlive", "jaumo", "lovoo", "muzmatch", "coffeemeetsbagel", "feeld",
        "taimi", "yubo", "skout", "meetme", "plentyoffish", "flirt",
    )
    private val DATING_LABEL_WORDS = listOf(
        "dating", "tinder", "bumble", "badoo", "hinge", "okcupid", "grindr", "happn", "hily",
        "tantan", "azar", "jaumo", "lovoo", "muzz", "flirt", "singles", "random video chat",
        "تعارف", "مواعدة",
    )

    fun isDatingApp(context: Context, pkg: String): Boolean {
        val p = pkg.lowercase()
        if (p in DATING_PACKAGES) return true
        if (DATING_PACKAGE_WORDS.any { p.contains(it) }) return true
        val label = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().lowercase()
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
        return DATING_LABEL_WORDS.any { label.contains(it) }
    }

    // ---- Adult content ----------------------------------------------------------------------

    private val ADULT_WORDS = listOf(
        "porn", "xxx", "xvideo", "xnxx", "xhamster", "redtube", "youporn", "spankbang", "brazzers",
        "bangbros", "onlyfans", "chaturbate", "stripchat", "bongacams", "livejasmin", "camgirl",
        "hentai", "nsfw", "sex", "nude", "naked", "milf", "boobs", "pussy", "fuck", "escort",
        "سكس", "اباحي", "إباحي", "افلام نار",
    )

    /** Innocent words that contain an adult word; removed before checking. */
    private val ADULT_EXCEPTIONS = listOf(
        "essex", "sussex", "middlesex", "wessex", "sextant", "sexton", "unisex", "sextet",
    )

    fun isAdultText(text: String): Boolean {
        var t = text.lowercase()
        for (e in ADULT_EXCEPTIONS) t = t.replace(e, " ")
        return ADULT_WORDS.any { t.contains(it) }
    }

    fun isFacebookUrl(text: String): Boolean {
        val t = text.lowercase()
        return FACEBOOK_URL_MARKERS.any { t.contains(it) }
    }

    fun mentionsBlockedContact(text: String): Boolean {
        val t = text.lowercase().replace(Regex("\\s+"), " ")
        return BLOCKED_CONTACT_NAMES.any { t.contains(it) }
    }

    // ---- Banking and payment apps (skip the web filter; many refuse to run with a VPN on) ----

    val FINANCE_PACKAGES = setOf(
        "com.egyptianbanks.instapay", "com.ebc.instapay", "com.instapay.egypt",
        "com.emeint.android.myservices", // Vodafone Cash
        "com.fawry.myfawry", "com.fawry.retailer", "com.valu.app", "com.paymob.wallet",
    )
    private val FINANCE_WORDS = listOf(
        "bank", "instapay", "insta pay", "wallet", "cash", "fawry", "valu", "pay",
        "بنك", "مصرف", "انستا", "محفظة", "كاش", "فوري",
    )

    fun isFinanceApp(context: Context, pkg: String): Boolean {
        val p = pkg.lowercase()
        if (p in FINANCE_PACKAGES || p.contains("instapay") || p.contains("bank")) return true
        if (p in KNOWN_BROWSERS) return false
        val label = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().lowercase()
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
        return FINANCE_WORDS.any { label.contains(it) }
    }

    // ---- Browsers ---------------------------------------------------------------------------

    val KNOWN_BROWSERS = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
        "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.focus", "org.mozilla.fennec_fdroid",
        "com.brave.browser", "com.microsoft.emmx", "com.opera.browser", "com.opera.mini.native",
        "com.opera.gx", "com.sec.android.app.sbrowser", "com.duckduckgo.mobile.android",
        "com.vivaldi.browser", "com.kiwibrowser.browser", "com.UCMobile.intl", "com.uc.browser.en",
        "com.hihonor.browser", "com.huawei.browser", "com.mi.globalbrowser", "com.yandex.browser",
        "org.torproject.torbrowser", "mark.via.gp", "com.google.android.googlequicksearchbox",
    )

    /** Parts of a view id that mean "this is the address bar". */
    val URL_BAR_ID_HINTS = listOf("url", "omnibox", "omnibar", "location_bar", "address")

    // ---- Time ---------------------------------------------------------------------------------

    /** Changing the clock or time zone must not open a Facebook slot. */
    fun isAutoTime(context: Context): Boolean {
        val cr = context.contentResolver
        return Settings.Global.getInt(cr, Settings.Global.AUTO_TIME, 0) == 1 &&
            Settings.Global.getInt(cr, Settings.Global.AUTO_TIME_ZONE, 0) == 1
    }

    private fun minuteOfDay(now: Calendar): Int =
        now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

    fun currentSlot(now: Calendar = Calendar.getInstance()): Slot? {
        val m = minuteOfDay(now)
        return FACEBOOK_SLOTS.firstOrNull { m >= it.startMinute && m < it.startMinute + SLOT_MINUTES }
    }

    fun nextSlot(now: Calendar = Calendar.getInstance()): Slot {
        val m = minuteOfDay(now)
        return FACEBOOK_SLOTS.firstOrNull { it.startMinute > m } ?: FACEBOOK_SLOTS.first()
    }

    fun facebookAllowed(context: Context): Boolean =
        isAutoTime(context) && currentSlot() != null
}
