package com.tvtron.player.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.tvtron.player.MainActivity
import com.tvtron.player.R
import com.tvtron.player.util.LaunchRefresher
import com.tvtron.player.util.SettingsManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Recreate (rare for this transient screen) — skip the wait, just route through.
        if (savedInstanceState != null) {
            goNext(); return
        }

        val refreshJob = LaunchRefresher.start(this)
        lifecycleScope.launch {
            val started = System.currentTimeMillis()
            withTimeoutOrNull(REFRESH_CAP_MS) { refreshJob.join() }
            val elapsed = System.currentTimeMillis() - started
            val remaining = (MIN_SPLASH_MS - elapsed).coerceAtLeast(0L)
            if (remaining > 0) delay(remaining)
            goNext()
        }
    }

    private fun goNext() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun applyTheme() {
        when (SettingsManager.getTheme(this)) {
            SettingsManager.Theme.LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            SettingsManager.Theme.DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            SettingsManager.Theme.SYSTEM -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        setTheme(R.style.Theme_TVTron_Splash)
    }

    companion object {
        // Minimum splash visibility — keeps logo on screen even when refresh is a no-op.
        private const val MIN_SPLASH_MS = 600L
        // Hard cap — slow networks shouldn't trap the user on the splash. Refresh
        // continues in LaunchRefresher's process scope after we navigate away.
        private const val REFRESH_CAP_MS = 8_000L
    }
}
