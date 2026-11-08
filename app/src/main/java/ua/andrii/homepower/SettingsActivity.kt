package ua.andrii.homepower

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Prefs(this)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }

        fun field(hint: String, value: String, type: Int) = EditText(this).apply {
            this.hint = hint; setText(value); inputType = type; root.addView(this)
        }
        root.addView(TextView(this).apply { text = "Home Power — логер Deye"; textSize = 20f })
        val host = field("IP логера, напр. 192.168.31.85", p.host, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val serial = field("Серійник логера Solarman (порожньо = Modbus TCP)", if (p.serial > 0) p.serial.toString() else "", InputType.TYPE_CLASS_NUMBER)
        val port = field("Порт (502 — Modbus TCP, 8899 — Solarman)", p.port.toString(), InputType.TYPE_CLASS_NUMBER)
        val result = TextView(this).apply { setPadding(0, pad, 0, 0); textSize = 15f }

        fun save(): Boolean {
            val raw = serial.text.toString().trim()
            val sn = if (raw.isEmpty()) 0L else raw.toLongOrNull()
            if (host.text.isBlank() || sn == null || sn !in 0..0xFFFFFFFFL) { result.text = "Перевір IP і серійник"; return false }
            p.host = host.text.toString().trim(); p.serial = sn
            p.port = port.text.toString().trim().toIntOrNull() ?: 502
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
                        "✅ Працює!\nSOC ${r.soc}%\nСпоживання ${r.loadW} W\nБатарея ${r.battW} W (+ розряд)\nМережа ${r.gridW} W\nPV ${r.pvW} W\nGen ${r.genW} W"
                    } catch (e: Exception) { "❌ ${e.javaClass.simpleName}: ${e.message}" }
                    runOnUiThread { result.setTextColor(if (msg.startsWith("✅")) Color.parseColor("#10B981") else Color.RED); result.text = msg }
                    PowerWidget.refresh(applicationContext, null)
                }.start()
            }
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(result)
        root.addView(TextView(this).apply {
            setPadding(0, pad, 0, 0); setTextColor(Color.GRAY)
            text = "Після перевірки додай віджет «Home Power» на головний екран. Тап по віджету — оновлення даних."
        })
        setContentView(root)
    }
}
