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
        private const val TUN_ADDRESS = "10.111.222.1"
        private const val FAKE_DNS = "10.111.222.2"
        private val UPSTREAMS = listOf("1.1.1.3", "1.0.0.3", "8.8.8.8")

        @Volatile var running = false
            private set

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (tun == null) startTunnel()
        return START_STICKY
    }

    private fun startTunnel() {
        val t = try {
            Builder()
                .setSession(Rules.APP_NAME)
                .setMtu(4200)
                .addAddress(TUN_ADDRESS, 32)
                .addDnsServer(FAKE_DNS)
                .addRoute(FAKE_DNS, 32)
                .setBlocking(true)
                .establish()
        } catch (_: Exception) {
            null
        } ?: return
        tun = t
        running = true
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
