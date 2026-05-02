package com.tvtron.player.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File

object UpdateInstaller {

    private const val FILENAME = "TVTron-update.apk"

    fun download(context: Context, release: UpdateChecker.Release, onComplete: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        cleanupPrevious(app)

        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("${app.getString(com.tvtron.player.R.string.app_name)} ${release.versionName}")
            .setDescription("Downloading update")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, "updates/$FILENAME")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (completedId != id) return
                ctx.applicationContext.unregisterReceiver(this)

                val q = DownloadManager.Query().setFilterById(id)
                dm.query(q).use { c ->
                    if (c != null && c.moveToFirst()) {
                        val statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val status = if (statusIdx >= 0) c.getInt(statusIdx) else -1
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            installApk(app)
                            onComplete(true)
                            return
                        }
                    }
                }
                onComplete(false)
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
    }

    fun canInstallUnknownApps(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true
    }

    fun openInstallSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun installApk(context: Context) {
        val file = apkFile(context) ?: return
        if (!file.exists()) return

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.updates",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun apkFile(context: Context): File? {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        return File(File(dir, "updates"), FILENAME)
    }

    private fun cleanupPrevious(context: Context) {
        apkFile(context)?.takeIf { it.exists() }?.delete()
    }
}
