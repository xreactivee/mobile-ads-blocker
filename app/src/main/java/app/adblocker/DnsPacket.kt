package app.adblocker

/**
 * Minimal IPv4/UDP/DNS handling: just enough to read the name in a DNS query
 * coming out of the VPN tunnel and to write replies back into it.
 */
object DnsPacket {

    class Query(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dns: ByteArray,        // UDP payload: the raw DNS message
        val domain: String,        // lowercase, e.g. "ads.example.com"
        val questionEnd: Int,      // offset in [dns] right after the first question
    )

    /** Returns the DNS query in [buf], or null if it is not an IPv4 UDP packet to port 53. */
    fun parse(buf: ByteArray, len: Int): Query? {
        if (len < 20 || buf[0].toInt() ushr 4 != 4 || buf[9].toInt() != 17) return null
        val ihl = (buf[0].toInt() and 0x0f) * 4
        if (len < ihl + 8 + 12 || u16(buf, ihl + 2) != 53) return null
        val udpLen = u16(buf, ihl + 4)
        if (udpLen < 8 + 12) return null
        val dns = buf.copyOfRange(ihl + 8, minOf(len, ihl + udpLen))
        if (u16(dns, 4) < 1) return null // no question

        val name = StringBuilder()
        var i = 12
        while (true) {
            if (i >= dns.size) return null
            val n = dns[i].toInt() and 0xff
            if (n == 0) break
            if (n > 63 || i + 1 + n > dns.size) return null // queries never use compression
            if (name.isNotEmpty()) name.append('.')
            name.append(String(dns, i + 1, n, Charsets.US_ASCII))
            i += 1 + n
        }
        val questionEnd = i + 1 + 4 // zero label + QTYPE + QCLASS
        if (questionEnd > dns.size) return null

        return Query(
            srcIp = buf.copyOfRange(12, 16),
            dstIp = buf.copyOfRange(16, 20),
            srcPort = u16(buf, ihl),
            dns = dns,
            domain = name.toString().lowercase(),
            questionEnd = questionEnd,
        )
    }

    /** A complete IP packet answering [q] with NXDOMAIN ("no such domain"). */
    fun blockedReply(q: Query): ByteArray {
        val dns = q.dns.copyOf(q.questionEnd)       // header + first question only
        dns[2] = (dns[2].toInt() or 0x80).toByte()  // QR = response, keep opcode and RD
        dns[3] = 0x83.toByte()                      // RA + RCODE 3 (NXDOMAIN)
        dns[4] = 0; dns[5] = 1                      // QDCOUNT = 1
        for (i in 6 until 12) dns[i] = 0            // AN/NS/AR = 0, drops the EDNS record
        return wrapReply(q, dns)
    }

    /** Wraps the DNS message [dns] in IPv4 + UDP headers addressed back to the sender of [q]. */
    fun wrapReply(q: Query, dns: ByteArray): ByteArray {
        val p = ByteArray(28 + dns.size)
        p[0] = 0x45 // IPv4, 20-byte header
        put16(p, 2, p.size)
        p[8] = 64   // TTL
        p[9] = 17   // UDP
        q.dstIp.copyInto(p, 12)
        q.srcIp.copyInto(p, 16)
        put16(p, 10, checksum(p, 0, 20))
        put16(p, 20, 53)
        put16(p, 22, q.srcPort)
        put16(p, 24, 8 + dns.size)
        // UDP checksum left at 0: means "no checksum", which is legal for IPv4.
        dns.copyInto(p, 28)
        return p
    }

    /** Internet checksum (RFC 1071) over [len] bytes of [b]; len must be even. */
    fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0
        for (i in off until off + len step 2) sum += u16(b, i)
        while (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv() and 0xffff
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)

    private fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 8).toByte()
        b[i + 1] = v.toByte()
    }
}
