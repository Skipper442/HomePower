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

        private fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, PowerWidget::class.java))

        fun refresh(ctx: Context, pending: PendingResult?) {
            val app = ctx.applicationContext
            if (ids(app).isEmpty()) { pending?.finish(); return }
            AppWidgetManager.getInstance(app).partiallyUpdateAppWidget(ids(app),
                RemoteViews(app.packageName, R.layout.widget_power).apply { setTextViewText(R.id.updated, "⟳ оновлюю…") })
            Thread {
                try {
                    if (!Prefs(app).configured) throw IllegalStateException("налаштуй застосунок")
                    show(app, Deye.read(app))
                } catch (e: Exception) {
                    showError(app, e.message ?: "немає зв'язку")
                } finally {
                    pending?.finish()
                }
            }.start()
        }

        /** Малює свіжі дані на всіх віджетах (викликається і з фонового моніторингу). */
        fun show(ctx: Context, r: Reading) {
            val app = ctx.applicationContext
            val ids = ids(app); if (ids.isEmpty()) return
            val v = base(app)
            v.setTextViewText(R.id.updated, "⟳ ${now()}")
            v.setProgressBar(R.id.battBar, 100, r.soc.coerceIn(0, 100), false)
            v.setTextViewText(R.id.soc, "${r.soc}%")
            v.setTextViewText(R.id.load, r.loadW.toString())
            v.setTextViewText(R.id.pv, if (r.pvW > 0) "☀ ${kw(r.pvW)}" else "")
            v.setTextViewText(R.id.runtime, Fmt.battery(r, Prefs(app).minSoc))
            when {
                r.battW > ACTIVE -> { v.setTextViewText(R.id.battStatus, "Розряд ${r.battW} W"); v.setTextColor(R.id.battStatus, Color.parseColor("#5AA9FF")) }
                r.battW < -ACTIVE -> { v.setTextViewText(R.id.battStatus, "Заряд ${-r.battW} W"); v.setTextColor(R.id.battStatus, Color.parseColor("#34D399")) }
                else -> { v.setTextViewText(R.id.battStatus, "Батарея в спокої"); v.setTextColor(R.id.battStatus, Color.parseColor("#9CA3AF")) }
            }
            if (r.gridOn) {
                v.setTextViewText(R.id.chipGrid, "Grid")
                chip(v, R.id.chipGrid, abs(r.gridW) > ACTIVE)
            } else {
                v.setTextViewText(R.id.chipGrid, "Нема мережі")
                v.setInt(R.id.chipGrid, "setBackgroundResource", R.drawable.chip_alert)
                v.setTextColor(R.id.chipGrid, Color.parseColor("#3B0A0A"))
            }
            chip(v, R.id.chipGen, abs(r.genW) > ACTIVE)
            chip(v, R.id.chipBatt, r.battW > ACTIVE)
            AppWidgetManager.getInstance(app).updateAppWidget(ids, v)
        }

        fun showError(ctx: Context, msg: String) {
            val app = ctx.applicationContext
            val ids = ids(app); if (ids.isEmpty()) return
            val v = base(app)
            v.setTextViewText(R.id.updated, "⚠ ${now()} · $msg")
            AppWidgetManager.getInstance(app).partiallyUpdateAppWidget(ids, v)
        }

        private fun base(app: Context) = RemoteViews(app.packageName, R.layout.widget_power).apply {
            setOnClickPendingIntent(R.id.root, PendingIntent.getBroadcast(
                app, 0, Intent(app, PowerWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }

        private fun now() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        private fun chip(v: RemoteViews, id: Int, on: Boolean) {
            v.setInt(id, "setBackgroundResource", if (on) R.drawable.chip_on else R.drawable.chip_off)
            v.setTextColor(id, Color.parseColor(if (on) "#06281C" else "#6B7280"))
        }

        private fun kw(w: Int) = if (w >= 1000) String.format(Locale.US, "%.1f kW", w / 1000.0) else "$w W"
    }
}
