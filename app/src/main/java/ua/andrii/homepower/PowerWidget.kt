package ua.andrii.homepower

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class PowerWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) refresh(context, goAsync()) else super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        refresh(context, goAsync())
    }

    companion object {
        const val ACTION_REFRESH = "ua.andrii.homepower.REFRESH"
        private const val ACTIVE = 30 // W — поріг, щоб вважати джерело активним

        fun refresh(ctx: Context, pending: PendingResult?) {
            val app = ctx.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, PowerWidget::class.java))
            if (ids.isEmpty()) { pending?.finish(); return }

            mgr.partiallyUpdateAppWidget(ids, RemoteViews(app.packageName, R.layout.widget_power).apply {
                setTextViewText(R.id.updated, "⟳ оновлюю…")
            })

            Thread {
                val views = RemoteViews(app.packageName, R.layout.widget_power)
                views.setOnClickPendingIntent(R.id.root, PendingIntent.getBroadcast(
                    app, 0, Intent(app, PowerWidget::class.java).setAction(ACTION_REFRESH),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                try {
                    if (!Prefs(app).configured) throw IllegalStateException("налаштуй застосунок")
                    render(views, Deye.read(app), time)
                    mgr.updateAppWidget(ids, views)
                } catch (e: Exception) {
                    // залишаємо останні цифри, міняємо тільки статус
                    views.setTextViewText(R.id.updated, "⚠ $time · ${e.message ?: "немає зв'язку"}")
                    mgr.partiallyUpdateAppWidget(ids, views)
                } finally {
                    pending?.finish()
                }
            }.start()
        }

        private fun render(v: RemoteViews, r: Reading, time: String) {
            v.setTextViewText(R.id.updated, "⟳ $time")
            v.setProgressBar(R.id.battBar, 100, r.soc.coerceIn(0, 100), false)
            v.setTextViewText(R.id.soc, "${r.soc}%")
            v.setTextViewText(R.id.load, r.loadW.toString())
            v.setTextViewText(R.id.pv, if (r.pvW > 0) "☀ ${kw(r.pvW)}" else "")
            when {
                r.battW > ACTIVE -> { v.setTextViewText(R.id.battStatus, "Розряд ${r.battW} W"); v.setTextColor(R.id.battStatus, Color.parseColor("#5AA9FF")) }
                r.battW < -ACTIVE -> { v.setTextViewText(R.id.battStatus, "Заряд ${-r.battW} W"); v.setTextColor(R.id.battStatus, Color.parseColor("#34D399")) }
                else -> { v.setTextViewText(R.id.battStatus, "Батарея в спокої"); v.setTextColor(R.id.battStatus, Color.parseColor("#9CA3AF")) }
            }
            chip(v, R.id.chipGrid, abs(r.gridW) > ACTIVE)
            chip(v, R.id.chipGen, abs(r.genW) > ACTIVE)
            chip(v, R.id.chipBatt, r.battW > ACTIVE)
        }

        private fun chip(v: RemoteViews, id: Int, on: Boolean) {
            v.setInt(id, "setBackgroundResource", if (on) R.drawable.chip_on else R.drawable.chip_off)
            v.setTextColor(id, Color.parseColor(if (on) "#06281C" else "#6B7280"))
        }

        private fun kw(w: Int) = if (w >= 1000) String.format(Locale.US, "%.1f kW", w / 1000.0) else "$w W"
    }
}
