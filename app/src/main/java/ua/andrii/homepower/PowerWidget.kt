package ua.andrii.homepower

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PowerWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) refresh(context, goAsync()) else super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        refresh(context, goAsync())
    }

    companion object {
        const val ACTION_REFRESH = "ua.andrii.homepower.REFRESH"
        private const val ACTIVE = 30 // W — поріг, щоб вважати потік активним
        private val WHITE = Color.parseColor("#FFFFFF")
        private val BLUE = Color.parseColor("#5AA9FF")
        private val GREEN = Color.parseColor("#34D399")
        private val SUN = Color.parseColor("#FBBF24")

        private fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, PowerWidget::class.java))

        fun refresh(ctx: Context, pending: PendingResult?) {
            val app = ctx.applicationContext
            if (ids(app).isEmpty()) { pending?.finish(); return }
            AppWidgetManager.getInstance(app).partiallyUpdateAppWidget(ids(app),
                RemoteViews(app.packageName, R.layout.widget_power).apply { setTextViewText(R.id.updated, "оновлюю…") })
            Thread {
                try {
                    if (!Prefs(app).configured) throw IllegalStateException("налаштуй застосунок")
                    val r = Deye.read(app)
                    GridTracker.observe(app, r.gridOn)
                    show(app, r)
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
            val p = Prefs(app)
            val soc = r.soc.coerceIn(0, 100)
            val ring = when { soc > 50 -> R.id.ringGreen; soc >= 20 -> R.id.ringYellow; else -> R.id.ringRed }
            for (id in intArrayOf(R.id.ringGreen, R.id.ringYellow, R.id.ringRed)) {
                v.setViewVisibility(id, if (id == ring) android.view.View.VISIBLE else android.view.View.GONE)
                v.setProgressBar(id, 100, soc, false)
            }
            v.setTextViewText(R.id.soc, "${r.soc}%")
            v.setTextViewText(R.id.kwh, if (r.capAh > 0) "${Fmt.kwh(r.leftKWh)} kWh" else "")

            // тривалість відключення відома, якщо застосунок бачив момент зникнення мережі
            val outageSince = if (!r.gridOn && p.gridState == 0 && p.gridSince > 0) p.gridSince else 0L
            v.setTextViewText(R.id.updated,
                if (outageSince > 0) "з " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(outageSince)) else now())
            if (r.gridOn) {
                v.setTextViewText(R.id.gridPill, "Мережа є")
                v.setInt(R.id.gridPill, "setBackgroundResource", R.drawable.pill_green)
                v.setTextColor(R.id.gridPill, Color.parseColor("#06281C"))
            } else {
                v.setTextViewText(R.id.gridPill,
                    if (outageSince > 0) "Нема ${Fmt.duration(System.currentTimeMillis() - outageSince)}" else "Нема мережі")
                v.setInt(R.id.gridPill, "setBackgroundResource", R.drawable.pill_red)
                v.setTextColor(R.id.gridPill, Color.parseColor("#3B0A0A"))
            }

            val discharging = r.battW > ACTIVE
            val toFull = r.hoursToFull()
            val src = when {
                r.gridW > ACTIVE && r.gridW >= r.pvW -> "від мережі"
                r.pvW > ACTIVE -> "від сонця"
                else -> "від мережі"
            }
            val d = SpannableStringBuilder()
            when {
                // Живимось від батареї (нема мережі або розряд за розкладом) — скільки вистачить
                !r.gridOn || discharging -> {
                    setHours(v, r.hoursLeft(p.minSoc), "год")
                    if (r.capAh <= 0) d.append("Вкажи ємність АКБ у застосунку") else {
                        d.append("Споживання "); bold(d, "${r.loadW} W", WHITE)
                        if (discharging) { d.append(" · "); colored(d, "розряд ${kw(r.battW)}", BLUE) }
                    }
                }
                // Мережа є, батарея заряджається — скільки до 100%
                toFull != null -> {
                    setHours(v, toFull, "год до 100%")
                    d.append("Споживання "); bold(d, "${r.loadW} W", WHITE); d.append(" $src · ")
                    colored(d, "заряд ${kw(-r.battW)}", GREEN)
                }
                // Мережа є, батарея повна/в резерві — показуємо споживання і джерело
                else -> {
                    if (r.loadW >= 1000) {
                        v.setTextViewText(R.id.hours, String.format(Locale.US, "%.1f", r.loadW / 1000.0))
                        v.setTextViewText(R.id.hoursUnit, "kW $src")
                    } else {
                        v.setTextViewText(R.id.hours, r.loadW.toString())
                        v.setTextViewText(R.id.hoursUnit, "W $src")
                    }
                    d.append("Батарея ")
                    if (r.battW < -ACTIVE) colored(d, "заряд ${kw(-r.battW)}", GREEN) else colored(d, "в резерві", GREEN)
                }
            }
            if (r.pvW > ACTIVE && r.capAh > 0) { d.append(" · "); colored(d, "☀ ${kw(r.pvW)}", SUN) }
            v.setTextViewText(R.id.details, d)
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

        private fun setHours(v: RemoteViews, h: Double?, unit: String) {
            v.setTextViewText(R.id.hours, when {
                h == null -> "—"
                h >= 10 -> "≈ ${h.toInt()}"
                else -> "≈ " + String.format(Locale.US, "%.1f", h)
            })
            v.setTextViewText(R.id.hoursUnit, unit)
        }

        private fun colored(sb: SpannableStringBuilder, text: String, color: Int) {
            val s = sb.length; sb.append(text)
            sb.setSpan(ForegroundColorSpan(color), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        private fun bold(sb: SpannableStringBuilder, text: String, color: Int) {
            val s = sb.length; colored(sb, text, color)
            sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        private fun now() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        private fun kw(w: Int) = if (w >= 1000) String.format(Locale.US, "%.1f kW", w / 1000.0) else "$w W"
    }
}
