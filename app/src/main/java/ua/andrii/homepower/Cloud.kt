package ua.andrii.homepower

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * Хмарна «скринька» на Firebase Realtime Database (REST).
 * Дані лежать за адресою /h/<секретний ключ>.json — без ключа їх не прочитати й не змінити.
 */
object Cloud {

    fun newKey(): String {
        val abc = "abcdefghijkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        return (1..32).map { abc[rnd.nextInt(abc.length)] }.joinToString("")
    }

    private fun url(p: Prefs) = p.cloudUrl.trimEnd('/') + "/h/" + p.cloudKey + ".json"

    fun push(p: Prefs, r: Reading) {
        val body = JSONObject()
            .put("t", r.time).put("soc", r.soc).put("battW", r.battW).put("gridW", r.gridW)
            .put("genW", r.genW).put("loadW", r.loadW).put("pvW", r.pvW)
            .put("gridV", r.gridV).put("battV", r.battV).put("capAh", r.capAh)
        request(url(p), "PUT", body.toString())
    }

    fun fetch(p: Prefs): Reading {
        val text = request(url(p), "GET", null)
        if (text.isBlank() || text == "null") throw IOException("хаб ще не надсилав дані")
        val j = JSONObject(text)
        return Reading(
            soc = j.getInt("soc"), battW = j.getInt("battW"), gridW = j.getInt("gridW"),
            genW = j.optInt("genW"), loadW = j.getInt("loadW"), pvW = j.optInt("pvW"),
            gridV = j.optDouble("gridV", 0.0), battV = j.optDouble("battV", 0.0), capAh = j.optInt("capAh"),
            time = j.getLong("t"), fromCloud = true,
        )
    }

    private fun request(u: String, method: String, body: String?): String {
        val c = URL(u).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 6000; c.readTimeout = 6000
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 401 || code == 403) throw IOException("Firebase відмовив у доступі (перевір правила бази і ключ)")
            if (code !in 200..299) throw IOException("Firebase HTTP $code")
            return text
        } finally { c.disconnect() }
    }
}
