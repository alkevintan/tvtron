package com.tvtron.player.ui

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tvtron.player.R
import com.tvtron.player.util.SettingsManager
import com.tvtron.player.util.UpdateChecker
import kotlinx.coroutines.launch

class AboutDialog : DialogFragment() {

    private var versionName: String = "0.0.0"
    private var updateStatusView: TextView? = null
    private var updateProgressView: ProgressBar? = null
    private var checkRow: View? = null
    private var checkInFlight: Boolean = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_about, null)

        versionName = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.0.0"
        } catch (_: Exception) { "0.0.0" }
        view.findViewById<TextView>(R.id.aboutVersion).text = "${getString(R.string.app_name)} v$versionName"

        setupUpdateRow(view)
        setupGithubRow(view)

        return MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.about)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .create()
    }

    private fun setupUpdateRow(view: View) {
        val owner = getString(R.string.update_github_owner)
        val repo = getString(R.string.update_github_repo)
        checkRow = view.findViewById(R.id.checkUpdatesRow)
        updateStatusView = view.findViewById(R.id.updateStatus)
        updateProgressView = view.findViewById(R.id.updateProgress)
        if (owner.isBlank() || repo.isBlank()) {
            updateStatusView?.text = "Updates not configured"
            checkRow?.isEnabled = false
            return
        }
        updateStatusView?.text = "v$versionName"
        checkRow?.setOnClickListener { runCheck() }
    }

    private fun runCheck() {
        if (checkInFlight) return
        checkInFlight = true
        updateProgressView?.visibility = View.VISIBLE
        updateStatusView?.text = "Checking…"
        lifecycleScope.launch {
            val release = try { UpdateChecker.fetchLatest(requireContext()) } catch (_: Exception) { null }
            updateProgressView?.visibility = View.GONE
            checkInFlight = false
            SettingsManager.setLastUpdateCheck(requireContext(), System.currentTimeMillis())
            if (release == null) {
                updateStatusView?.text = "v$versionName — check failed"
                Toast.makeText(requireContext(), "Update check failed", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (UpdateChecker.isNewer(release, versionName)) {
                dismiss()
                UpdateDialog.show(requireContext(), release)
            } else {
                updateStatusView?.text = "v$versionName — up to date"
            }
        }
    }

    private fun setupGithubRow(view: View) {
        val owner = getString(R.string.update_github_owner)
        val repo = getString(R.string.update_github_repo)
        val urlLabel = view.findViewById<TextView>(R.id.githubUrl)
        val row = view.findViewById<View>(R.id.githubRow)
        if (owner.isNotBlank() && repo.isNotBlank()) {
            val url = "https://github.com/$owner/$repo"
            urlLabel.text = "github.com/$owner/$repo"
            row.setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { Toast.makeText(requireContext(), "Cannot open URL", Toast.LENGTH_SHORT).show() }
            }
        } else {
            row.visibility = View.GONE
        }
    }
}
