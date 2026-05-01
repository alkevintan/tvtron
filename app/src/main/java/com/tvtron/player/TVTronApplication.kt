package com.tvtron.player

import android.app.Application
import android.util.Log
import com.google.android.gms.security.ProviderInstaller

class TVTronApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            ProviderInstaller.installIfNeededAsync(
                this,
                object : ProviderInstaller.ProviderInstallListener {
                    override fun onProviderInstalled() {}
                    override fun onProviderInstallFailed(errorCode: Int, recoveryIntent: android.content.Intent?) {
                        Log.w("TVTronApplication", "TLS provider install failed: $errorCode")
                    }
                }
            )
        } catch (t: Throwable) {
            Log.w("TVTronApplication", "ProviderInstaller unavailable", t)
        }
    }
}
