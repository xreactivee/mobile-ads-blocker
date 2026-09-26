package app.adblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Local VPN that only captures DNS. The phone's DNS server is set to a fake address
 * (10.0.0.1) and only that address is routed into the tunnel, so all other traffic
 * (video, games, web pages) goes around it untouched. Queries for listed ad domains
 * get NXDOMAIN; the rest are forwarded to a real DNS server.
 */
class AdBlockVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var pool: ExecutorService? = null
    private val uidToApp = ConcurrentHashMap<Int, String>()
    private var lastStatsSave = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop("off button")
            return START_NOT_STICKY
        }
        // Android restarts us through here when "always-on VPN" is switched on or off.
        alwaysOn = isAlwaysOn
        // Any other start (our button, or Android's "always-on VPN") turns blocking on.
        if (tun == null) start()
        return START_STICKY
    }

    private fun start() {
        val iface = Builder()
            .setSession(applicationInfo.loadLabel(packageManager).toString())
            .addAddress(TUN_ADDRESS, 32)
            .addDnsServer(DNS_ADDRESS)
            .addRoute(DNS_ADDRESS, 32)
            .setBlocking(true)
            .establish()
        if (iface == null) { // VPN permission was revoked
            stopSelf()
            return
        }
        tun = iface
        pool = Executors.newCachedThreadPool()
        running = true
        startForegroundNotification()

        val pool = pool!!
        val stats = stats(this)
        Thread {
            if (blocklist.size == 0) blocklist = loadBlocklist(this)
            Log.i(TAG, "started, ${blocklist.size} domains")
            val input = FileInputStream(iface.fileDescriptor)
            val output = FileOutputStream(iface.fileDescriptor)
            val buf = ByteArray(32767)
            try {
                while (true) {
                    val len = input.read(buf)
                    if (len <= 0) continue
                    val q = DnsPacket.parse(buf, len) ?: continue
                    if (blocklist.isBlocked(q.domain)) {
                        // Look up the app before replying: its query socket closes once answered.
                        val app = appFor(q)
                        write(output, DnsPacket.blockedReply(q))
                        stats.record(app, q.domain, System.currentTimeMillis())
                        Log.i(TAG, "blocked ${q.domain} for $app")
                        saveStats(force = false)
                    } else {
                        pool.execute { forward(q, output) }
                    }
                }
            } catch (e: IOException) {
                Log.i(TAG, "tunnel closed: $e") // normal on stop
            }
        }.start()
    }

    /** Package name of the app that sent [q], or [SYSTEM_APP] / [UNKNOWN_APP]. */
    private fun appFor(q: DnsPacket.Query): String {
        val uid = try {
            // Only the active VPN app may ask this; DNS sockets are owned by the requesting app.
            getSystemService(ConnectivityManager::class.java).getConnectionOwnerUid(
                OsConstants.IPPROTO_UDP,
                InetSocketAddress(InetAddress.getByAddress(q.srcIp), q.srcPort),
                InetSocketAddress(InetAddress.getByAddress(q.dstIp), 53),
            )
        } catch (e: RuntimeException) {
            Process.INVALID_UID
        }
        return when (uid) {
            Process.INVALID_UID -> UNKNOWN_APP
            Process.SYSTEM_UID -> SYSTEM_APP
            else -> uidToApp.getOrPut(uid) { packageManager.getPackagesForUid(uid)?.firstOrNull() ?: UNKNOWN_APP }
        }
    }

    /** Sends the query to the real DNS server and writes its answer back into the tunnel. */
    private fun forward(q: DnsPacket.Query, output: FileOutputStream) {
        try {
            DatagramSocket().use { socket ->
                protect(socket) // keep this socket out of our own VPN
                socket.soTimeout = 5000
                socket.send(DatagramPacket(q.dns, q.dns.size, InetAddress.getByName(UPSTREAM_DNS), 53))
                val reply = DatagramPacket(ByteArray(4096), 4096)
                socket.receive(reply)
                write(output, DnsPacket.wrapReply(q, reply.data.copyOf(reply.length)))
            }
        } catch (e: IOException) {
            Log.d(TAG, "upstream failed for ${q.domain}: $e") // the app will retry the lookup
        }
    }

    private fun write(output: FileOutputStream, packet: ByteArray) = synchronized(output) { output.write(packet) }

    /** Persists the per-app counters, at most every 10 s unless [force]d. */
    private fun saveStats(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastStatsSave < 10_000) return
        lastStatsSave = now
        val editor = getSharedPreferences(STATS_PREFS, MODE_PRIVATE).edit()
        for ((app, count) in stats(this).snapshot()) editor.putLong(app, count)
        editor.apply()
    }

    /** Keeps Android from killing the service in the background. */
    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Reklam engelleme", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Reklam engelleme açık")
            .setContentIntent(open)
            .build()
        try {
            startForeground(1, notification)
        } catch (e: RuntimeException) {
            // Can be refused when started in the background (e.g. always-on VPN at boot).
            // The system still keeps an active VPN service alive, so carry on without it.
            Log.w(TAG, "startForeground refused: $e")
        }
    }

    private fun stop(reason: String) {
        if (tun != null) {
            Log.i(TAG, "stopping: $reason")
            saveStats(force = true)
        }
        running = false
        tun?.close() // unblocks the reader thread
        tun = null
        pool?.shutdownNow()
        pool = null
        stopSelf()
    }

    override fun onRevoke() = stop("revoked") // another VPN took over, or the user turned us off in Settings

    override fun onDestroy() {
        stop("destroyed")
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "app.adblocker.STOP"
        const val SYSTEM_APP = "android"
        const val UNKNOWN_APP = "?"
        private const val TAG = "AdBlock"
        private const val CHANNEL = "vpn"
        private const val TUN_ADDRESS = "10.0.0.2"
        private const val DNS_ADDRESS = "10.0.0.1"
        private const val UPSTREAM_DNS = "1.1.1.1" // Cloudflare; change to e.g. "9.9.9.9" (Quad9) if preferred
        private const val STATS_PREFS = "stats"     // package name -> blocked count since install
        private const val SETTINGS_PREFS = "settings"

        @Volatile var running = false
        @Volatile var alwaysOn = false
        @Volatile var blocklist = Blocklist(emptySet())
        @Volatile private var stats: BlockStats? = null

        /** Counters since install, loaded from disk on first use. */
        fun stats(context: Context): BlockStats = stats ?: synchronized(this) {
            stats ?: BlockStats(
                context.getSharedPreferences(STATS_PREFS, MODE_PRIVATE).all.mapValues { it.value as Long },
            ).also { stats = it }
        }

        /** Domain count of the last loaded blocklist (0 if never loaded). */
        fun listSize(context: Context) = context.getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).getInt("list_size", 0)

        /**
         * Lists merged into one blocklist: file name (bundled in assets) to its download URL.
         * StevenBlack is the broad classic list; HaGeZi Pro adds regional ad networks (e.g. Turkish ones).
         */
        val LISTS = mapOf(
            "hosts.txt" to "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            "hagezi-pro.txt" to "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro-onlydomains.txt",
        )

        /** Each list from its downloaded copy if there is one, otherwise from the copy bundled in the APK. */
        fun loadBlocklist(context: Context): Blocklist {
            val readers = LISTS.keys.map { name ->
                val updated = File(context.filesDir, name)
                if (updated.exists()) updated.reader() else context.assets.open(name).reader()
            }
            val list = try {
                Blocklist.parse(*readers.toTypedArray())
            } finally {
                readers.forEach { it.close() }
            }
            context.getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).edit().putInt("list_size", list.size).apply()
            return list
        }
    }
}
