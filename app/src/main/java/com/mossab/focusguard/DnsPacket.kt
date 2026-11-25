package com.mossab.focusguard

/** Minimal IPv4/UDP/DNS handling for the local DNS filter. */
object DnsPacket {

    class Query(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dns: ByteArray,
        val name: String,
        val questionEnd: Int,
    )

    private fun u16(b: ByteArray, off: Int) =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun put16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v shr 8).toByte()
        b[off + 1] = v.toByte()
    }

    fun parseQuery(pkt: ByteArray, len: Int): Query? {
        if (len < 20 + 8 + 12) return null
        if ((pkt[0].toInt() shr 4) and 0xF != 4) return null
        val ihl = (pkt[0].toInt() and 0xF) * 4
        if (pkt[9].toInt() and 0xFF != 17) return null // UDP only
        if (len < ihl + 8 + 12) return null
        if (u16(pkt, ihl + 2) != 53) return null
        val udpLen = u16(pkt, ihl + 4)
        val dnsOff = ihl + 8
        val dnsLen = minOf(udpLen - 8, len - dnsOff)
        if (dnsLen < 12) return null
        val dns = pkt.copyOfRange(dnsOff, dnsOff + dnsLen)
        val sb = StringBuilder()
        var i = 12
        while (true) {
            if (i >= dns.size) return null
            val l = dns[i].toInt() and 0xFF
            if (l == 0) { i += 1; break }
            if (l and 0xC0 != 0) return null
            if (i + 1 + l > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(dns, i + 1, l, Charsets.US_ASCII))
            i += 1 + l
        }
        val qEnd = i + 4
        if (qEnd > dns.size) return null
        return Query(
            srcIp = pkt.copyOfRange(12, 16),
            dstIp = pkt.copyOfRange(16, 20),
            srcPort = u16(pkt, ihl),
            dns = dns,
            name = sb.toString(),
            questionEnd = qEnd,
        )
    }

    /** NXDOMAIN answer for a blocked name. */
    fun blockedResponse(q: Query): ByteArray {
        val r = q.dns.copyOf(q.questionEnd)
        r[2] = ((q.dns[2].toInt() and 0x79) or 0x80).toByte() // QR=1, keep opcode and RD
        r[3] = 0x83.toByte() // RA=1, RCODE=3 (NXDOMAIN)
        put16(r, 4, 1)
        put16(r, 6, 0)
        put16(r, 8, 0)
        put16(r, 10, 0)
        return r
    }

    /** Wraps a DNS payload into an IPv4/UDP packet going back to the asking app. */
    fun wrap(q: Query, payload: ByteArray): ByteArray {
        val total = 28 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45
        put16(p, 2, total)
        p[6] = 0x40 // don't fragment
        p[8] = 64 // TTL
        p[9] = 17 // UDP
        System.arraycopy(q.dstIp, 0, p, 12, 4)
        System.arraycopy(q.srcIp, 0, p, 16, 4)
        var sum = 0
        for (i in 0 until 20 step 2) sum += u16(p, i)
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        put16(p, 10, sum.inv() and 0xFFFF)
        put16(p, 20, 53)
        put16(p, 22, q.srcPort)
        put16(p, 24, 8 + payload.size)
        // UDP checksum 0 = not used (allowed on IPv4)
        System.arraycopy(payload, 0, p, 28, payload.size)
        return p
    }

    private fun skipName(b: ByteArray, start: Int): Int {
        var i = start
        while (i < b.size) {
            val l = b[i].toInt() and 0xFF
            if (l == 0) return i + 1
            if (l and 0xC0 == 0xC0) return i + 2
            i += 1 + l
        }
        return -1
    }

    /** True if the upstream family filter answered 0.0.0.0, which it does for adult sites. */
    fun answerIsNullIp(resp: ByteArray): Boolean {
        if (resp.size < 12) return false
        val qd = u16(resp, 4)
        val an = u16(resp, 6)
        var i = 12
        repeat(qd) {
            i = skipName(resp, i)
            if (i < 0) return false
            i += 4
        }
        repeat(an) {
            i = skipName(resp, i)
            if (i < 0 || i + 10 > resp.size) return false
            val type = u16(resp, i)
            val rdLen = u16(resp, i + 8)
            val rd = i + 10
            if (rd + rdLen > resp.size) return false
            if (type == 1 && rdLen == 4 &&
                resp[rd].toInt() == 0 && resp[rd + 1].toInt() == 0 &&
                resp[rd + 2].toInt() == 0 && resp[rd + 3].toInt() == 0
            ) return true
            i = rd + rdLen
        }
        return false
    }
}
