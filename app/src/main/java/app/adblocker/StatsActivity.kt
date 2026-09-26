package app.adblocker

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Blocked counts per app since install, or (with [EXTRA_RECENT]) the latest blocked domains. */
class StatsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)
        findViewById<ImageView>(R.id.back).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val stats = AdBlockVpnService.stats(this)
        val title = findViewById<TextView>(R.id.title)
        val subtitle = findViewById<TextView>(R.id.subtitle)
        val rows = findViewById<LinearLayout>(R.id.rows)
        rows.removeAllViews()

        if (intent.getBooleanExtra(EXTRA_RECENT, false)) {
            title.text = "Son engellenenler"
            subtitle.text = "Son ${BlockStats.RECENT_MAX} engelleme, en yenisi üstte"
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            for (e in stats.recent()) {
                addRow(rows, e.app, e.domain, "${appLabel(this, e.app)} · ${time.format(Date(e.time))}", "")
            }
        } else {
            val total = stats.total()
            title.text = "Uygulamalara göre"
            subtitle.text = "Kurulumdan beri · toplam ${formatCount(total)}"
            for ((app, count) in stats.byApp()) {
                addRow(rows, app, appLabel(this, app), "%${count * 100 / total}", formatCount(count))
            }
        }
        if (rows.childCount == 0) {
            rows.addView(TextView(this).apply {
                text = "Henüz engellenen bir şey yok"
                setTextColor(getColor(R.color.text_secondary))
                setPadding(48, 36, 48, 36)
            })
        }
    }

    private fun addRow(rows: LinearLayout, app: String, title: String, subtitle: String, count: String) {
        val row = layoutInflater.inflate(R.layout.item_row, rows, false)
        row.findViewById<ImageView>(R.id.icon).setImageDrawable(appIcon(this, app))
        row.findViewById<TextView>(R.id.title).text = title
        row.findViewById<TextView>(R.id.subtitle).text = subtitle
        row.findViewById<TextView>(R.id.count).text = count
        rows.addView(row)
    }

    companion object {
        const val EXTRA_RECENT = "recent"
    }
}

private val countFormat = NumberFormat.getIntegerInstance(Locale.forLanguageTag("tr"))

/** 1234 -> "1.234" */
fun formatCount(n: Long): String = countFormat.format(n)

/** Display name for a package name recorded in [BlockStats]. */
fun appLabel(context: Context, app: String): String = when (app) {
    AdBlockVpnService.SYSTEM_APP -> "Android sistemi"
    AdBlockVpnService.UNKNOWN_APP -> "Bilinmeyen"
    else -> try {
        context.packageManager.getApplicationInfo(app, 0).loadLabel(context.packageManager).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        app // uninstalled since
    }
}

fun appIcon(context: Context, app: String): Drawable = try {
    context.packageManager.getApplicationIcon(app)
} catch (e: PackageManager.NameNotFoundException) {
    context.packageManager.defaultActivityIcon
}
