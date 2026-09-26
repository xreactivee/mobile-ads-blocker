package app.adblocker

/**
 * Blocked-request counters per app (keyed by package name) and the most recent blocks.
 * Thread-safe; persisting the counters is left to the caller via [snapshot].
 */
class BlockStats(initial: Map<String, Long> = emptyMap()) {

    class Entry(val domain: String, val app: String, val time: Long)

    private val perApp = HashMap(initial)
    private val recent = ArrayDeque<Entry>()

    @Synchronized
    fun record(app: String, domain: String, time: Long) {
        perApp[app] = (perApp[app] ?: 0L) + 1
        recent.addFirst(Entry(domain, app, time))
        if (recent.size > RECENT_MAX) recent.removeLast()
    }

    @Synchronized
    fun total(): Long = perApp.values.sum()

    /** Apps with their counts, most blocked first. */
    @Synchronized
    fun byApp(): List<Pair<String, Long>> = perApp.entries.sortedByDescending { it.value }.map { it.key to it.value }

    /** Newest first. */
    @Synchronized
    fun recent(): List<Entry> = recent.toList()

    @Synchronized
    fun snapshot(): Map<String, Long> = HashMap(perApp)

    companion object {
        const val RECENT_MAX = 50
    }
}
