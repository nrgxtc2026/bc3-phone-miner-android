package org.bc3pool.miner

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.net.wifi.WifiManager
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

class MinerService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var process: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val lines = ArrayDeque<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var launchArgs: List<String>? = null
    private var nativeDir: String = ""
    private var temperatureControl = false
    private var temperatureThreshold = 40
    private var batteryControl = false
    private var batteryThreshold = 10
    private var cooldownMillis = 10 * 60 * 1000L
    private var batteryPauseMillis = 60 * 60 * 1000L
    private var safetyPaused = false
    private var cooldownUntil = 0L
    private var safetyReason = ""
    private var manuallyStopped = false
    private var manuallyPaused = false

    private val safetyMonitor = object : Runnable {
        override fun run() {
            checkSafety()
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        (getSystemService(NotificationManager::class.java)).createNotificationChannel(
            NotificationChannel(CHANNEL, "BC3 mining", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopMiner()
            ACTION_START -> startMiner(intent)
            ACTION_PAUSE -> pauseMiner()
            ACTION_RESUME -> resumeMiner()
            ACTION_QUERY -> publishCurrentState()
        }
        return START_NOT_STICKY
    }

    private fun startMiner(intent: Intent) {
        if (process?.isAlive == true) return
        val wallet = intent.getStringExtra(EXTRA_WALLET) ?: return
        val pool = intent.getStringExtra(EXTRA_POOL) ?: return
        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: "x"
        val threads = intent.getIntExtra(EXTRA_THREADS, 1)
        val donate = intent.getBooleanExtra(EXTRA_DONATE, false)
        temperatureControl = intent.getBooleanExtra(EXTRA_TEMP_CONTROL, false)
        temperatureThreshold = intent.getIntExtra(EXTRA_TEMP_THRESHOLD, 40)
        batteryControl = intent.getBooleanExtra(EXTRA_BATTERY_CONTROL, false)
        batteryThreshold = intent.getIntExtra(EXTRA_BATTERY_THRESHOLD, 10)
        cooldownMillis = intent.getIntExtra(EXTRA_COOLDOWN_MINUTES, 10) * 60_000L
        batteryPauseMillis = intent.getIntExtra(EXTRA_BATTERY_PAUSE_MINUTES, 60).coerceIn(1, 120) * 60_000L
        manuallyStopped = false
        manuallyPaused = false
        synchronized(lines) { lines.clear() }
        startForeground(NOTIFICATION_ID, notification("Starting miner…"))
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BC3Miner::Mining").apply { acquire() }
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BC3Miner::PoolConnection").apply {
                setReferenceCounted(false)
                acquire()
            }

        nativeDir = applicationInfo.nativeLibraryDir
        val engine = "$nativeDir/libbc3miner.so"
        val engineFile = java.io.File(engine)
        publish("Starting Android miner for ${Build.SUPPORTED_ABIS.joinToString()}…")
        if (!engineFile.exists()) {
            publish("No compatible mining engine is included for this phone (${Build.SUPPORTED_ABIS.joinToString()}).")
            stopSelf()
            return
        }
        val args = mutableListOf(engine, "-a", "sha3d", "--no-extranonce", "-o", pool, "-u", wallet, "-p", password, "-t", threads.toString())
        if (donate) args.add(1, "--donate")
        launchArgs = args
        handler.removeCallbacks(safetyMonitor)
        handler.post(safetyMonitor)
        checkSafety()
        if (!safetyPaused) startNativeProcess()
    }

    private fun startNativeProcess() {
        val args = launchArgs ?: return
        if (manuallyStopped || manuallyPaused || process?.isAlive == true) return
        safetyPaused = false
        executor.execute {
            try {
                val builder = ProcessBuilder(args).redirectErrorStream(true)
                builder.environment()["LD_LIBRARY_PATH"] = nativeDir
                process = builder.start()
                BufferedReader(InputStreamReader(process!!.inputStream)).use { reader ->
                    reader.forEachLine { publish(it) }
                }
                val exitCode = process?.waitFor()
                if (!safetyPaused && !manuallyPaused && !manuallyStopped) publish("Miner stopped (exit $exitCode).")
            } catch (e: Exception) {
                if (!safetyPaused && !manuallyPaused && !manuallyStopped) publish("Unable to start native miner: ${e.message}")
            } finally {
                process = null
            }
        }
    }

    private fun checkSafety() {
        if (manuallyStopped || manuallyPaused) return
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 100
        val tempC = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
        val temperatureExceeded = temperatureControl && tempC >= temperatureThreshold
        val batteryLow = batteryControl && percent <= batteryThreshold
        val reason = when {
            temperatureExceeded -> "Temperature safety pause: %.1f °C (limit %d °C).".format(tempC, temperatureThreshold)
            batteryLow -> "Battery safety pause: $percent% (limit $batteryThreshold%)."
            else -> null
        }
        if (reason != null && !safetyPaused) {
            safetyPaused = true
            safetyReason = reason
            cooldownUntil = System.currentTimeMillis() + if (batteryLow) batteryPauseMillis else cooldownMillis
            publish(reason)
            publish("Mining will resume after the cooldown when conditions are safe.")
            process?.destroy()
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(reason))
        } else if (reason == null && safetyPaused && process?.isAlive != true && System.currentTimeMillis() >= cooldownUntil) {
            publish("Safety conditions are clear. Resuming mining.")
            sendBroadcast(Intent(ACTION_SAFETY).setPackage(packageName).putExtra(EXTRA_SAFETY_ACTIVE, false))
            startNativeProcess()
        }
        if (safetyPaused) {
            val remaining = ((cooldownUntil - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1000L
            val displayReason = reason ?: safetyReason
            sendBroadcast(Intent(ACTION_SAFETY).setPackage(packageName)
                .putExtra(EXTRA_SAFETY_ACTIVE, true).putExtra(EXTRA_SAFETY_REASON, displayReason)
                .putExtra(EXTRA_SAFETY_REMAINING, remaining))
        }
    }

    private fun publish(line: String) {
        val cleanLine = line.replace(Regex("\u001B\\[[;\\d]*m"), "")
        synchronized(lines) {
            lines.addLast(cleanLine)
            while (lines.size > 120) lines.removeFirst()
            sendBroadcast(Intent(ACTION_OUTPUT).setPackage(packageName)
                .putExtra(EXTRA_OUTPUT, lines.joinToString("\n"))
                .putExtra(EXTRA_MINER_ACTIVE, !manuallyStopped && launchArgs != null))
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(cleanLine.take(80)))
    }

    private fun pauseMiner() {
        if (manuallyStopped || manuallyPaused) return
        manuallyPaused = true
        process?.destroy()
        publish("Mining paused by user.")
        sendSafetyState(true, "Mining paused by user.", -1L, true)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("Mining paused by user"))
    }

    private fun resumeMiner() {
        if (manuallyStopped || !manuallyPaused) return
        manuallyPaused = false
        sendSafetyState(false, "", 0L, false)
        publish("User resumed mining.")
        checkSafety()
        if (!safetyPaused) handler.postDelayed({
            if (!manuallyPaused && !manuallyStopped && process?.isAlive != true) startNativeProcess()
        }, 500L)
    }

    private fun publishCurrentState() {
        val output = synchronized(lines) { lines.joinToString("\n") }
        sendBroadcast(Intent(ACTION_OUTPUT).setPackage(packageName)
            .putExtra(EXTRA_OUTPUT, output)
            .putExtra(EXTRA_MINER_ACTIVE, !manuallyStopped && launchArgs != null))
        when {
            manuallyPaused -> sendSafetyState(true, "Mining paused by user.", -1L, true)
            safetyPaused -> {
                val remaining = ((cooldownUntil - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1000L
                sendSafetyState(true, safetyReason, remaining, false)
            }
            else -> sendSafetyState(false, "", 0L, false)
        }
    }

    private fun sendSafetyState(active: Boolean, reason: String, remaining: Long, manual: Boolean) {
        sendBroadcast(Intent(ACTION_SAFETY).setPackage(packageName)
            .putExtra(EXTRA_SAFETY_ACTIVE, active)
            .putExtra(EXTRA_SAFETY_REASON, reason)
            .putExtra(EXTRA_SAFETY_REMAINING, remaining)
            .putExtra(EXTRA_MANUAL_PAUSE, manual))
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.logo)
        .setContentTitle("BC3 Miner running")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "Stop", PendingIntent.getService(this, 1, Intent(this, MinerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun stopMiner() {
        manuallyStopped = true
        manuallyPaused = false
        handler.removeCallbacks(safetyMonitor)
        process?.destroy()
        process = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { stopMiner(); executor.shutdownNow(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "org.bc3pool.miner.START"
        const val ACTION_STOP = "org.bc3pool.miner.STOP"
        const val ACTION_OUTPUT = "org.bc3pool.miner.OUTPUT"
        const val ACTION_SAFETY = "org.bc3pool.miner.SAFETY"
        const val ACTION_PAUSE = "org.bc3pool.miner.PAUSE"
        const val ACTION_RESUME = "org.bc3pool.miner.RESUME"
        const val ACTION_QUERY = "org.bc3pool.miner.QUERY"
        const val EXTRA_OUTPUT = "output"
        const val EXTRA_WALLET = "wallet"
        const val EXTRA_POOL = "pool"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_THREADS = "threads"
        const val EXTRA_DONATE = "donate"
        const val EXTRA_TEMP_CONTROL = "temperatureControl"
        const val EXTRA_TEMP_THRESHOLD = "temperatureThreshold"
        const val EXTRA_BATTERY_CONTROL = "batteryControl"
        const val EXTRA_BATTERY_THRESHOLD = "batteryThreshold"
        const val EXTRA_COOLDOWN_MINUTES = "cooldownMinutes"
        const val EXTRA_BATTERY_PAUSE_MINUTES = "batteryPauseMinutes"
        const val EXTRA_SAFETY_ACTIVE = "safetyActive"
        const val EXTRA_SAFETY_REASON = "safetyReason"
        const val EXTRA_SAFETY_REMAINING = "safetyRemaining"
        const val EXTRA_MANUAL_PAUSE = "manualPause"
        const val EXTRA_MINER_ACTIVE = "minerActive"
        private const val CHANNEL = "mining"
        private const val NOTIFICATION_ID = 1001
    }
}
