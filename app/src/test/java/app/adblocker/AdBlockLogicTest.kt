package app.adblocker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockLogicTest {

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)

    /** IPv4/UDP DNS query 10.0.0.2:40000 -> 10.0.0.1:53, id 0x1234, with an EDNS OPT record. */
    private fun query(domain: String, protocol: Int = 17): ByteArray {
        val qname = domain.split('.').flatMap { listOf(it.length.toByte()) + it.toByteArray().toList() } + 0.toByte()
        val dns = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 1) +
            qname.toByteArray() +
            byteArrayOf(0, 1, 0, 1) +                   // QTYPE A, QCLASS IN
            byteArrayOf(0, 0, 41, 0x10, 0, 0, 0, 0, 0, 0, 0) // OPT record
        val p = ByteArray(28 + dns.size)
        p[0] = 0x45; p[8] = 64; p[9] = protocol.toByte()
        p[2] = (p.size shr 8).toByte(); p[3] = p.size.toByte()
        byteArrayOf(10, 0, 0, 2).copyInto(p, 12)
        byteArrayOf(10, 0, 0, 1).copyInto(p, 16)
        p[20] = (40000 shr 8).toByte(); p[21] = 40000.toByte()
        p[22] = 0; p[23] = 53
        p[24] = ((8 + dns.size) shr 8).toByte(); p[25] = (8 + dns.size).toByte()
        dns.copyInto(p, 28)
        return p
    }

    @Test
    fun parsesDomainFromQuery() {
        val q = DnsPacket.parse(query("Ads.Example.com"), query("Ads.Example.com").size)!!
        assertEquals("ads.example.com", q.domain)
        assertEquals(40000, q.srcPort)
    }

    @Test
    fun ignoresNonUdpAndTruncatedPackets() {
        val p = query("example.com")
        assertNull(DnsPacket.parse(query("example.com", protocol = 6), p.size))
        assertNull(DnsPacket.parse(p, 30))
    }

    @Test
    fun blockedReplyIsValidNxdomain() {
        val p = query("ads.example.com")
        val r = DnsPacket.blockedReply(DnsPacket.parse(p, p.size)!!)

        assertEquals(0, DnsPacket.checksum(r, 0, 20)) // IPv4 header checksum verifies
        assertEquals(r.size, u16(r, 2))
        assertArrayEquals(byteArrayOf(10, 0, 0, 1), r.copyOfRange(12, 16)) // src/dst swapped
        assertArrayEquals(byteArrayOf(10, 0, 0, 2), r.copyOfRange(16, 20))
        assertEquals(53, u16(r, 20))
        assertEquals(40000, u16(r, 22))
        assertEquals(r.size - 20, u16(r, 24))

        val dns = r.copyOfRange(28, r.size)
        assertEquals(0x1234, u16(dns, 0))           // same id
        assertTrue(dns[2].toInt() and 0x80 != 0)    // QR = response
        assertEquals(3, dns[3].toInt() and 0x0f)    // NXDOMAIN
        assertEquals(1, u16(dns, 4))                // one question
        assertEquals(0, u16(dns, 10))               // OPT record stripped
        assertEquals(12 + 17 + 4, dns.size)         // header + qname + type/class
    }

    @Test
    fun blocklistMatchesListedDomainsAndSubdomains() {
        val hosts = """
            # comment
            127.0.0.1 localhost
            0.0.0.0 0.0.0.0
            0.0.0.0 ads.example.com # trailing comment

            0.0.0.0 Tracker.NET other.org
        """.trimIndent()
        val list = Blocklist.parse(hosts.reader())

        assertEquals(3, list.size)
        assertTrue(list.isBlocked("ads.example.com"))
        assertTrue(list.isBlocked("x.ads.example.com"))
        assertTrue(list.isBlocked("tracker.net"))
        assertTrue(list.isBlocked("other.org"))
        assertFalse(list.isBlocked("example.com"))
        assertFalse(list.isBlocked("localhost"))
        assertFalse(list.isBlocked("notads.example.com"))
    }

    @Test
    fun statsCountPerAppAndKeepNewestRecentBlocks() {
        val stats = BlockStats(mapOf("com.game" to 5L))
        stats.record("com.game", "ads.one.com", 1)
        stats.record("com.browser", "ads.two.com", 2)
        repeat(60) { stats.record("com.browser", "ads$it.com", 3L + it) }

        assertEquals(67, stats.total())
        assertEquals(listOf("com.browser" to 61L, "com.game" to 6L), stats.byApp())
        assertEquals(mapOf("com.browser" to 61L, "com.game" to 6L), stats.snapshot())

        val recent = stats.recent()
        assertEquals(BlockStats.RECENT_MAX, recent.size)
        assertEquals("ads59.com", recent.first().domain) // newest first
        assertEquals("com.browser", recent.first().app)
        assertEquals(62L, recent.first().time)
    }

    @Test
    fun blocklistMergesHostsAndPlainDomainLists() {
        val hosts = "0.0.0.0 ads.example.com\n::1 localhost\n"
        val domains = "# Title: plain list\nadmatic.com.tr\n\nTracker.NET\n"
        val list = Blocklist.parse(hosts.reader(), domains.reader())

        assertEquals(3, list.size)
        assertTrue(list.isBlocked("ads.example.com"))
        assertTrue(list.isBlocked("static.cdn.admatic.com.tr"))
        assertTrue(list.isBlocked("tracker.net"))
        assertFalse(list.isBlocked("localhost"))
        assertFalse(list.isBlocked("example.com"))
    }
}
