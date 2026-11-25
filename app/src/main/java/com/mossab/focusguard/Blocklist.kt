package com.mossab.focusguard

import android.content.Context

/** Domain rules used by the web filter. */
object Blocklist {
    enum class Category { NONE, ADULT, DATING, BYPASS }

    @Volatile private var adultHosts: Set<String> = emptySet()
    @Volatile private var loaded = false

    private val ADULT_SEED = setOf(
        "pornhub.com", "pornhub.org", "xvideos.com", "xvideos2.com", "xvideos3.com", "xvideos.es",
        "xnxx.com", "xnxx.tv", "xhamster.com", "xhamster2.com", "xhamster3.com", "xhamster.desi",
        "redtube.com", "youporn.com", "tube8.com", "spankbang.com", "eporner.com", "beeg.com",
        "brazzers.com", "onlyfans.com", "fansly.com", "chaturbate.com", "stripchat.com",
        "bongacams.com", "livejasmin.com", "cam4.com", "myfreecams.com", "porn.com", "tnaflix.com",
        "hqporner.com", "txxx.com", "motherless.com", "rule34.xxx", "nhentai.net", "erome.com",
        "porntrex.com", "sxyprn.com", "daftsex.com", "noodlemagazine.com", "4tube.com",
        "porntube.com", "youjizz.com", "drtuber.com", "sunporno.com", "ixxx.com", "literotica.com",
        "manyvids.com", "clips4sale.com", "fapello.com", "thothub.to", "xxxbunker.com",
    )

    private val ADULT_TLDS = setOf("xxx", "porn", "sex", "adult", "sexy")

    private val DATING = setOf(
        "tinder.com", "gotinder.com", "bumble.com", "badoo.com", "hinge.co", "okcupid.com",
        "pof.com", "plentyoffish.com", "match.com", "grindr.com", "happn.com", "hily.com",
        "tantanapp.com", "azarlive.com", "jaumo.com", "lovoo.com", "meetme.com", "zoosk.com",
        "eharmony.com", "coffeemeetsbagel.com", "feeld.co", "taimi.com", "muzz.com", "muzmatch.com",
        "skout.com", "waplog.com", "yubo.live",
    )

    /** Encrypted-DNS services a browser could use to go around the filter. */
    private val BYPASS = setOf(
        "dns.google", "dns.google.com", "cloudflare-dns.com", "mozilla.cloudflare-dns.com",
        "chrome.cloudflare-dns.com", "one.one.one.one", "dns.quad9.net", "dns9.quad9.net",
        "dns10.quad9.net", "dns11.quad9.net", "dns.adguard.com", "dns.adguard-dns.com",
        "doh.opendns.com", "dns.nextdns.io", "doh.cleanbrowsing.org", "doh.dns.sb",
        "dns.alidns.com", "dns.controld.com", "doh.mullvad.net", "dns.mullvad.net",
        "use-application-dns.net", // tells Firefox not to switch on its own encrypted DNS
    )

    fun ensureLoaded(c: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val set = HashSet<String>(ADULT_SEED)
            try {
                c.assets.open("adult_hosts.txt").bufferedReader().useLines { lines ->
                    for (raw in lines) {
                        val line = raw.substringBefore('#').trim()
                        if (line.isEmpty()) continue
                        val parts = line.split(Regex("\\s+"))
                        val host = (if (parts.size >= 2) parts[1] else parts[0]).lowercase()
                        if (host.contains('.') && host != "0.0.0.0") set.add(host)
                    }
                }
            } catch (_: Exception) {
            }
            adultHosts = set
            loaded = true
        }
    }

    fun check(domainIn: String): Category {
        val domain = domainIn.lowercase().trimEnd('.')
        if (domain.isEmpty()) return Category.NONE
        var d = domain
        while (true) {
            if (d in BYPASS) return Category.BYPASS
            if (d in DATING) return Category.DATING
            if (d in adultHosts) return Category.ADULT
            val dot = d.indexOf('.')
            if (dot < 0) break
            d = d.substring(dot + 1)
        }
        if (domain.substringAfterLast('.') in ADULT_TLDS) return Category.ADULT
        if (Rules.isAdultText(domain)) return Category.ADULT
        return Category.NONE
    }
}
