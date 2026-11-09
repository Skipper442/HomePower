package ua.andrii.homepower

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Фоновий моніторинг: опитує інвертор кожні 30 с, сповіщає про зникнення/появу мережі й оновлює віджет. */
class MonitorService : Service() {

    companion object {
        const val CH_MONITOR = "monitor"
        private const val CH_GRID = "grid_alerts"
        private const val ID_ONGOING = 1
        private const val ID_GRID = 2
        private const val INTERVAL_MS = 30_000L
        private const val CONFIRM = 2 // скільки опитувань поспіль має підтвердити зміну

        fun start(ctx: Context) {
            val i = Intent(ctx, MonitorService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, MonitorService::class.java)) }

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_MONITOR, "Фоновий моніторинг", NotificationManager.IMPORTANCE_MIN))
            nm.createNotificationChannel(NotificationChannel(CH_GRID, "Світло зникло / з'явилось", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    @Volatile private var running = false
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels(this)
        val n = ongoing("Стежу за світлом")
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID_ONGOING, n)
        if (!running) {
            running = true
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HomePower:monitor").apply { setReferenceCounted(false); acquire() }
            worker = Thread { loop() }.apply { start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun loop() {
        val p = Prefs(this)
        var candidate = -1
        var seen = 0
        while (running) {
            try {
                val r = Deye.read(this)
                PowerWidget.show(this, r)
                val state = if (r.gridOn) 1 else 0
                if (state == candidate) seen++ else { candidate = state; seen = 1 }
                if (seen >= CONFIRM && state != p.gridState) {
                    val previous = p.gridState
                    val since = p.gridSince
                    p.gridState = state
                    p.gridSince = System.currentTimeMillis()
                    if (previous != -1) alert(r, state == 1, since)
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                // поза домашньою мережею — тихо чекаємо наступного опитування
            }
            try { Thread.sleep(INTERVAL_MS) } catch (e: InterruptedException) { break }
        }
    }

    private fun alert(r: Reading, gridBack: Boolean, prevSince: Long) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val title: String
        val text: String
        if (gridBack) {
            title = "✅ Світло з'явилось о $time"
            val off = if (prevSince > 0) "Не було ${Fmt.duration(System.currentTimeMillis() - prevSince)} · " else ""
            text = "${off}батарея ${r.soc}%"
        } else {
            title = "⚡ Світло зникло о $time"
            val batt = Fmt.battery(r, Prefs(this).minSoc)
            text = "Батарея ${r.soc}%" + if (batt.isNotEmpty()) " · $batt" else ""
        }
        val n = Notification.Builder(this, CH_GRID)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ID_GRID, n)
    }

    private fun ongoing(text: String): Notification =
        Notification.Builder(this, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle("Home Power")
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setShowWhen(false)
            .build()

    private fun openApp() = PendingIntent.getActivity(this, 0, Intent(this, SettingsActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
