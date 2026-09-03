package org.bc3pool.miner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Button
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.Date
import java.text.SimpleDateFormat
import java.math.BigDecimal
import java.util.concurrent.Executors
import kotlin.math.abs

class MiningActivity : AppCompatActivity() {
    private lateinit var hashRate: TextView
    private lateinit var status: TextView
    private lateinit var accepted: TextView
    private lateinit var rejected: TextView
    private lateinit var uptime: TextView
    private lateinit var acceptedRate: TextView
    private lateinit var maxHashRate: TextView
    private lateinit var temperature: TextView
    private lateinit var batteryPercent: TextView
    private lateinit var chargeStatus: TextView
    private lateinit var logPanel: View
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var safetyPanel: View
    private var normalPanel: View? = null
    private var pauseButton: Button? = null
    private var resumeButton: Button? = null
    private var manualPause = false
    private var difficultyValue: TextView? = null
    private var blockHeightValue: TextView? = null
    private var networkDifficultyValue: TextView? = null
    private var minerPage: View? = null
    private var networkPage: View? = null
    private var devicesPage: View? = null
    private var landscapePage = 0
    private var minerSessionActive = false
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private var networkRequestRunning = false
    private var cachedNetworkBlocks = JSONArray()

    private val networkRefresh = object : Runnable {
        override fun run() {
            loadNetworkStats()
            handler.postDelayed(this, 60_000L)
        }
    }
    private var startedAt = System.currentTimeMillis()
    private val handler = Handler(Looper.getMainLooper())
    private var acceptedShares = 0
    private var rejectedShares = 0
    private var acceptedBase = 0
    private var rejectedBase = 0
    private var lastRawAccepted = 0
    private var lastRawRejected = 0
    private var maxKhPerSecond = 0.0
    private var poolDisplay = "POOL"
    private var safetyDeadline = 0L
    private var safetyWaiting = false
    private var nextBlockExpectedAt = 0L
    private var pendingEtaView: TextView? = null
    private var walletAddress = ""
    private val workerHashrateSamples = mutableListOf<Double>()
    private var devicesWebView: WebView? = null

    private val workerRefresh = object : Runnable {
        override fun run() {
            devicesWebView?.evaluateJavascript("document.body ? document.body.innerText : ''") { encoded ->
                val text = try { JSONObject("{\"value\":$encoded}").optString("value") } catch (_: Exception) { "" }
                renderWorkerDashboard(text)
            }
            handler.postDelayed(this, 30_000L)
        }
    }

    private var nextLandscapeSwitchAt = 0L

    private val clock = object : Runnable {
        override fun run() {
            val seconds = (System.currentTimeMillis() - startedAt) / 1000
            uptime.text = String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
            acceptedRate.text = if (seconds > 0) String.format(Locale.US, "%.1f /hr", acceptedShares * 3600.0 / seconds) else "0.0 /hr"
            pendingEtaView?.text = when {
                nextBlockExpectedAt <= 0L -> "ETA  --:--"
                nextBlockExpectedAt <= System.currentTimeMillis() -> "ETA  DUE NOW"
                else -> {
                    val eta = (nextBlockExpectedAt - System.currentTimeMillis() + 999L) / 1000L
                    String.format(Locale.US, "ETA  %02d:%02d", eta / 60, eta % 60)
                }
            }
            if (safetyPanel.visibility == View.VISIBLE) {
                val remaining = ((safetyDeadline - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1000L
                findViewById<TextView>(R.id.safetyCountdown).text = if (manualPause) {
                    "PAUSED BY USER"
                } else if (remaining > 0) {
                    String.format(Locale.US, "%02d:%02d", remaining / 60, remaining % 60)
                } else if (safetyWaiting) "WAITING FOR SAFE LEVELS" else "00:00"
            }
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                nextLandscapeSwitchAt > 0L && System.currentTimeMillis() >= nextLandscapeSwitchAt) {
                val pages = if (minerSessionActive) listOf(0, 1, 2) else listOf(1, 2)
                val current = pages.indexOf(landscapePage).coerceAtLeast(0)
                showLandscapePage(pages[(current + 1) % pages.size], false)
                nextLandscapeSwitchAt = System.currentTimeMillis() + 60_000L
            }
            handler.postDelayed(this, 1000)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MinerService.ACTION_SAFETY) {
                val active = intent.getBooleanExtra(MinerService.EXTRA_SAFETY_ACTIVE, false)
                safetyPanel.visibility = if (active) View.VISIBLE else View.GONE
                normalPanel?.visibility = if (active) View.GONE else View.VISIBLE
                if (!active) {
                    manualPause = false
                    resumeButton?.visibility = View.GONE
                }
                if (active) {
                    showLandscapePage(0)
                    val reason = intent.getStringExtra(MinerService.EXTRA_SAFETY_REASON).orEmpty()
                    findViewById<TextView>(R.id.safetyReason).text = reason
                    val seconds = intent.getLongExtra(MinerService.EXTRA_SAFETY_REMAINING, 0L)
                    safetyDeadline = System.currentTimeMillis() + seconds * 1000L
                    safetyWaiting = seconds <= 0L
                    manualPause = intent.getBooleanExtra(MinerService.EXTRA_MANUAL_PAUSE, false)
                    resumeButton?.apply {
                        visibility = View.VISIBLE
                        isEnabled = manualPause
                        text = if (manualPause) "RESUME MINING" else "RESUME WHEN SAFE"
                        alpha = if (manualPause) 1f else 0.55f
                    }
                    status.text = if (manualPause) "PAUSED BY USER" else when {
                        reason.contains("Temperature", true) -> "PAUSED • TEMPERATURE"
                        reason.contains("Battery", true) -> "PAUSED • BATTERY"
                        else -> "PAUSED • SAFETY"
                    }
                } else status.text = if (minerSessionActive) "RESUMING MINER" else "NETWORK INFORMATION"
            } else {
                minerSessionActive = intent?.getBooleanExtra(MinerService.EXTRA_MINER_ACTIVE, false) == true
                updateLandscapeMiningControls()
                render(intent?.getStringExtra(MinerService.EXTRA_OUTPUT).orEmpty())
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            batteryPercent.text = if (level >= 0 && scale > 0) "${level * 100 / scale}%" else "--%"
            val tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
            temperature.text = if (tempC > 0) String.format(Locale.US, "%.1f °C", tempC) else "-- °C"
            when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> {
                    chargeStatus.text = "CHARGING"
                    chargeStatus.setTextColor(Color.rgb(75, 220, 135))
                }
                BatteryManager.BATTERY_STATUS_FULL -> {
                    chargeStatus.text = "FULL"
                    chargeStatus.setTextColor(Color.rgb(75, 220, 135))
                }
                BatteryManager.BATTERY_STATUS_NOT_CHARGING, BatteryManager.BATTERY_STATUS_DISCHARGING -> {
                    chargeStatus.text = "DISCHARGING"
                    chargeStatus.setTextColor(Color.rgb(255, 55, 55))
                }
                else -> {
                    chargeStatus.text = "UNKNOWN"
                    chargeStatus.setTextColor(Color.DKGRAY)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE &&
            intent.getBooleanExtra(EXTRA_NETWORK_VIEWER, false)) {
            startActivity(Intent(this, MinerChooserActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_mining)
        findViewById<View>(R.id.miningRoot).keepClearOfSystemBars()
        val sessionPrefs = getSharedPreferences("miner", MODE_PRIVATE)
        startedAt = sessionPrefs.getLong("sessionStarted", System.currentTimeMillis())
        acceptedShares = sessionPrefs.getInt("sessionAccepted", 0)
        rejectedShares = sessionPrefs.getInt("sessionRejected", 0)
        acceptedBase = sessionPrefs.getInt("acceptedBase", 0)
        rejectedBase = sessionPrefs.getInt("rejectedBase", 0)
        lastRawAccepted = sessionPrefs.getInt("lastRawAccepted", 0)
        lastRawRejected = sessionPrefs.getInt("lastRawRejected", 0)
        maxKhPerSecond = Double.fromBits(sessionPrefs.getLong("sessionMaxKhBits", 0L))
        hashRate = findViewById(R.id.hashRate)
        status = findViewById(R.id.miningStatus)
        accepted = findViewById(R.id.acceptedCount)
        rejected = findViewById(R.id.rejectedCount)
        uptime = findViewById(R.id.uptimeValue)
        acceptedRate = findViewById(R.id.acceptedRate)
        maxHashRate = findViewById(R.id.maxHashRate)
        temperature = findViewById(R.id.temperatureValue)
        batteryPercent = findViewById(R.id.batteryValue)
        chargeStatus = findViewById(R.id.chargeStatus)
        accepted.text = acceptedShares.toString()
        rejected.text = rejectedShares.toString()
        if (maxKhPerSecond > 0.0) maxHashRate.text = formatRate(maxKhPerSecond)
        val savedMiner = getSharedPreferences("miner", MODE_PRIVATE)
        val walletWorker = intent.getStringExtra(MinerService.EXTRA_WALLET)
            ?: savedMiner.getString("wallet", "").orEmpty()
        walletAddress = walletWorker.substringBeforeLast('.', walletWorker)
        val workerName = walletWorker.substringAfterLast('.', "PHONE").ifBlank { "PHONE" }
        findViewById<TextView>(R.id.workerName).text = workerName
        val pool = (intent.getStringExtra(MinerService.EXTRA_POOL)
            ?: savedMiner.getString("pool", "").orEmpty()).trim()
        poolDisplay = pool.removePrefix("stratum+tcp://").removePrefix("stratum://")
            .substringBefore('/').ifBlank { "POOL" }.uppercase(Locale.US)
        val threadCount = if (intent.hasExtra(MinerService.EXTRA_THREADS)) {
            intent.getIntExtra(MinerService.EXTRA_THREADS, Runtime.getRuntime().availableProcessors())
        } else savedMiner.getString("threads", null)?.toIntOrNull() ?: Runtime.getRuntime().availableProcessors()
        val hardware = Build.HARDWARE.takeIf { it.isNotBlank() }?.uppercase(Locale.US) ?: Build.MODEL.uppercase(Locale.US)
        findViewById<TextView>(R.id.cpuValue).text = "CPU • $hardware • $threadCount THREADS HASHING"
        logPanel = findViewById(R.id.logPanel)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        safetyPanel = findViewById(R.id.safetyPanel)
        difficultyValue = findViewById(R.id.difficultyValue)
        blockHeightValue = findViewById(R.id.blockHeightValue)
        networkDifficultyValue = findViewById(R.id.networkDifficultyValue)
        setupLandscapeDashboard()
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) showLandscapePage(2)
        normalPanel = findViewById(R.id.normalPanel)
        pauseButton = findViewById<Button?>(R.id.pauseButton)?.also { button ->
            button.setOnClickListener {
                startService(Intent(this, MinerService::class.java).setAction(MinerService.ACTION_PAUSE))
            }
        }
        resumeButton = findViewById<Button?>(R.id.resumeButton)?.also { button ->
            button.setOnClickListener {
                if (manualPause) startService(Intent(this, MinerService::class.java).setAction(MinerService.ACTION_RESUME))
            }
        }
        findViewById<Button>(R.id.safetyStopButton).setOnClickListener {
            startService(Intent(this, MinerService::class.java).setAction(MinerService.ACTION_STOP))
            finish()
        }

        findViewById<ImageButton>(R.id.logButton).setOnClickListener {
            logPanel.visibility = if (logPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (logPanel.visibility == View.VISIBLE) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
        findViewById<View>(R.id.closeLog).setOnClickListener { logPanel.visibility = View.GONE }

        val slider = findViewById<SeekBar?>(R.id.stopSlider)
        slider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                findViewById<TextView>(R.id.slideLabel).text = if (progress > 80) "RELEASE TO STOP" else "SLIDE TO STOP  ›››"
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) {
                if ((bar?.progress ?: 0) >= 90) {
                    startService(Intent(this@MiningActivity, MinerService::class.java).setAction(MinerService.ACTION_STOP))
                    finish()
                } else bar?.progress = 0
            }
        })
        findViewById<Button?>(R.id.landscapeStopButton)?.setOnClickListener {
            startService(Intent(this, MinerService::class.java).setAction(MinerService.ACTION_STOP))
            finish()
        }
        handler.post(clock)
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            handler.post(networkRefresh)
            nextLandscapeSwitchAt = System.currentTimeMillis() + 60_000L
        }
    }

    private fun setupLandscapeDashboard() {
        val container = findViewById<View?>(R.id.pageContainer) ?: return
        minerPage = findViewById(R.id.minerPage)
        devicesPage = findViewById(R.id.devicesPage)
        networkPage = findViewById(R.id.networkPage)
        setupDevicesDashboard()
        applyLandscapeTypeface(findViewById(R.id.miningRoot))
        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true
            override fun onFling(first: MotionEvent?, second: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (first == null || abs(second.x - first.x) < 90f || abs(velocityX) < 180f) return false
                val pages = if (minerSessionActive) listOf(0, 1, 2) else listOf(1, 2)
                val current = pages.indexOf(landscapePage).coerceAtLeast(0)
                val direction = if (second.x < first.x) 1 else -1
                showLandscapePage(pages[(current + direction + pages.size) % pages.size])
                return true
            }
        })
        container.setOnTouchListener { _, event -> gesture.onTouchEvent(event) }
        findViewById<Button?>(R.id.networkPauseButton)?.setOnClickListener {
            if (minerSessionActive) {
                startService(Intent(this, MinerService::class.java).setAction(MinerService.ACTION_PAUSE))
            } else {
                startSavedMiner()
            }
        }
        findViewById<Button?>(R.id.networkStopButton)?.setOnClickListener {
            startService(Intent(this, MinerService::class.java).setAction(MinerService.ACTION_STOP))
            finish()
        }
    }

    private fun updateLandscapeMiningControls() {
        val startPause = findViewById<Button?>(R.id.networkPauseButton) ?: return
        val stop = findViewById<Button?>(R.id.networkStopButton)
        startPause.text = if (minerSessionActive) "Ⅱ  PAUSE MINING" else "START MINER"
        stop?.visibility = if (minerSessionActive) View.VISIBLE else View.GONE
        if (!minerSessionActive && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            status.text = if (landscapePage == 1) "CURRENT MINING DEVICES" else "NETWORK INFORMATION"
        }
    }

    private fun applyLandscapeTypeface(view: View) {
        if (view is TextView) {
            val style = if (view.typeface?.isBold == true) Typeface.BOLD else Typeface.NORMAL
            view.typeface = Typeface.create(Typeface.MONOSPACE, style)
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) applyLandscapeTypeface(view.getChildAt(index))
    }

    private fun showLandscapePage(page: Int, resetTimer: Boolean = true) {
        if (minerPage == null || devicesPage == null || networkPage == null) return
        landscapePage = page.coerceIn(0, 2)
        minerPage?.visibility = if (landscapePage == 0) View.VISIBLE else View.GONE
        devicesPage?.visibility = if (landscapePage == 1) View.VISIBLE else View.GONE
        networkPage?.visibility = if (landscapePage == 2) View.VISIBLE else View.GONE
        status.text = when (landscapePage) {
            1 -> "CURRENT MINING DEVICES"
            2 -> "NETWORK INFORMATION"
            else -> if (minerSessionActive) "MINING • $poolDisplay" else "MINER"
        }
        if (resetTimer) {
            nextLandscapeSwitchAt = System.currentTimeMillis() + 60_000L
        }
    }

    private fun startSavedMiner() {
        val prefs = getSharedPreferences("miner", MODE_PRIVATE)
        val wallet = prefs.getString("wallet", "").orEmpty()
        val pool = prefs.getString("pool", "").orEmpty()
        if (wallet.isBlank() || pool.isBlank()) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_FORCE_PORTRAIT_SETUP, true))
            finish()
            return
        }
        val threads = prefs.getString("threads", "1")?.toIntOrNull()?.coerceIn(1, 64) ?: 1
        startedAt = System.currentTimeMillis()
        acceptedShares = 0
        rejectedShares = 0
        acceptedBase = 0
        rejectedBase = 0
        lastRawAccepted = 0
        lastRawRejected = 0
        maxKhPerSecond = 0.0
        prefs.edit().putLong("sessionStarted", startedAt).putInt("sessionAccepted", 0)
            .putInt("sessionRejected", 0).putInt("lastRawAccepted", 0).putInt("lastRawRejected", 0)
            .putInt("acceptedBase", 0).putInt("rejectedBase", 0).putLong("sessionMaxKhBits", 0L).apply()
        val startIntent = Intent(this, MinerService::class.java).apply {
            action = MinerService.ACTION_START
            putExtra(MinerService.EXTRA_WALLET, wallet)
            putExtra(MinerService.EXTRA_POOL, pool)
            putExtra(MinerService.EXTRA_PASSWORD, prefs.getString("password", "x").orEmpty())
            putExtra(MinerService.EXTRA_THREADS, threads)
            putExtra(MinerService.EXTRA_DONATE, prefs.getBoolean("donate", false))
            putExtra(MinerService.EXTRA_TEMP_CONTROL, prefs.getBoolean("tempControl", false))
            putExtra(MinerService.EXTRA_TEMP_THRESHOLD, prefs.getInt("tempThreshold", 40))
            putExtra(MinerService.EXTRA_BATTERY_CONTROL, prefs.getBoolean("batteryControl", false))
            putExtra(MinerService.EXTRA_BATTERY_THRESHOLD, prefs.getInt("batteryThreshold", 10))
            putExtra(MinerService.EXTRA_COOLDOWN_MINUTES, prefs.getInt("cooldown", 10))
            putExtra(MinerService.EXTRA_BATTERY_PAUSE_MINUTES, prefs.getInt("batteryPause", 60))
        }
        minerSessionActive = true
        ContextCompat.startForegroundService(this, startIntent)
        updateLandscapeMiningControls()
        showLandscapePage(0)
    }

    private fun setupDevicesDashboard() {
        val web = findViewById<WebView?>(R.id.devicesWebView) ?: return
        devicesWebView = web
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                handler.removeCallbacks(workerRefresh)
                handler.postDelayed(workerRefresh, 1_500L)
            }
        }
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadWithOverviewMode = true
        web.settings.useWideViewPort = true
        val source = findViewById<TextView?>(R.id.devicesSource)
        val url = when {
            poolDisplay.contains("BC3POOL.ORG") && walletAddress.isNotBlank() ->
                "https://bc3pool.org/miner/${Uri.encode(walletAddress)}"
            else -> null
        }
        if (url != null) {
            source?.text = "LIVE WORKERS • $poolDisplay • $walletAddress"
            web.loadUrl(url)
        } else {
            source?.text = "WORKER DASHBOARD UNAVAILABLE FROM $poolDisplay"
            web.loadDataWithBaseURL(null, "<html><body style='background:#141e29;color:white;text-align:center;font-family:monospace;padding:40px'><h2>Pool worker data unavailable</h2><p>This pool does not publish a verified wallet-worker dashboard endpoint yet.</p></body></html>", "text/html", "UTF-8", null)
        }
    }

    private fun renderWorkerDashboard(page: String) {
        if (page.isBlank()) return
        val hashMatch = listOf(
            Regex("(?:LIVE|CURRENT|TOTAL)?\\s*HASHRATE\\s*[:\\n ]+([0-9.,]+)\\s*([KMGT]?H/S)", RegexOption.IGNORE_CASE),
            Regex("([0-9.,]+)\\s*([KMGT]?H/S)\\s*(?:CURRENT|LIVE)?", RegexOption.IGNORE_CASE)
        ).firstNotNullOfOrNull { it.find(page) }
        if (hashMatch != null) {
            val number = hashMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
            val unit = hashMatch.groupValues[2].uppercase(Locale.US)
            val kh = when (unit) { "H/S" -> number / 1000.0; "MH/S" -> number * 1000.0; "GH/S" -> number * 1_000_000.0; "TH/S" -> number * 1_000_000_000.0; else -> number }
            findViewById<TextView?>(R.id.devicesHashrate)?.text = formatRate(kh)
            workerHashrateSamples.add(kh)
            if (workerHashrateSamples.size > 60) workerHashrateSamples.removeAt(0)
            findViewById<WorkerHashrateChartView?>(R.id.devicesHashrateChart)?.setValues(workerHashrateSamples)
        }
        val reporting = listOf(
            Regex("WORKERS?\\s+ONLINE\\s*[:\\n ]+(\\d+)", RegexOption.IGNORE_CASE),
            Regex("(\\d+)\\s+(?:ACTIVE|ONLINE)\\s+WORKERS?", RegexOption.IGNORE_CASE)
        ).firstNotNullOfOrNull { it.find(page)?.groupValues?.get(1)?.toIntOrNull() }
        val offlineExplicit = Regex("(?:NOT REPORTING|OFFLINE WORKERS?)\\s*[:\\n ]+(\\d+)", RegexOption.IGNORE_CASE)
            .find(page)?.groupValues?.get(1)?.toIntOrNull()
        val offline = offlineExplicit ?: Regex("\\b(?:IDLE|OFFLINE|NOT REPORTING)\\b", RegexOption.IGNORE_CASE).findAll(page).count()
        reporting?.let { findViewById<TextView?>(R.id.devicesReporting)?.text = it.toString() }
        findViewById<TextView?>(R.id.devicesOffline)?.text = offline.toString()
        val reward = listOf(
            Regex("(?:CURRENT REWARD|PENDING BALANCE|UNPAID BALANCE)\\s*[:\\n ]+([0-9.,]+)\\s*BC3", RegexOption.IGNORE_CASE),
            Regex("([0-9.,]+)\\s*BC3\\s*(?:PENDING|UNPAID)", RegexOption.IGNORE_CASE)
        ).firstNotNullOfOrNull { it.find(page)?.groupValues?.get(1) }
        reward?.let { findViewById<TextView?>(R.id.devicesReward)?.text = "$it BC3" }
    }

    private fun loadNetworkStats() {
        if (networkPage == null || networkRequestRunning) return
        networkRequestRunning = true
        networkExecutor.execute {
            try {
                val adjustment = JSONObject(readNetworkJson("https://m3mpool.space/api/v1/difficulty-adjustment"))
                val latestBlocks = JSONArray(readNetworkJson("https://m3mpool.space/api/v1/blocks"))
                if (latestBlocks.length() == 0) error("No recent blocks returned")
                val blocks = loadRecentBlockHistory(latestBlocks)
                val latest = blocks.getJSONObject(0)
                val height = latest.getInt("height")
                val extras = latest.getJSONObject("extras")
                val subsidySats = extras.optLong("reward") - extras.optLong("totalFees")
                val halvingInterval = 210_000
                val epochStart = height / halvingInterval * halvingInterval
                val nextHalving = epochStart + halvingInterval
                val blocksToHalving = nextHalving - height
                val halvingPercent = (height - epochStart) * 100.0 / halvingInterval
                val averageSeconds = adjustment.optDouble("timeAvg", 0.0) / 1000.0
                val mempool = try {
                    JSONObject(readNetworkJson("https://m3mpool.space/api/mempool"))
                } catch (_: Exception) {
                    JSONObject()
                }
                val adjustmentHistory = try {
                    JSONArray(readNetworkJson("https://m3mpool.space/api/v1/mining/difficulty-adjustments/1y"))
                } catch (_: Exception) {
                    JSONArray()
                }
                val change = adjustment.optDouble("difficultyChange", 0.0)
                val remaining = adjustment.optInt("remainingBlocks", 0)
                handler.post {
                    findViewById<TextView?>(R.id.epochRewardValue)?.text = "${formatBc3Sats(subsidySats)} BC3"
                    findViewById<TextView?>(R.id.blocksToHalvingValue)?.text = String.format(Locale.US, "%,d", blocksToHalving)
                    findViewById<TextView?>(R.id.halvingProgressValue)?.text = String.format(Locale.US, "%.1f%%", halvingPercent)
                    findViewById<android.widget.ProgressBar?>(R.id.halvingProgressBar)?.progress = (halvingPercent * 10).toInt()
                    findViewById<TextView?>(R.id.networkHeightValue)?.text = "HEIGHT\n${String.format(Locale.US, "%,d", height)}"
                    findViewById<TextView?>(R.id.nextHalvingValue)?.text = "NEXT\n${String.format(Locale.US, "%,d", nextHalving)}"
                    findViewById<TextView?>(R.id.adjustmentValue)?.text = String.format(Locale.US, "%+.1f%%", change)
                    findViewById<TextView?>(R.id.blocksToAdjustmentValue)?.text = String.format(Locale.US, "%,d", remaining)
                    findViewById<TextView?>(R.id.averageBlockTimeValue)?.text = if (averageSeconds >= 60.0) {
                        String.format(Locale.US, "%.1f minutes", averageSeconds / 60.0)
                    } else String.format(Locale.US, "%.1f seconds", averageSeconds)
                    renderBlockVisualizer(blocks, mempool, averageSeconds)
                    renderDifficultyHistory(adjustmentHistory)
                    findViewById<TextView?>(R.id.networkDataStatus)?.text = "NETWORK DATA • LIVE • M3MPOOL.SPACE"
                }
            } catch (error: Exception) {
                handler.post {
                    findViewById<TextView?>(R.id.networkDataStatus)?.text = "NETWORK DATA UNAVAILABLE • RETRYING"
                }
            } finally {
                networkRequestRunning = false
            }
        }
    }

    private fun renderBlockVisualizer(blocks: JSONArray, mempool: JSONObject, averageSeconds: Double) {
        val strip = findViewById<LinearLayout?>(R.id.blockVisualizerStrip) ?: return
        strip.removeAllViews()
        val latestTimestamp = blocks.optJSONObject(0)?.optLong("timestamp") ?: 0L
        nextBlockExpectedAt = if (latestTimestamp > 0L && averageSeconds > 0.0) {
            latestTimestamp * 1000L + (averageSeconds * 1000L).toLong()
        } else 0L

        val pendingOuter = blockOuter()
        val pendingCard = blockTile(R.drawable.pending_block_card)
        pendingCard.addView(blockText("NEXT BLOCK", 10f, Color.rgb(114, 216, 247), true))
        pendingEtaView = blockText("ETA --:--", 14f, Color.WHITE, true)
        pendingCard.addView(pendingEtaView)
        val pendingCount = mempool.optInt("count", 0)
        val pendingSize = mempool.optLong("vsize", 0L) / 1000.0
        val pendingFees = mempool.optLong("total_fee", 0L)
        pendingCard.addView(blockText("$pendingCount TRANSACTIONS", 9f, Color.rgb(197, 208, 220), true))
        pendingCard.addView(blockText(String.format(Locale.US, "%.1f kB", pendingSize), 9f, Color.WHITE, true))
        pendingCard.addView(blockText("${formatBc3Sats(pendingFees)} BC3 FEES", 8f, Color.WHITE, true))
        pendingOuter.addView(pendingCard)
        strip.addView(pendingOuter)

        for (index in 0 until minOf(blocks.length(), 3)) {
            val block = blocks.optJSONObject(index) ?: continue
            val extras = block.optJSONObject("extras") ?: JSONObject()
            val pool = extras.optJSONObject("pool")?.optString("name")
                ?.takeIf { it.isNotBlank() } ?: "Unknown miner"
            val rewardSats = extras.optLong("reward") - extras.optLong("totalFees")
            val feeSats = extras.optLong("totalFees")
            val txCount = block.optInt("tx_count", block.optInt("txCount", 0))
            val sizeKb = block.optLong("size", 0L) / 1000.0
            val height = block.optInt("height")
            val timestamp = block.optLong("timestamp")
            val olderTimestamp = blocks.optJSONObject(index + 1)?.optLong("timestamp") ?: 0L
            val miningTime = if (timestamp > olderTimestamp && olderTimestamp > 0L) {
                formatDuration(timestamp - olderTimestamp)
            } else "--"
            val blockId = block.optString("id")
            val outer = blockOuter()
            val card = blockTile(R.drawable.mining_stats_card)
            card.addView(blockText(if (index == 0) "LATEST BLOCK" else "BLOCK", 7f, Color.BLACK, true))
            card.addView(blockText(String.format(Locale.US, "%,d", height), 12f, Color.WHITE, true))
            card.addView(blockText("MINED IN $miningTime", 7f, Color.BLACK, true))
            card.addView(blockText(pool.uppercase(Locale.US), 7f, Color.WHITE, true))
            card.addView(blockText("$txCount TX  •  ${formatBc3Sats(rewardSats)} BC3", 7f, Color.WHITE, true))
            card.addView(blockText(relativeAge(timestamp), 7f, Color.BLACK, true))
            outer.addView(card)
            val detailText = "Block ${String.format(Locale.US, "%,d", height)} • mined in $miningTime • $pool • $txCount transactions • ${String.format(Locale.US, "%.1f", sizeKb)} kB\nReward ${formatBc3Sats(rewardSats)} BC3 • Fees ${formatBc3Sats(feeSats)} BC3 • tap to open explorer"
            outer.setOnClickListener {
                if (blockId.isNotBlank()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://m3mpool.space/block/$blockId")))
            }
            strip.addView(outer)
        }
        findViewById<android.widget.HorizontalScrollView?>(R.id.blockVisualizerScroll)?.scrollTo(0, 0)
    }

    private fun renderDifficultyHistory(history: JSONArray) {
        val list = findViewById<LinearLayout?>(R.id.difficultyHistoryList) ?: return
        list.removeAllViews()
        if (history.length() == 0) {
            list.addView(historyRow("Adjustment history unavailable", Color.LTGRAY))
            return
        }
        val date = SimpleDateFormat("dd MMM yyyy", Locale.US)
        for (index in 0 until minOf(history.length(), 40)) {
            val entry = history.optJSONArray(index) ?: continue
            if (entry.length() < 4) continue
            val timestamp = entry.optLong(0)
            val height = entry.optLong(1)
            val difficulty = entry.optDouble(2)
            val multiplier = entry.optDouble(3, 1.0)
            val change = (multiplier - 1.0) * 100.0
            val colour = if (change >= 0.0) Color.rgb(116, 227, 154) else Color.rgb(255, 107, 107)
            val value = String.format(Locale.US, "%s  •  %,d  •  DIFF %.4g  •  %+.2f%%",
                date.format(Date(timestamp * 1000L)), height, difficulty, change)
            list.addView(historyRow(value, colour))
        }
    }

    private fun historyRow(value: String, colour: Int) = TextView(this).apply {
        text = value
        textSize = 8f
        setTextColor(colour)
        setPadding(dp(5), dp(4), dp(5), dp(4))
        maxLines = 1
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setBackgroundColor(Color.rgb(25, 37, 51))
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)).apply {
            bottomMargin = dp(2)
        }
    }

    private fun blockOuter() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(dp(108), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(5)
        }
    }

    private fun blockTile(background: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(4), dp(5), dp(4), dp(1))
        setBackgroundResource(background)
        layoutParams = LinearLayout.LayoutParams(dp(108), dp(108))
    }

    private fun poolLabel(value: String) = TextView(this).apply {
        text = value
        textSize = 8f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        maxLines = 1
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(15))
    }

    private fun relativeAge(timestamp: Long): String {
        val seconds = ((System.currentTimeMillis() / 1000L) - timestamp).coerceAtLeast(0L)
        return when {
            seconds < 60 -> "${seconds}s AGO"
            seconds < 3600 -> "${seconds / 60}m AGO"
            seconds < 86_400 -> "${seconds / 3600}h AGO"
            else -> "${seconds / 86_400}d AGO"
        }
    }

    private fun formatDuration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        return when {
            safe >= 3600L -> String.format(Locale.US, "%dh %02dm", safe / 3600L, (safe % 3600L) / 60L)
            else -> String.format(Locale.US, "%dm %02ds", safe / 60L, safe % 60L)
        }
    }

    private fun loadRecentBlockHistory(latest: JSONArray): JSONArray {
        val unique = LinkedHashMap<String, JSONObject>()
        fun addAll(source: JSONArray) {
            for (index in 0 until source.length()) {
                val block = source.optJSONObject(index) ?: continue
                val key = block.optString("id").ifBlank { block.optInt("height").toString() }
                if (key.isNotBlank()) unique[key] = block
            }
        }
        addAll(latest)
        addAll(cachedNetworkBlocks)
        var oldestHeight = unique.values.minOfOrNull { it.optInt("height", Int.MAX_VALUE) } ?: Int.MAX_VALUE
        var pages = 0
        while (unique.size < 100 && oldestHeight > 1 && pages < 8) {
            val page = try {
                JSONArray(readNetworkJson("https://m3mpool.space/api/v1/blocks/${oldestHeight - 1}"))
            } catch (_: Exception) {
                break
            }
            if (page.length() == 0) break
            val priorSize = unique.size
            addAll(page)
            oldestHeight = unique.values.minOfOrNull { it.optInt("height", Int.MAX_VALUE) } ?: oldestHeight
            pages++
            if (unique.size == priorSize) break
        }
        val sorted = unique.values.sortedByDescending { it.optInt("height") }.take(100)
        return JSONArray().also { result -> sorted.forEach { result.put(it) }; cachedNetworkBlocks = result }
    }

    private fun blockText(value: String, size: Float, colour: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(colour)
        gravity = Gravity.CENTER
        maxLines = 1
        includeFontPadding = true
        typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun formatBc3Sats(sats: Long): String = BigDecimal.valueOf(sats)
        .movePointLeft(8).stripTrailingZeros().toPlainString()

    private fun readNetworkJson(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        return connection.inputStream.bufferedReader().use { it.readText() }.also { connection.disconnect() }
    }

    private fun render(output: String) {
        logText.text = output
        if (logPanel.visibility == View.VISIBLE) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }

        val rate = Regex("(?:Total:|TTF @)\\s*([0-9.]+)\\s*([kMGT]?h/s)", RegexOption.IGNORE_CASE)
            .findAll(output).lastOrNull()
        if (rate != null) {
            val value = rate.groupValues[1].toDoubleOrNull() ?: 0.0
            val unit = rate.groupValues[2]
            val rateKh = when {
                unit.equals("mh/s", true) -> value * 1000.0
                unit.equals("gh/s", true) -> value * 1_000_000.0
                unit.equals("th/s", true) -> value * 1_000_000_000.0
                else -> value
            }
            if (rateKh > maxKhPerSecond) {
                maxKhPerSecond = rateKh
                maxHashRate.text = formatRate(maxKhPerSecond)
                getSharedPreferences("miner", MODE_PRIVATE).edit().putLong("sessionMaxKhBits", maxKhPerSecond.toBits()).apply()
            }
            hashRate.text = if (unit.equals("kh/s", true) && value >= 1000.0) {
                String.format(Locale.US, "%.2f MH/s", value / 1000.0)
            } else {
                String.format(Locale.US, "%.2f %s", value, unit)
            }
        }
        val liveStatus = when {
            output.contains("authentication failed", true) -> "CONNECTION REJECTED"
            output.contains("Unable to start", true) -> "MINER START FAILED"
            output.contains("Stratum connect", true) || output.contains("New Work", true) || output.contains("Accepted", true) -> "MINING • $poolDisplay"
            else -> "STARTING MINER"
        }
        status.text = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            when (landscapePage) {
                1 -> "CURRENT MINING DEVICES"
                2 -> "NETWORK INFORMATION"
                else -> liveStatus
            }
        } else liveStatus
        val rawAccepted = Regex("Accepted\\s+(\\d+)", RegexOption.IGNORE_CASE).findAll(output).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
        if (rawAccepted != null) {
            if (rawAccepted < lastRawAccepted) acceptedBase += lastRawAccepted
            lastRawAccepted = rawAccepted
            acceptedShares = acceptedBase + rawAccepted
        }
        val rawRejected = Regex("Rejected\\s+(\\d+)", RegexOption.IGNORE_CASE).findAll(output).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
        if (rawRejected != null) {
            if (rawRejected < lastRawRejected) rejectedBase += lastRawRejected
            lastRawRejected = rawRejected
            rejectedShares = rejectedBase + rawRejected
        }
        accepted.text = acceptedShares.toString()
        rejected.text = rejectedShares.toString()
        Regex("Stratum\\s+([0-9.eE+\\-]+)", RegexOption.IGNORE_CASE).findAll(output).lastOrNull()
            ?.groupValues?.get(1)?.let { difficultyValue?.text = "SHARE DIFF  $it" }
        Regex("Block\\s+(\\d+)", RegexOption.IGNORE_CASE).findAll(output).lastOrNull()
            ?.groupValues?.get(1)?.let { blockHeightValue?.text = "BLOCK  $it" }
        Regex("Netdiff\\s+([0-9.eE+\\-]+)", RegexOption.IGNORE_CASE).findAll(output).lastOrNull()
            ?.groupValues?.get(1)?.let { networkDifficultyValue?.text = "NET DIFF  $it" }
        getSharedPreferences("miner", MODE_PRIVATE).edit()
            .putInt("sessionAccepted", acceptedShares).putInt("sessionRejected", rejectedShares)
            .putInt("acceptedBase", acceptedBase).putInt("rejectedBase", rejectedBase)
            .putInt("lastRawAccepted", lastRawAccepted).putInt("lastRawRejected", lastRawRejected).apply()
    }

    private fun formatRate(kh: Double): String = if (kh >= 1000.0) {
        String.format(Locale.US, "%.2f MH/s", kh / 1000.0)
    } else String.format(Locale.US, "%.2f kH/s", kh)

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(MinerService.ACTION_OUTPUT).apply { addAction(MinerService.ACTION_SAFETY) }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        startService(Intent(this, MinerService::class.java).setAction(MinerService.ACTION_QUERY))
    }

    override fun onStop() {
        unregisterReceiver(receiver)
        unregisterReceiver(batteryReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(clock)
        handler.removeCallbacks(networkRefresh)
        handler.removeCallbacks(workerRefresh)
        devicesWebView?.destroy()
        networkExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NETWORK_VIEWER = "networkViewer"
    }
}
