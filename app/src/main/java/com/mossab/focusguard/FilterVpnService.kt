package com.mossab.focusguard

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Local DNS filter. Only DNS traffic goes through it, so it works in every browser
 * and in incognito mode. Allowed names go to Cloudflare for Families, which also blocks adult sites.
 */
class FilterVpnService : VpnService() {

    companion object {
        const val ACTION_STOP = "com.mossab.focusguard.STOP_VPN"
        const val ACTION_RESTART = "com.mossab.focusguard.RESTART_VPN"

        /** Banking apps found on the phone plus the ones picked by hand. */
        fun excludedApps(c: Context): Set<String> {
            val set = HashSet(Prefs.trustedApps(c))
            try {
                @Suppress("DEPRECATION")
                c.packageManager.getInstalledApplications(0).forEach {
                    if (it.packageName != c.packageName && Rules.isFinanceApp(c, it.packageName)) {
                        set.add(it.packageName)
                    }
                }
            } catch (_: Exception) {
            }
            return set
        }
        private const val TUN_ADDRESS = "10.111.222.1"
        private const val FAKE_DNS = "10.111.222.2"
        private val UPSTREAMS = listOf("1.1.1.3", "1.0.0.3", "8.8.8.8")

        @Volatile var running = false
            private set

        /** True while only banking apps have internet. */
        @Volatile var bankingOnly = false
            private set

        private const val TUN_ADDRESS6 = "fd00:6f63:7573::1"
        private const val WATCHING_GRACE_MS = 60_000L

        fun restart(c: Context) {
            if (!running) return
            try {
                c.startService(Intent(c, FilterVpnService::class.java).setAction(ACTION_RESTART))
            } catch (_: Exception) {
            }
        }

        fun startIfPermitted(c: Context) {
            if (!Prefs.vpnWanted(c)) return
            if (prepare(c) != null) return
            try {
                c.startService(Intent(c, FilterVpnService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var pool: ExecutorService? = null
    private val main = Handler(Looper.getMainLooper())
    private var watchingLostAt = 0L

    /**
     * When Focus Guard's screen watching is off (banking pause, or switched off in settings
     * without the PIN), every app except banking apps loses internet.
     */
    private fun wantBankingOnly(): Boolean {
        if (Prefs.bankingPause(this)) return true
        if (GuardAccessibilityService.instance != null || Prefs.isUnlocked(this)) {
            watchingLostAt = 0L
            return false
        }
        val now = System.currentTimeMillis()
        if (watchingLostAt == 0L) watchingLostAt = now
        return now - watchingLostAt > WATCHING_GRACE_MS
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (running && wantBankingOnly() != bankingOnly) {
                shutdown()
                startTunnel()
            }
            if (running) main.postDelayed(this, 15_000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESTART) shutdown()
        if (tun == null) startTunnel()
        return START_STICKY
    }

    private fun startTunnel() {
        val excluded = excludedApps(this)
        val cutOff = wantBankingOnly()
        val t = try {
            Builder()
                .apply {
                    for (pkg in excluded) {
                        try {
                            addDisallowedApplication(pkg)
                        } catch (_: Exception) {
                        }
                    }
                }
                .setSession(Rules.APP_NAME)
                .setMtu(4200)
                .addAddress(TUN_ADDRESS, 32)
                .addDnsServer(FAKE_DNS)
                .addRoute(FAKE_DNS, 32)
                .apply {
                    if (cutOff) {
                        // Everything goes into the tunnel and is dropped; banking apps bypass it.
                        addAddress(TUN_ADDRESS6, 128)
                        addRoute("0.0.0.0", 0)
                        addRoute("::", 0)
                    }
                }
                .setBlocking(true)
                .establish()
        } catch (_: Exception) {
            null
        } ?: return
        tun = t
        running = true
        bankingOnly = cutOff
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 15_000)
        pool = Executors.newFixedThreadPool(6)
        reader = Thread({ loop(t) }, "dns-filter").also { it.start() }
    }

    private fun loop(t: ParcelFileDescriptor) {
        Blocklist.ensureLoaded(this)
        val input = FileInputStream(t.fileDescriptor)
        val output = FileOutputStream(t.fileDescriptor)
        val buf = ByteArray(32767)
        while (running) {
            val n = try {
                input.read(buf)
            } catch (_: Exception) {
                break
            }
            if (n <= 0) continue
            val q = DnsPacket.parseQuery(buf, n) ?: continue
            if (bankingOnly) {
                write(output, DnsPacket.wrap(q, DnsPacket.blockedResponse(q)))
                continue
            }
            val cat = Blocklist.check(q.name)
            if (cat != Blocklist.Category.NONE) {
                write(output, DnsPacket.wrap(q, DnsPacket.blockedResponse(q)))
                if (cat == Blocklist.Category.ADULT || cat == Blocklist.Category.DATING) {
                    report(q.name, cat)
                }
                continue
            }
            try {
                pool?.execute { forward(q, output) }
            } catch (_: Exception) {
            }
        }
    }

    private fun forward(q: DnsPacket.Query, output: FileOutputStream) {
        for (server in UPSTREAMS) {
            try {
                DatagramSocket().use { s ->
                    protect(s)
                    s.soTimeout = 4000
                    s.send(DatagramPacket(q.dns, q.dns.size, InetAddress.getByName(server), 53))
                    val buf = ByteArray(4096)
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val resp = buf.copyOf(p.length)
                    if (DnsPacket.answerIsNullIp(resp)) report(q.name, Blocklist.Category.ADULT)
                    write(output, DnsPacket.wrap(q, resp))
                }
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun write(output: FileOutputStream, packet: ByteArray) {
        synchronized(output) {
            try {
                output.write(packet)
            } catch (_: Exception) {
            }
        }
    }

    private fun report(domain: String, cat: Blocklist.Category) {
        main.post { GuardAccessibilityService.instance?.onBlockedDomain(domain, cat) }
    }

    private fun shutdown() {
        running = false
        main.removeCallbacks(watchdog)
        try { tun?.close() } catch (_: Exception) { }
        tun = null
        pool?.shutdownNow()
        pool = null
    }

    override fun onRevoke() {
        shutdown()
        super.onRevoke()
        // Turned off from system settings without the PIN: switch it back on.
        if (!Prefs.isUnlocked(this) && Prefs.vpnWanted(this)) {
            val app = applicationContext
            main.postDelayed({ startIfPermitted(app) }, 3000)
        }
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }
}
