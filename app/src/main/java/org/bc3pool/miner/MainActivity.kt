package org.bc3pool.miner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import android.widget.Button
import android.widget.EditText
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.Toast
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private data class PoolChoice(val name: String, val endpoint: String?, val password: String = "x")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forcePortraitSetup = intent.getBooleanExtra(EXTRA_FORCE_PORTRAIT_SETUP, false)
        if (forcePortraitSetup) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            intent.removeExtra(EXTRA_FORCE_PORTRAIT_SETUP)
        } else if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            !intent.getBooleanExtra(EXTRA_AUTO_START, false)) {
            startActivity(Intent(this, MiningActivity::class.java)
                .putExtra(MiningActivity.EXTRA_NETWORK_VIEWER, true))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        findViewById<android.view.View>(R.id.mainRoot).keepClearOfSystemBars()
        val wallet = findViewById<EditText>(R.id.wallet)
        val worker = findViewById<EditText>(R.id.worker)
        val pool = findViewById<EditText>(R.id.pool)
        val threads = findViewById<EditText>(R.id.threads)
        val performanceMode = findViewById<Spinner>(R.id.performanceMode)
        val prefs = getSharedPreferences("miner", MODE_PRIVATE)
        // A saved profile may request one start when the user presses Start Miner in
        // the chooser. Consume that request immediately so activity recreation (for
        // example, rotating the phone) can never start or restart mining.
        val autoStartRequested = savedInstanceState == null &&
            intent.getBooleanExtra(EXTRA_AUTO_START, false)
        intent.removeExtra(EXTRA_AUTO_START)
        val selectedProfile = intent.getStringExtra(EXTRA_PROFILE)
        val creating = intent.getBooleanExtra(EXTRA_CREATE, false)
        fun key(name: String) = selectedProfile?.let { "profile.$it.$name" } ?: name
        fun savedString(name: String, fallback: String) = if (creating) fallback else prefs.getString(key(name), fallback).orEmpty()
        fun savedInt(name: String, fallback: Int) = if (creating) fallback else prefs.getInt(key(name), fallback)
        fun savedBoolean(name: String, fallback: Boolean) = if (creating) fallback else prefs.getBoolean(key(name), fallback)
        val savedIdentity = savedString("wallet", "")
        wallet.setText(savedIdentity.substringBeforeLast('.', savedIdentity))
        worker.setText(savedString("worker", selectedProfile ?: savedIdentity.substringAfterLast('.', "phone")))
        val savedPool = savedString("pool", "stratum+tcp://eu1.bc3pool.org:3333")
        pool.setText(savedPool)
        var poolPassword = savedString("password", if (savedPool.contains("bc3pool.org", true)) "d=0.03125" else "x")
        threads.setText(savedString("threads", "8"))
        val performanceModes = listOf("Balanced (recommended)", "Efficient", "Maximum", "Manual")
        performanceMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, performanceModes)
        performanceMode.setSelection(savedInt("performanceMode", 0).coerceIn(0, performanceModes.lastIndex))
        fun updateThreadControl() {
            threads.isEnabled = performanceMode.selectedItemPosition == 3
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            if (!threads.isEnabled) {
                val selectedThreads = when (performanceMode.selectedItemPosition) {
                    1 -> (cores + 1) / 2
                    2 -> cores
                    else -> if (cores <= 4) cores else cores - 2
                }
                threads.setText(selectedThreads.toString())
            }
        }
        performanceMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = updateThreadControl()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        performanceMode.post { updateThreadControl() }
        val pools = listOf(
            PoolChoice("BC3Pool EU — cPPLNS", "stratum+tcp://eu1.bc3pool.org:3333", "d=0.03125"),
            PoolChoice("BC3Pool US — cPPLNS", "stratum+tcp://us1.bc3pool.org:3333", "d=0.03125"),
            PoolChoice("BC3Pool EU — SOLO", "stratum+tcp://eu1.bc3pool.org:4333", "d=0.03125"),
            PoolChoice("BC3Pool US — SOLO", "stratum+tcp://us1.bc3pool.org:4333", "d=0.03125"),
            PoolChoice("ArgfaMining LATAM — CPU SOLO", "stratum+tcp://stratum.argfamining.com:24052"),
            PoolChoice("ArgfaMining US — CPU SOLO", "stratum+tcp://stratum-us.argfamining.com:24052"),
            PoolChoice("ArgfaMining EU — CPU SOLO", "stratum+tcp://stratum-eu.argfamining.com:24052"),
            PoolChoice("ArgfaMining India — CPU SOLO", "stratum+tcp://stratum-in.argfamining.com:24052"),
            PoolChoice("ArgfaMining LATAM — CPU PROP", "stratum+tcp://stratum.argfamining.com:24053"),
            PoolChoice("ArgfaMining US — CPU PROP", "stratum+tcp://stratum-us.argfamining.com:24053"),
            PoolChoice("ArgfaMining EU — CPU PROP", "stratum+tcp://stratum-eu.argfamining.com:24053"),
            PoolChoice("ArgfaMining India — CPU PROP", "stratum+tcp://stratum-in.argfamining.com:24053"),
            PoolChoice("RPlant EU — PROP", "stratum+tcp://eu.rplant.xyz:7157"),
            PoolChoice("RPlant Asia-Pacific — PROP", "stratum+tcp://asia.rplant.xyz:7157"),
            PoolChoice("RPlant America — PROP", "stratum+tcp://na.rplant.xyz:7157"),
            PoolChoice("AxeHub — SOLO", "stratum+tcp://pool.axehub.app:3338"),
            PoolChoice("AxeHub — PPLNS", "stratum+tcp://pool.axehub.app:4338"),
            PoolChoice("PythonPool — SOLO", "stratum+tcp://stratum.pythonpool.dev:3357"),
            PoolChoice("Vexta UK — PPLNS", "stratum+tcp://vexta-pool.co.uk:7333"),
            PoolChoice("Vexta US — PPLNS", "stratum+tcp://us.vexta-pool.co.uk:7333"),
            PoolChoice("Vexta UK — SOLO", "stratum+tcp://vexta-pool.co.uk:7334"),
            PoolChoice("Vexta US — SOLO", "stratum+tcp://us.vexta-pool.co.uk:7334"),
            PoolChoice("Vexta UK — PROP", "stratum+tcp://vexta-pool.co.uk:7335"),
            PoolChoice("Vexta US — PROP", "stratum+tcp://us.vexta-pool.co.uk:7335"),
            PoolChoice("Crypto-Éire — SOLO", "stratum+tcp://stratum.crypto-eire.com:3362"),
            PoolChoice("HashBay — currently unavailable", null),
            PoolChoice("Zpool — endpoint not published", null),
            PoolChoice("HuurrexPool — endpoint not verified", null),
            PoolChoice("Hashforge — endpoint not verified", null),
            PoolChoice("BTC3Forge — endpoint not verified", null),
            PoolChoice("Coin-Miners — endpoint not verified", null),
            PoolChoice("BaikalMine — endpoint not verified", null),
            PoolChoice("AriaBrain — endpoint not verified", null),
            PoolChoice("Custom pool", null)
        )
        val poolSelector = findViewById<Spinner>(R.id.poolSelector)
        poolSelector.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, pools.map { it.name })
        val matchedPool = pools.indexOfFirst { it.endpoint == savedPool }.takeIf { it >= 0 } ?: pools.lastIndex
        poolSelector.setSelection(matchedPool)
        var selectorReady = false
        poolSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (selectorReady) pools[position].endpoint?.let { pool.setText(it) }
                if (selectorReady) poolPassword = pools[position].password
                selectorReady = true
                if (pools[position].endpoint == null) pool.hint = "Enter the pool's current Stratum URL"
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val tempControl = findViewById<CheckBox>(R.id.temperatureControl)
        val batteryControl = findViewById<CheckBox>(R.id.batteryControl)
        val tempThreshold = findViewById<SeekBar>(R.id.temperatureThreshold)
        val batteryThreshold = findViewById<SeekBar>(R.id.batteryThreshold)
        val cooldown = findViewById<SeekBar>(R.id.cooldownMinutes)
        val batteryPause = findViewById<SeekBar>(R.id.batteryPauseMinutes)
        val donateControl = findViewById<CheckBox>(R.id.donate)
        donateControl.isChecked = savedBoolean("donate", false)
        tempControl.isChecked = savedBoolean("tempControl", false)
        batteryControl.isChecked = savedBoolean("batteryControl", false)
        tempThreshold.progress = savedInt("tempThreshold", 40) - 30
        batteryThreshold.progress = savedInt("batteryThreshold", 10) - 5
        cooldown.progress = savedInt("cooldown", 10) - 1
        batteryPause.progress = savedInt("batteryPause", 60).coerceIn(1, 120) - 1
        fun updateSafetyLabels() {
            findViewById<android.widget.TextView>(R.id.temperatureLabel).text = "Maximum temperature: ${tempThreshold.progress + 30} °C"
            findViewById<android.widget.TextView>(R.id.batteryLabel).text = "Minimum battery: ${batteryThreshold.progress + 5}%"
            findViewById<android.widget.TextView>(R.id.cooldownLabel).text = "Temperature cooldown before restart: ${cooldown.progress + 1} minutes"
            findViewById<android.widget.TextView>(R.id.batteryPauseLabel).text = "Battery charging pause: ${batteryPause.progress + 1} minutes"
        }
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updateSafetyLabels()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        tempThreshold.setOnSeekBarChangeListener(listener)
        batteryThreshold.setOnSeekBarChangeListener(listener)
        cooldown.setOnSeekBarChangeListener(listener)
        batteryPause.setOnSeekBarChangeListener(listener)
        updateSafetyLabels()

        fun beginMining() {
            val baseWallet = wallet.text.toString().filter {
                it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.'
            }.trimEnd('.')
            val workerName = worker.text.toString().filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank { "phone" }
            val w = "$baseWallet.$workerName"
            val p = pool.text.toString().trim()
            val t = threads.text.toString().toIntOrNull()?.coerceIn(1, 64) ?: 1
            val mode = performanceMode.selectedItemPosition.coerceIn(0, performanceModes.lastIndex)
            if (w.isBlank() || p.isBlank()) {
                Toast.makeText(this, "Enter a wallet/worker and pool URL.", Toast.LENGTH_LONG).show()
                return
            }
            val profilePrefix = "profile.$workerName."
            val profiles = prefs.getStringSet("profiles", emptySet()).orEmpty().toMutableSet().apply {
                selectedProfile?.let { remove(it) }
                add(workerName)
            }
            prefs.edit().putString("wallet", w).putString("worker", workerName).putString("pool", p).putString("threads", t.toString())
                .putString("password", poolPassword)
                .putStringSet("profiles", profiles)
                .putString(profilePrefix + "wallet", w).putString(profilePrefix + "worker", workerName)
                .putString(profilePrefix + "pool", p).putString(profilePrefix + "threads", t.toString())
                .putString(profilePrefix + "password", poolPassword)
                .putInt(profilePrefix + "performanceMode", mode)
                .putBoolean(profilePrefix + "donate", donateControl.isChecked)
                .putBoolean(profilePrefix + "tempControl", tempControl.isChecked)
                .putInt(profilePrefix + "tempThreshold", tempThreshold.progress + 30)
                .putBoolean(profilePrefix + "batteryControl", batteryControl.isChecked)
                .putInt(profilePrefix + "batteryThreshold", batteryThreshold.progress + 5)
                .putInt(profilePrefix + "cooldown", cooldown.progress + 1)
                .putInt(profilePrefix + "batteryPause", batteryPause.progress + 1)
                .putBoolean("donate", donateControl.isChecked)
                .putBoolean("tempControl", tempControl.isChecked).putInt("tempThreshold", tempThreshold.progress + 30)
                .putBoolean("batteryControl", batteryControl.isChecked).putInt("batteryThreshold", batteryThreshold.progress + 5)
                .putInt("cooldown", cooldown.progress + 1)
                .putInt("batteryPause", batteryPause.progress + 1)
                .putInt("performanceMode", mode)
                .putLong("sessionStarted", System.currentTimeMillis())
                .putInt("sessionAccepted", 0).putInt("sessionRejected", 0)
                .putInt("lastRawAccepted", 0).putInt("lastRawRejected", 0)
                .putInt("acceptedBase", 0).putInt("rejectedBase", 0)
                .putLong("sessionMaxKhBits", 0L).apply()
            val intent = Intent(this, MinerService::class.java).apply {
                action = MinerService.ACTION_START
                putExtra(MinerService.EXTRA_WALLET, w)
                putExtra(MinerService.EXTRA_POOL, p)
                putExtra(MinerService.EXTRA_PASSWORD, poolPassword)
                putExtra(MinerService.EXTRA_THREADS, t)
                putExtra(MinerService.EXTRA_DONATE, donateControl.isChecked)
                putExtra(MinerService.EXTRA_TEMP_CONTROL, tempControl.isChecked)
                putExtra(MinerService.EXTRA_TEMP_THRESHOLD, tempThreshold.progress + 30)
                putExtra(MinerService.EXTRA_BATTERY_CONTROL, batteryControl.isChecked)
                putExtra(MinerService.EXTRA_BATTERY_THRESHOLD, batteryThreshold.progress + 5)
                putExtra(MinerService.EXTRA_COOLDOWN_MINUTES, cooldown.progress + 1)
                putExtra(MinerService.EXTRA_BATTERY_PAUSE_MINUTES, batteryPause.progress + 1)
            }
            ContextCompat.startForegroundService(this, intent)
            startActivity(Intent(this, MiningActivity::class.java)
                .putExtra(MinerService.EXTRA_THREADS, t)
                .putExtra(MinerService.EXTRA_WALLET, w)
                .putExtra(MinerService.EXTRA_POOL, p))
        }
        val startButton = findViewById<Button>(R.id.start)
        startButton.setOnClickListener {
            if (prefs.getBoolean("skipMiningNotice", false)) {
                beginMining()
                return@setOnClickListener
            }
            val noticeView = layoutInflater.inflate(R.layout.dialog_mining_notice, null)
            val skipNextTime = noticeView.findViewById<CheckBox>(R.id.dontShowMiningNotice)
            val noticeDialog = AlertDialog.Builder(this)
                .setTitle("Keep mining connected")
                .setView(noticeView)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Continue") { _, _ ->
                    if (skipNextTime.isChecked) prefs.edit().putBoolean("skipMiningNotice", true).apply()
                    beginMining()
                }
                .setPositiveButton("Open battery settings") { _, _ ->
                    try {
                        Toast.makeText(this, "Select BC3 Miner, then choose Don't optimise or Allow.", Toast.LENGTH_LONG).show()
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")))
                    }
                }
                .create()
            noticeDialog.setOnShowListener {
                fun forceBlack(view: android.view.View) {
                    if (view is TextView) view.setTextColor(android.graphics.Color.BLACK)
                    if (view is android.view.ViewGroup) {
                        for (index in 0 until view.childCount) forceBlack(view.getChildAt(index))
                    }
                }
                forceBlack(noticeView)
                val titleId = resources.getIdentifier("alertTitle", "id", "android")
                if (titleId != 0) noticeDialog.findViewById<TextView>(titleId)?.setTextColor(android.graphics.Color.BLACK)
            }
            noticeDialog.show()
        }
        findViewById<Button>(R.id.stop).setOnClickListener {
            startService(Intent(this, MinerService::class.java).setAction(MinerService.ACTION_STOP))
        }
        if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        findViewById<TextView>(R.id.disclaimerLink).setOnClickListener {
            AlertDialog.Builder(this).setTitle("Software and Hardware Liability Disclaimer")
                .setMessage(DISCLAIMER).setPositiveButton("Close", null).show()
        }
        if (autoStartRequested) {
            startButton.post { startButton.performClick() }
        }
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_CREATE = "create"
        const val EXTRA_AUTO_START = "autoStart"
        const val EXTRA_FORCE_PORTRAIT_SETUP = "forcePortraitSetup"
        const val DISCLAIMER = """PLEASE READ THIS DISCLAIMER CAREFULLY BEFORE INSTALLING OR USING THE SOFTWARE.

1. Express Waiver of Responsibility and Liability

This software (the \"App\") is provided on an \"AS IS\" and \"AS AVAILABLE\" basis, without warranties or conditions of any kind, either express or implied. The developer(s), authors, copyright holders, and distributors (collectively, the \"Developer\") expressly disclaim all liability and responsibility to any user, entity, or third party for any direct, indirect, incidental, consequential, special, exemplary, or punitive damages, losses, or negative impacts arising out of or in connection with:

• The installation, execution, or use of the App;
• The operational behavior, performance, malfunction, hardware failure, or physical damage to any device, system, or hardware component upon which the App is installed;
• Any loss of data, system downtime, battery degradation, thermal overload, security vulnerability, or unintended device behavior.

2. User Assumption of Risk

By downloading, installing, or executing this App, you (\"End User\") acknowledge and agree that:

• You assume 100% of the risk associated with the performance, safety, and operation of the device(s) containing the App.
• The Developer exercise no control over the hardware environment, physical conditions, operating system configurations, or third-party modifications present on your device.
• You are solely responsible for ensuring regular data backups and taking necessary precautions to prevent potential system or device impact.

3. Limitation of Legal Remedies

To the maximum extent permitted by applicable law, in no event shall the Developer be liable for any claims, demands, or damages whatsoever—whether in an action of contract, tort, negligence, or otherwise—arising from, out of, or in connection with the App or the use or other dealings in the App.

If your local jurisdiction does not allow the exclusion or limitation of liability for consequential or incidental damages, the Developer's total liability shall be limited to the minimum extent permitted by law or the total amount paid by you (if any) to acquire the software.

4. Express Consent

INSTALLING, DOWNLOADING, OR USING THIS APPLICATION CONSTITUTES YOUR EXPLICIT ACKNOWLEDGMENT AND AGREEMENT TO ALL TERMS OF THIS DISCLAIMER. IF YOU DO NOT AGREE TO THESE TERMS, REMOVE AND UNINSTALL THE APPLICATION FROM ALL DEVICES IMMEDIATELY."""
    }

}
