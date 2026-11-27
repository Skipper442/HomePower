package ua.andrii.homepower

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class PowerWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) refresh(context, goAsync()) else super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        refresh(context, goAsync())
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, options: android.os.Bundle) {
        refresh(context, goAsync()) // змінили розмір — перемалювати з новим масштабом
    }

    /** Колірна тема стану: тло, смужка, акцентний і світлий текст. */
    private enum class Theme(val bg: Int, val bar: Int, val accent: String, val light: String) {
        RED(R.drawable.bg_red, R.id.barRed, "#FCA5A5", "#FECACA"),
        GREEN(R.drawable.bg_green, R.id.barGreen, "#86EFAC", "#BBF7D0"),
        BLUE(R.drawable.bg_blue, R.id.barBlue, "#93C5FD", "#BFDBFE"),
        GREY(R.drawable.bg_grey, R.id.barGrey, "#9CA3AF", "#D1D5DB"),
    }

    companion object {
        const val ACTION_REFRESH = "ua.andrii.homepower.REFRESH"
        private const val ACTIVE = 30 // W — поріг, щоб вважати потік активним
        private const val LOW_SOC = 30 // нижче — смужка й відсоток жовтіють
        private val BARS = intArrayOf(R.id.barRed, R.id.barGreen, R.id.barBlue, R.id.barGrey, R.id.barAmber)

        private fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, PowerWidget::class.java))

        fun refresh(ctx: Context, pending: PendingResult?) {
            val app = ctx.applicationContext
            if (ids(app).isEmpty()) { pending?.finish(); return }
            AppWidgetManager.getInstance(app).partiallyUpdateAppWidget(ids(app),
                RemoteViews(app.packageName, R.layout.widget_power).apply { setTextViewText(R.id.meta, "оновлюю…") })
            Thread {
                try {
                    if (!Prefs(app).configured) throw IllegalStateException("налаштуй застосунок")
                    val r = Deye.read(app)
                    if (!r.stale) GridTracker.observe(app, r.gridOn)
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
            val mgr = AppWidgetManager.getInstance(app)
            for (id in ids(app)) mgr.updateAppWidget(id, build(app, r, scaleFor(mgr, id)))
        }

        /** Масштаб тексту під реальну висоту віджета (база — 170 dp). */
        private fun scaleFor(mgr: AppWidgetManager, id: Int): Float {
            val o = mgr.getAppWidgetOptions(id)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            return if (h <= 0) 1f else (h / 170f).coerceIn(1f, 1.35f)
        }

        private fun build(app: Context, r: Reading, scale: Float): RemoteViews {
            val p = Prefs(app)
            val v = base(app)

            val discharging = r.battW > ACTIVE
            val toFull = r.hoursToFull()
            val src = when {
                r.gridW > ACTIVE && r.gridW >= r.pvW -> "від мережі"
                r.pvW > ACTIVE -> "від сонця"
                else -> "від мережі"
            }
            val outageSince = if (!r.gridOn && p.gridState == 0 && p.gridSince > 0) p.gridSince else 0L
            val stale = r.fromCloud && r.stale
            val theme = when {
                stale -> Theme.GREY
                !r.gridOn -> Theme.RED
                discharging -> Theme.BLUE
                else -> Theme.GREEN
            }

            // Заголовок і правий верхній кут
            val temp = r.invTemp?.let { "🌡 ${it.roundToInt()}°" }
            val clock = (if (r.fromCloud) "☁ " else "") + hms(r.time)
            val (title, meta) = when {
                stale -> "⚠ Дані ${Fmt.duration(r.ageSec * 1000)} тому" to "тапни для оновлення"
                !r.gridOn -> (if (outageSince > 0) "⚡ Нема ${Fmt.duration(System.currentTimeMillis() - outageSince)}" else "⚡ Нема світла") to
                    listOfNotNull(if (outageSince > 0) "з ${hm(outageSince)}" else clock, temp).joinToString(" · ")
                discharging -> "🔋 Живлення від батареї" to listOfNotNull(temp, clock).joinToString(" · ")
                else -> "✓ Світло є" to listOfNotNull(temp, clock).joinToString(" · ")
            }
            v.setInt(R.id.root, "setBackgroundResource", theme.bg)
            v.setTextViewText(R.id.title, title)
            v.setTextColor(R.id.title, Color.parseColor(theme.light))
            v.setTextViewText(R.id.meta, meta)
            v.setTextColor(R.id.meta, Color.parseColor(theme.accent))

            // Велике число + деталі
            var bigUnitLong = false
            val details: String
            when {
                !r.gridOn || discharging -> {
                    setHours(v, r.hoursLeft(p.minSoc), "год")
                    details = if (r.capAh <= 0) "Вкажи ємність АКБ у застосунку" else buildString {
                        append("${r.loadW} W")
                        if (discharging) append(" · розряд ${kw(r.battW)}")
                        if (r.gridOn) append(" · мережа є")
                    }
                }
                toFull != null -> {
                    setHours(v, toFull, "год до 100%"); bigUnitLong = true
                    details = "${r.loadW} W $src · заряд ${kw(-r.battW)}"
                }
                else -> {
                    bigUnitLong = true
                    if (r.loadW >= 1000) {
                        v.setTextViewText(R.id.big, String.format(Locale.US, "%.1f", r.loadW / 1000.0))
                        v.setTextViewText(R.id.unit, "kW $src")
                    } else {
                        v.setTextViewText(R.id.big, r.loadW.toString())
                        v.setTextViewText(R.id.unit, "W $src")
                    }
                    details = if (r.battW < -ACTIVE) "Батарея: заряд ${kw(-r.battW)}" else "Батарея в резерві"
                }
            }
            v.setTextViewText(R.id.details, if (stale) "Останнє: $details" else details)
            v.setTextColor(R.id.details, Color.parseColor(theme.accent))
            v.setTextColor(R.id.unit, Color.parseColor(theme.accent))
            v.setTextColor(R.id.big, Color.parseColor(if (stale) "#D1D5DB" else "#FFFFFF"))

            // Заряд праворуч і смужка
            val soc = r.soc.coerceIn(0, 100)
            val low = soc < LOW_SOC && !stale
            v.setTextViewText(R.id.soc, "$soc%")
            v.setTextColor(R.id.soc, Color.parseColor(when { low -> "#FBBF24"; stale -> "#D1D5DB"; else -> "#FFFFFF" }))
            v.setTextViewText(R.id.kwh, if (r.capAh > 0) "${Fmt.kwh(r.leftKWh)} kWh" else "")
            v.setTextColor(R.id.kwh, Color.parseColor(theme.accent))
            val bar = if (low) R.id.barAmber else theme.bar
            for (id in BARS) {
                v.setViewVisibility(id, if (id == bar) View.VISIBLE else View.GONE)
                v.setProgressBar(id, 100, soc, false)
            }

            // Сонячна генерація (вмикається в застосунку)
            if (p.showSolar) {
                val today = r.pvTodayKWh?.let { "сьогодні ${Fmt.kwh(it)} kWh" }
                val now = if (r.pvW > ACTIVE) kw(r.pvW) else null
                v.setTextViewText(R.id.solar, "☀ " + (listOfNotNull(now, today).joinToString(" · ").ifEmpty { "0 W" }))
                v.setViewVisibility(R.id.solar, View.VISIBLE)
            } else {
                v.setViewVisibility(R.id.solar, View.GONE)
            }

            // Розміри тексту: велике число і відсоток ростуть сильніше, дрібний текст — трохи
            val small = minOf(scale, 1.15f)
            val bigScale = if (bigUnitLong) minOf(scale, 1.2f) else scale
            fun sp(id: Int, base: Float, k: Float) = v.setTextViewTextSize(id, android.util.TypedValue.COMPLEX_UNIT_SP, base * k)
            sp(R.id.big, 44f, bigScale); sp(R.id.soc, 26f, scale)
            sp(R.id.unit, 16f, small); sp(R.id.details, 13f, small); sp(R.id.kwh, 12f, small)
            sp(R.id.title, 14f, small); sp(R.id.meta, 12f, small); sp(R.id.solar, 13f, small)
            return v
        }

        fun showError(ctx: Context, msg: String) {
            val app = ctx.applicationContext
            val ids = ids(app); if (ids.isEmpty()) return
            val v = base(app)
            v.setTextViewText(R.id.meta, "⚠ ${hms(System.currentTimeMillis())} · $msg")
            AppWidgetManager.getInstance(app).partiallyUpdateAppWidget(ids, v)
        }

        private fun base(app: Context) = RemoteViews(app.packageName, R.layout.widget_power).apply {
            setOnClickPendingIntent(R.id.root, PendingIntent.getBroadcast(
                app, 0, Intent(app, PowerWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }

        private fun setHours(v: RemoteViews, h: Double?, unit: String) {
            v.setTextViewText(R.id.big, when {
                h == null -> "—"
                h >= 10 -> "≈ ${h.toInt()}"
                else -> "≈ " + String.format(Locale.US, "%.1f", h)
            })
            v.setTextViewText(R.id.unit, unit)
        }

        private fun hms(t: Long) = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(t))
        private fun hm(t: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

        private fun kw(w: Int) = if (w >= 1000) String.format(Locale.US, "%.1f kW", w / 1000.0) else "$w W"
    }
}
