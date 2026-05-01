package com.tvtron.player.ui

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.tvtron.player.R
import com.tvtron.player.util.SettingsManager
import com.tvtron.player.util.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.settingsToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        setupPlayer()
        setupAppearance()
        setupEpg()
        setupUpdates()
        setupAbout()
    }

    private fun setupPlayer() {
        val skin = findViewById<SwitchMaterial>(R.id.trinitronSwitch)
        skin.isChecked = SettingsManager.isTrinitronSkin(this)
        skin.setOnCheckedChangeListener { _, on -> SettingsManager.setTrinitronSkin(this, on) }

        updateAspectLabel()
        findViewById<View>(R.id.aspectRow).setOnClickListener { showAspectDialog() }
    }

    private fun updateAspectLabel() {
        findViewById<TextView>(R.id.aspectLabel).text = SettingsManager.getDefaultAspect(this).label
    }

    private fun showAspectDialog() {
        val modes = SettingsManager.AspectMode.values()
        val labels = modes.map { it.label }.toTypedArray()
        val curIdx = modes.indexOf(SettingsManager.getDefaultAspect(this))
        AlertDialog.Builder(this)
            .setTitle(R.string.default_aspect)
            .setSingleChoiceItems(labels, curIdx) { dialog, which ->
                SettingsManager.setDefaultAspect(this, modes[which])
                updateAspectLabel()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupAppearance() {
        updateThemeLabel()
        findViewById<View>(R.id.themeRow).setOnClickListener { showThemeDialog() }
    }

    private fun updateThemeLabel() {
        findViewById<TextView>(R.id.themeLabel).text = SettingsManager.getTheme(this).label
    }

    private fun showThemeDialog() {
        val themes = SettingsManager.Theme.values()
        val labels = themes.map { it.label }.toTypedArray()
        val curIdx = themes.indexOf(SettingsManager.getTheme(this))
        AlertDialog.Builder(this)
            .setTitle(R.string.theme)
            .setSingleChoiceItems(labels, curIdx) { dialog, which ->
                val t = themes[which]
                SettingsManager.setTheme(this, t)
                updateThemeLabel()
                when (t) {
                    SettingsManager.Theme.LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                    SettingsManager.Theme.DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                    SettingsManager.Theme.SYSTEM -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private val backChoices = intArrayOf(0, 1, 2, 3, 5, 7, 14)
    private val fwdChoices = intArrayOf(1, 2, 3, 5, 7, 10, 14)

    private fun setupEpg() {
        updateEpgLabels()
        findViewById<View>(R.id.epgBackRow).setOnClickListener {
            showDayChoiceDialog("Days back", backChoices, SettingsManager.getEpgDaysBack(this)) {
                SettingsManager.setEpgDaysBack(this, it)
                updateEpgLabels()
            }
        }
        findViewById<View>(R.id.epgFwdRow).setOnClickListener {
            showDayChoiceDialog("Days forward", fwdChoices, SettingsManager.getEpgDaysForward(this)) {
                SettingsManager.setEpgDaysForward(this, it)
                updateEpgLabels()
            }
        }
    }

    private fun updateEpgLabels() {
        findViewById<TextView>(R.id.epgBackLabel).text = "${SettingsManager.getEpgDaysBack(this)} day(s)"
        findViewById<TextView>(R.id.epgFwdLabel).text = "${SettingsManager.getEpgDaysForward(this)} day(s)"
    }

    private fun showDayChoiceDialog(title: String, choices: IntArray, current: Int, onPick: (Int) -> Unit) {
        val labels = choices.map { "$it day(s)" }.toTypedArray()
        val curIdx = choices.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, curIdx) { dialog, which ->
                onPick(choices[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupUpdates() {
        val auto = findViewById<SwitchMaterial>(R.id.autoUpdateSwitch)
        auto.isChecked = SettingsManager.isAutoUpdateCheck(this)
        auto.setOnCheckedChangeListener { _, on -> SettingsManager.setAutoUpdateCheck(this, on) }

        updateLastCheckLabel()
        findViewById<View>(R.id.checkUpdateRow).setOnClickListener { checkUpdate() }
    }

    private fun updateLastCheckLabel() {
        val last = SettingsManager.getLastUpdateCheck(this)
        findViewById<TextView>(R.id.lastCheckLabel).text =
            if (last == 0L) "Never checked"
            else "Last check: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last))
    }

    private fun checkUpdate() {
        Toast.makeText(this, "Checking…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val release = withContext(Dispatchers.IO) { UpdateChecker.fetchLatest(this@SettingsActivity) }
            SettingsManager.setLastUpdateCheck(this@SettingsActivity, System.currentTimeMillis())
            updateLastCheckLabel()
            if (release == null) {
                Toast.makeText(this@SettingsActivity, "No release found", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val current = packageManager.getPackageInfo(packageName, PackageManager.GET_META_DATA).versionName ?: "0.0.0"
            if (!UpdateChecker.isNewer(release, current)) {
                Toast.makeText(this@SettingsActivity, "Up to date ($current)", Toast.LENGTH_SHORT).show()
                return@launch
            }
            UpdateDialog.show(this@SettingsActivity, release)
        }
    }

    private fun setupAbout() {
        val v = packageManager.getPackageInfo(packageName, PackageManager.GET_META_DATA).versionName ?: "0.0.0"
        findViewById<TextView>(R.id.versionLabel).text = "Version $v"
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
