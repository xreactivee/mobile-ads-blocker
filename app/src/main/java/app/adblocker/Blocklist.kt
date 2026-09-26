package app.adblocker

import java.io.Reader

class Blocklist(private val domains: Set<String>) {

    val size get() = domains.size

    fun isBlocked(domain: String): Boolean {
        var d = domain
        while (true) {
            if (d in domains) return true
            val dot = d.indexOf('.')
            if (dot < 0) return false
            d = d.substring(dot + 1)
        }
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        fun parse(vararg readers: Reader): Blocklist {
            val domains = HashSet<String>()
            for (reader in readers) reader.forEachLine { line ->
                val parts = line.substringBefore('#').trim().split(WHITESPACE)
                val hosts = when {
                    parts.size == 1 -> parts                                         // plain domain list
                    parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" -> parts.drop(1) // hosts file
                    else -> emptyList()
                }
                for (host in hosts) {
                    // Skips "", "localhost", "broadcasthost", "0.0.0.0" and similar non-domains.
                    if ('.' in host && host != "0.0.0.0") domains += host.lowercase()
                }
            }
            return Blocklist(domains)
        }
    }
}
