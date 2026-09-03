package org.bc3pool.miner

import android.content.Intent
import android.os.Bundle
import android.content.res.Configuration
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MinerChooserActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            startActivity(Intent(this, MiningActivity::class.java)
                .putExtra(MiningActivity.EXTRA_NETWORK_VIEWER, true))
            finish()
            return
        }
        setContentView(R.layout.activity_miner_chooser)
        findViewById<View>(R.id.chooserRoot).keepClearOfSystemBars()
        findViewById<Button>(R.id.createMiner).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_CREATE, true))
        }
        findViewById<TextView>(R.id.chooserDisclaimerLink).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("EULA and Software/Hardware Liability Disclaimer")
                .setMessage(MainActivity.DISCLAIMER)
                .setPositiveButton("Close", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("miner", MODE_PRIVATE)
        val profiles = prefs.getStringSet("profiles", emptySet()).orEmpty().toMutableSet()
        val legacyWorker = prefs.getString("worker", "").orEmpty()
        if (profiles.isEmpty() && legacyWorker.isNotBlank()) {
            profiles.add(legacyWorker)
            val editor = prefs.edit().putStringSet("profiles", profiles)
            listOf("wallet", "worker", "pool", "password", "threads", "tempThreshold", "batteryThreshold", "cooldown", "batteryPause", "performanceMode").forEach { key ->
                if (prefs.contains(key)) {
                    when (val value = prefs.all[key]) {
                        is String -> editor.putString("profile.$legacyWorker.$key", value)
                        is Int -> editor.putInt("profile.$legacyWorker.$key", value)
                    }
                }
            }
            listOf("donate", "tempControl", "batteryControl").forEach { key ->
                if (prefs.contains(key)) editor.putBoolean("profile.$legacyWorker.$key", prefs.getBoolean(key, false))
            }
            editor.apply()
        }

        val names = profiles.sorted()
        val spinner = findViewById<Spinner>(R.id.savedMiners)
        val empty = findViewById<TextView>(R.id.noSavedMiners)
        val actions = findViewById<View>(R.id.savedMinerActions)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        empty.visibility = if (names.isEmpty()) View.VISIBLE else View.GONE
        spinner.visibility = if (names.isEmpty()) View.GONE else View.VISIBLE
        actions.visibility = if (names.isEmpty()) View.GONE else View.VISIBLE

        findViewById<Button>(R.id.startSavedMiner).setOnClickListener {
            val name = spinner.selectedItem?.toString() ?: return@setOnClickListener
            startActivity(Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_PROFILE, name)
                .putExtra(MainActivity.EXTRA_AUTO_START, true))
        }
        findViewById<Button>(R.id.editSavedMiner).setOnClickListener {
            val name = spinner.selectedItem?.toString() ?: return@setOnClickListener
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_PROFILE, name))
        }
    }
}
