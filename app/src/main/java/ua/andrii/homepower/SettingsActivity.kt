package ua.andrii.homepower

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MonitorService.createChannels(this)
        val p = Prefs(this)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }

        fun field(hint: String, value: String, type: Int) = EditText(this).apply {
            this.hint = hint; setText(value); inputType = type; root.addView(this)
        }
        fun label(t: String) = root.addView(TextView(this).apply { text = t; textSize = 13f; setTextColor(Color.GRAY); setPadding(0, pad / 2, 0, 0) })

        root.addView(TextView(this).apply { text = "Home Power — інвертор Deye"; textSize = 20f })
        label("Підключення")
        val host = field("IP логера, напр. 192.168.31.85", p.host, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val port = field("Порт (502 — Modbus TCP, 8899 — Solarman)", p.port.toString(), InputType.TYPE_CLASS_NUMBER)
        val serial = field("Серійник логера Solarman (порожньо = Modbus TCP)", if (p.serial > 0) p.serial.toString() else "", InputType.TYPE_CLASS_NUMBER)
        label("Батарея")
        val minSoc = field("Мінімальний заряд для прогнозу, %", p.minSoc.toString(), InputType.TYPE_CLASS_NUMBER)
        val cap = field("Ємність, Ah (порожньо = з інвертора)", if (p.capOverride > 0) p.capOverride.toString() else "", InputType.TYPE_CLASS_NUMBER)
        val monitor = CheckBox(this).apply {
            text = "Сповіщення, коли зникає / з'являється світло"
            isChecked = p.monitor
            root.addView(this)
        }
        root.addView(TextView(this).apply {
            textSize = 12f; setTextColor(Color.GRAY)
            text = "Працює у фоні, поки телефон у домашній Wi-Fi: опитує інвертор кожні 30 с і заодно оновлює віджет. Трохи більше витрачає заряд телефона."
        })
        root.addView(Button(this).apply {
            text = "Сховати значок моніторингу зі шторки"
            setOnClickListener {
                runCatching {
                    startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, MonitorService.CH_MONITOR))
                }
            }
        })
        root.addView(TextView(this).apply {
            textSize = 12f; setTextColor(Color.GRAY)
            text = "Вимкни категорію «Фоновий моніторинг». Моніторинг продовжить працювати, а сповіщення про світло приходитимуть як і раніше."
        })
        val result = TextView(this).apply { setPadding(0, pad, 0, 0); textSize = 15f }

        fun save(): Boolean {
            val raw = serial.text.toString().trim()
            val sn = if (raw.isEmpty()) 0L else raw.toLongOrNull()
            if (host.text.isBlank() || sn == null || sn !in 0..0xFFFFFFFFL) { result.text = "Перевір IP і серійник"; return false }
            p.host = host.text.toString().trim(); p.serial = sn
            p.port = port.text.toString().trim().toIntOrNull() ?: 502
            p.minSoc = (minSoc.text.toString().trim().toIntOrNull() ?: 20).coerceIn(0, 100)
            p.capOverride = cap.text.toString().trim().toIntOrNull() ?: 0
            p.monitor = monitor.isChecked
            if (p.monitor) enableMonitor() else MonitorService.stop(this)
            return true
        }

        root.addView(Button(this).apply {
            text = "Зберегти й перевірити"
            setOnClickListener {
                if (!save()) return@setOnClickListener
                result.setTextColor(Color.GRAY); result.text = "Підключаюсь…"
                Thread {
                    val msg = try {
                        val r = Deye.read(this@SettingsActivity)
                        PowerWidget.show(applicationContext, r)
                        buildString {
                            append("✅ Працює!\n")
                            append("Мережа: ${if (r.gridOn) "є" else "нема"} (${r.gridV} V)\n")
                            append("SOC ${r.soc}% · батарея ${r.battV} V · ${r.capAh} Ah\n")
                            if (r.capAh > 0) append("Енергія: ${Fmt.kwh(r.leftKWh)} з ${Fmt.kwh(r.fullKWh)} kWh\n")
                            r.hoursLeft(p.minSoc)?.let { append("Вистачить до ${p.minSoc}%: ${Fmt.hours(it)}\n") }
                            append("Споживання ${r.loadW} W · батарея ${r.battW} W (+ розряд)\n")
                            append("Мережа ${r.gridW} W · PV ${r.pvW} W · Gen ${r.genW} W")
                        }
                    } catch (e: Exception) { "❌ ${e.javaClass.simpleName}: ${e.message}" }
                    runOnUiThread { result.setTextColor(if (msg.startsWith("✅")) Color.parseColor("#10B981") else Color.RED); result.text = msg }
                }.start()
            }
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(result)
        root.addView(TextView(this).apply {
            setPadding(0, pad, 0, 0); setTextColor(Color.GRAY)
            text = "Додай віджет «Home Power» на головний екран. Тап по віджету — оновлення даних."
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    @SuppressLint("BatteryLife")
    private fun enableMonitor() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }
        MonitorService.start(this)
    }
}
