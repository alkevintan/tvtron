package com.tvtron.player.ui

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.tvtron.player.util.UpdateChecker
import com.tvtron.player.util.UpdateInstaller

object UpdateDialog {
    fun show(context: Context, release: UpdateChecker.Release) {
        val sizeMb = release.apkSize / 1024 / 1024
        AlertDialog.Builder(context)
            .setTitle("Update available — ${release.versionName}")
            .setMessage(buildString {
                append("Size: ${if (sizeMb > 0) "$sizeMb MB" else "${release.apkSize} B"}\n\n")
                append(release.notes.ifBlank { "No release notes." })
            })
            .setPositiveButton("Download & install") { _, _ ->
                if (!UpdateInstaller.canInstallUnknownApps(context)) {
                    Toast.makeText(context, "Allow install from this app, then try again", Toast.LENGTH_LONG).show()
                    UpdateInstaller.openInstallSettings(context)
                } else {
                    UpdateInstaller.download(context, release)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
