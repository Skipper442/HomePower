package ua.andrii.homepower

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Оновлення «по повітрю» з релізу на GitHub — тільки за кнопкою в застосунку. */
object Updater {
    private const val BASE = "https://github.com/Skipper442/HomePower/releases/download/latest/"

    data class Remote(val code: Long, val name: String)

    fun currentCode(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }

    fun currentName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Нова версія на GitHub, або null, якщо встановлена актуальна. */
    fun check(ctx: Context): Remote? {
        val j = JSONObject(get(BASE + "version.json").decodeToString())
        val r = Remote(j.getLong("versionCode"), j.optString("versionName"))
        return if (r.code > currentCode(ctx)) r else null
    }

    /** Завантажує APK і передає системі на встановлення. */
    fun downloadAndInstall(ctx: Context) {
        val app = ctx.applicationContext
        val apk = File(app.cacheDir, "HomePower-update.apk")
        apk.writeBytes(get(BASE + "HomePower.apk"))

        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(app, id, Intent(app, UpdateReceiver::class.java), flags)
            s.commit(pi.intentSender)
        }
    }

    private fun get(u: String): ByteArray {
        var url = URL(u)
        repeat(5) {
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10000; c.readTimeout = 30000
            try {
                when (c.responseCode) {
                    in 200..299 -> return c.inputStream.use { it.readBytes() }
                    301, 302, 303, 307, 308 -> url = URL(url, c.getHeaderField("Location"))
                    else -> throw java.io.IOException("GitHub HTTP ${c.responseCode}")
                }
            } finally { c.disconnect() }
        }
        throw java.io.IOException("Забагато переадресацій")
    }
}

/** Результат встановлення оновлення. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "невідома помилка"
                android.widget.Toast.makeText(context, "Оновлення не встановилось: $msg", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
