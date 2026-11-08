package ua.andrii.homepower

import android.content.Context

data class Reading(val soc: Int, val battW: Int, val gridW: Int, val genW: Int, val loadW: Int, val pvW: Int)

/** Регістри однофазних гібридів Deye SG03LP1 / SG05LP1 (напр. SUN-6K-SG05LP1). */
object Deye {
    private const val START = 166
    private const val COUNT = 25 // 166..190

    fun read(ctx: Context): Reading {
        val p = Prefs(ctx)
        val r = if (p.serial > 0) Solarman.readHolding(p.host, p.port, p.serial, START, COUNT)
                else Solarman.readModbusTcp(p.host, p.port, 1, START, COUNT)
        fun reg(n: Int) = r[n - START]
        fun s16(n: Int) = reg(n).let { if (it >= 0x8000) it - 0x10000 else it }
        return Reading(
            soc = reg(184),
            battW = s16(190),             // + розряд, − заряд
            gridW = s16(169),             // + імпорт з мережі
            genW = s16(166),
            loadW = s16(178),
            pvW = reg(186) + reg(187),
        )
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
    val configured get() = host.isNotBlank()
}
