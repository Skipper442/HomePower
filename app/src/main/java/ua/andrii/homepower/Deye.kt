package ua.andrii.homepower

import android.content.Context
import kotlin.math.max

data class Reading(
    val soc: Int, val battW: Int, val gridW: Int, val genW: Int, val loadW: Int, val pvW: Int,
    val gridV: Double, val battV: Double, val capAh: Int,
) {
    val gridOn get() = gridV >= 150.0
    private val volts get() = if (battV > 40) battV else 51.2
    /** Повна ємність батареї, кВт·год. */
    val fullKWh get() = capAh * volts / 1000.0
    /** Скільки енергії зараз у батареї, кВт·год. */
    val leftKWh get() = fullKWh * soc / 100.0
    /** Години до 100% при поточній потужності заряду; null — якщо не заряджається. */
    fun hoursToFull(): Double? {
        if (battW > -30 || capAh <= 0 || soc >= 100) return null
        return fullKWh * (100 - soc) / 100.0 * 1000.0 / -battW
    }
    /** Години роботи при поточному споживанні до мінімального SOC; null — якщо не рахується. */
    fun hoursLeft(minSoc: Int): Double? {
        if (loadW < 20 || capAh <= 0) return null
        return max(0.0, fullKWh * (soc - minSoc) / 100.0) * 1000.0 / loadW
    }
}

/** Регістри однофазних гібридів Deye SG03LP1 / SG05LP1 (напр. SUN-6K-SG05LP1). */
object Deye {
    private const val START = 150
    private const val COUNT = 41 // 150..190
    private const val REG_CAPACITY = 102 // ємність батареї, Ah

    fun read(ctx: Context): Reading {
        val p = Prefs(ctx)
        val r = regs(p, START, COUNT)
        fun reg(n: Int) = r[n - START]
        fun s16(n: Int) = reg(n).let { if (it >= 0x8000) it - 0x10000 else it }
        val cap = if (p.capOverride > 0) p.capOverride else runCatching { regs(p, REG_CAPACITY, 1)[0] }.getOrDefault(0)
        return Reading(
            soc = reg(184),
            battW = s16(190),             // + розряд, − заряд
            gridW = s16(169),             // + імпорт з мережі
            genW = s16(166),
            loadW = s16(178),
            pvW = reg(186) + reg(187),
            gridV = reg(150) / 10.0,
            battV = reg(183) / 100.0,
            capAh = cap,
        )
    }

    private fun regs(p: Prefs, start: Int, count: Int) =
        if (p.serial > 0) Solarman.readHolding(p.host, p.port, p.serial, start, count)
        else Solarman.readModbusTcp(p.host, p.port, 1, start, count)
}

object Fmt {
    fun kwh(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
    /** Прогноз у годинах: «≈ 56 год», «≈ 7.5 год». */
    fun hours(h: Double): String = when {
        h >= 10 -> "≈ ${h.toInt()} год"
        else -> "≈ " + String.format(java.util.Locale.US, "%.1f", h) + " год"
    }
    fun duration(ms: Long): String {
        val m = ms / 60000
        return if (m >= 60) "${m / 60} год ${m % 60} хв" else "$m хв"
    }
    /** «6.2 / 10.2 kWh · ≈ 56 год» */
    fun battery(r: Reading, minSoc: Int): String {
        if (r.capAh <= 0) return "Вкажи ємність АКБ у застосунку"
        val base = "${kwh(r.leftKWh)} / ${kwh(r.fullKWh)} kWh"
        val h = r.hoursLeft(minSoc) ?: return base
        return "$base · ${hours(h)}"
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    var host: String
        get() = sp.getString("host", "") ?: ""
        set(v) = sp.edit().putString("host", v).apply()
    var port: Int
        get() = sp.getInt("port", 502)
        set(v) = sp.edit().putInt("port", v).apply()
    var serial: Long
        get() = sp.getLong("serial", 0L)
        set(v) = sp.edit().putLong("serial", v).apply()
    var minSoc: Int
        get() = sp.getInt("minSoc", 20)
        set(v) = sp.edit().putInt("minSoc", v).apply()
    var capOverride: Int
        get() = sp.getInt("capOverride", 0)
        set(v) = sp.edit().putInt("capOverride", v).apply()
    var monitor: Boolean
        get() = sp.getBoolean("monitor", false)
        set(v) = sp.edit().putBoolean("monitor", v).apply()
    /** Останній підтверджений стан мережі: 1 є, 0 нема, -1 невідомо. */
    var gridState: Int
        get() = sp.getInt("gridState", -1)
        set(v) = sp.edit().putInt("gridState", v).apply()
    var gridPending: Int
        get() = sp.getInt("gridPending", -1)
        set(v) = sp.edit().putInt("gridPending", v).apply()
    var gridPendingCount: Int
        get() = sp.getInt("gridPendingCount", 0)
        set(v) = sp.edit().putInt("gridPendingCount", v).apply()
    /** Коли мережа перейшла в поточний стан; 0 — невідомо. */
    var gridSince: Long
        get() = sp.getLong("gridSince", 0L)
        set(v) = sp.edit().putLong("gridSince", v).apply()
    val configured get() = host.isNotBlank()
}

/** Відстежує зміну стану мережі (з підтвердженням двома опитуваннями поспіль). */
object GridTracker {
    private const val CONFIRM = 2

    data class Change(val gridOn: Boolean, val previousSince: Long)

    /** Повертає зміну, якщо вона щойно підтвердилась (і попередній стан був відомий). */
    @Synchronized
    fun observe(ctx: Context, gridOn: Boolean): Change? {
        val p = Prefs(ctx)
        val state = if (gridOn) 1 else 0
        if (state == p.gridState) { p.gridPending = -1; p.gridPendingCount = 0; return null }
        val count = if (p.gridPending == state) p.gridPendingCount + 1 else 1
        p.gridPending = state; p.gridPendingCount = count
        if (count < CONFIRM) return null
        val previous = p.gridState
        val prevSince = p.gridSince
        p.gridState = state
        // якщо попередній стан невідомий — момент зміни теж невідомий
        p.gridSince = if (previous == -1) 0L else System.currentTimeMillis()
        p.gridPending = -1; p.gridPendingCount = 0
        return if (previous == -1) null else Change(gridOn, prevSince)
    }
}
