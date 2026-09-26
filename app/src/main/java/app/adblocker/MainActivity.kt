package app.adblocker

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.URL

class MainActivity : Activity() {

    private class Card(val body: TextView, val badge: TextView)

    private lateinit var halos: List<ImageView>
    private lateinit var powerBg: ImageView
    private lateinit var power: View
    private lateinit var state: TextView
    private lateinit var total: TextView
    private lateinit var appsCard: Card
    private lateinit var recentCard: Card
    private lateinit var listCard: Card
    private lateinit var alwaysOnCard: Card
    private lateinit var coverageCard: Card
    private var updatingList = false

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    /** Halos gently "breathe" while blocking is on. */
    private val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener {
            val f = it.animatedValue as Float
            halos.forEachIndexed { i, halo ->
                val scale = 1f + 0.035f * f * (i + 1)
                halo.scaleX = scale
                halo.scaleY = scale
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<TextView>(R.id.app_name).text = applicationInfo.loadLabel(packageManager)
        halos = listOf(R.id.halo1, R.id.halo2, R.id.halo3).map { findViewById(it) }
        powerBg = findViewById(R.id.power_bg)
        state = findViewById(R.id.state)
        total = findViewById(R.id.total)
        power = findViewById(R.id.power)
        power.setOnClickListener {
            if (AdBlockVpnService.running) stopVpn() else startVpn()
            handler.postDelayed(::render, 300)
        }

        appsCard = addCard(R.drawable.ic_chart, R.color.card_blue, "Uygulamalara göre") {
            startActivity(Intent(this, StatsActivity::class.java))
        }
        recentCard = addCard(R.drawable.ic_history, R.color.card_orange, "Son engellenenler") {
            startActivity(Intent(this, StatsActivity::class.java).putExtra(StatsActivity.EXTRA_RECENT, true))
        }
        listCard = addCard(R.drawable.ic_list, R.color.card_purple, "Engel listesi") { updateList() }
        alwaysOnCard = addCard(R.drawable.ic_autostart, R.color.on, "Her zaman açık") {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        }
        coverageCard = addCard(R.drawable.ic_shield, R.color.card_teal, "Koruma kapsamı") { showCoverage() }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
        pulse.pause()
    }

    private fun addCard(icon: Int, colorRes: Int, title: String, onClick: () -> Unit): Card {
        val cards = findViewById<LinearLayout>(R.id.cards)
        val view = layoutInflater.inflate(R.layout.item_card, cards, false)
        val color = getColor(colorRes)
        view.findViewById<ImageView>(R.id.icon).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(color)
            backgroundTintList = ColorStateList.valueOf((color and 0x00FFFFFF) or 0x26000000) // 15% circle
        }
        view.findViewById<TextView>(R.id.title).text = title
        view.setOnClickListener { onClick() }
        cards.addView(view)
        return Card(view.findViewById(R.id.body), view.findViewById(R.id.badge))
    }

    private fun setBadge(card: Card, on: Boolean) {
        card.badge.visibility = View.VISIBLE
        card.badge.text = if (on) "AÇIK" else "KAPALI"
        card.badge.setTextColor(getColor(if (on) R.color.on else R.color.text_secondary))
        card.badge.backgroundTintList = ColorStateList.valueOf(getColor(if (on) R.color.badge_on_bg else R.color.badge_off_bg))
    }

    private fun render() {
        val on = AdBlockVpnService.running
        val color = getColor(if (on) R.color.on else R.color.off)
        (halos + powerBg).forEach { it.imageTintList = ColorStateList.valueOf(color) }
        state.text = if (on) "AÇIK" else "KAPALI"
        state.setTextColor(color)
        power.contentDescription = if (on) "Reklam engellemeyi kapat" else "Reklam engellemeyi aç"
        if (on && !pulse.isStarted) pulse.start() else if (on && pulse.isPaused) pulse.resume()
        if (!on && pulse.isStarted) {
            pulse.cancel()
            halos.forEach { it.scaleX = 1f; it.scaleY = 1f }
        }

        val stats = AdBlockVpnService.stats(this)
        total.text = "Toplam engellenen: ${formatCount(stats.total())}"
        val top = stats.byApp().firstOrNull()
        appsCard.body.text = if (top == null) "Henüz engelleme yok"
        else "En çok: ${appLabel(this, top.first)} · ${formatCount(top.second)}"
        recentCard.body.text = stats.recent().firstOrNull()?.domain ?: "Henüz engelleme yok"
        if (!updatingList) {
            val size = AdBlockVpnService.listSize(this)
            listCard.body.text = if (size > 0) "${formatCount(size.toLong())} alan adı · güncellemek için dokun"
            else "StevenBlack + HaGeZi Pro · güncellemek için dokun"
        }
        alwaysOnCard.body.text = "Telefon açılınca kendiliğinden başlar"
        setBadge(alwaysOnCard, on && AdBlockVpnService.alwaysOn)
        coverageCard.body.text = "Uygulamalar, oyunlar ve tarayıcılar"
        setBadge(coverageCard, on)
    }

    private fun showCoverage() {
        AlertDialog.Builder(this)
            .setTitle("Koruma kapsamı")
            .setMessage(
                "Engellenenler: oyun ve uygulama reklamları (AdMob, AppLovin, Unity, Pangle ve diğerleri), " +
                    "sitelerdeki reklamlar ve takip kodları.\n\n" +
                    "Engellenemeyenler: YouTube, Instagram, Facebook ve TikTok reklamları. Bu uygulamalar " +
                    "reklamı içerikle aynı sunucudan gönderir; o sunucu engellenirse uygulamanın kendisi de çalışmaz.\n\n" +
                    "YouTube için Firefox + uBlock Origin kullanabilirsin.",
            )
            .setPositiveButton("Tamam", null)
            .show()
    }

    private fun startVpn() {
        val consent = VpnService.prepare(this) // non-null: Android must ask the user first
        if (consent != null) startActivityForResult(consent, REQUEST_VPN) else launchVpn()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) launchVpn()
    }

    private fun launchVpn() {
        startService(Intent(this, AdBlockVpnService::class.java))
    }

    private fun stopVpn() {
        startService(Intent(this, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_STOP))
    }

    private fun updateList() {
        if (updatingList) return
        updatingList = true
        listCard.body.text = "Listeler indiriliyor…"
        Thread {
            val result = try {
                for ((name, url) in AdBlockVpnService.LISTS) {
                    val tmp = File(filesDir, "$name.tmp")
                    URL(url).openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    val size = tmp.reader().use { Blocklist.parse(it) }.size
                    // A truncated download or an error page must not replace a working list.
                    check(size > 10_000) { "$name bozuk görünüyor ($size alan adı)" }
                    check(tmp.renameTo(File(filesDir, name))) { "$name kaydedilemedi" }
                }
                val list = AdBlockVpnService.loadBlocklist(this)
                AdBlockVpnService.blocklist = list
                "Listeler güncellendi: ${formatCount(list.size.toLong())} alan adı"
            } catch (e: Exception) {
                "Güncelleme başarısız: ${e.message}"
            }
            runOnUiThread {
                updatingList = false
                Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                render()
            }
        }.start()
    }

    companion object {
        private const val REQUEST_VPN = 1
    }
}
