package com.tvtron.player.util

import android.content.Context
import android.os.Build
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object HttpClientFactory {
    @Volatile private var client: OkHttpClient? = null

    fun get(context: Context): OkHttpClient {
        return client ?: synchronized(this) {
            client ?: build(context.applicationContext).also { client = it }
        }
    }

    private fun build(appContext: Context): OkHttpClient {
        val builder = OkHttpClient.Builder()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            val tm = legacyTrustManager(appContext)
            if (tm != null) {
                val anchors = tm.acceptedIssuers.size
                android.util.Log.i("HttpClientFactory", "legacy trust active: $anchors anchors")
                val ssl = SSLContext.getInstance("TLS")
                ssl.init(null, arrayOf(tm), null)
                builder.sslSocketFactory(ssl.socketFactory, tm)
            } else {
                android.util.Log.w("HttpClientFactory", "legacy trust NULL - using default")
            }
        }
        return builder.build()
    }

    private fun legacyTrustManager(appContext: Context): X509TrustManager? {
        return try {
            val cf = CertificateFactory.getInstance("X.509")
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ks.load(null, null)
            val pem = appContext.assets.open("cacerts.pem").bufferedReader().use { it.readText() }
            val begin = "-----BEGIN CERTIFICATE-----"
            val end = "-----END CERTIFICATE-----"
            var idx = 0
            var count = 0
            while (true) {
                val s = pem.indexOf(begin, idx)
                if (s < 0) break
                val e = pem.indexOf(end, s)
                if (e < 0) break
                val block = pem.substring(s, e + end.length).toByteArray()
                try {
                    val cert = cf.generateCertificate(ByteArrayInputStream(block))
                    ks.setCertificateEntry("ca${count++}", cert)
                } catch (_: Throwable) {}
                idx = e + end.length
            }
            android.util.Log.i("HttpClientFactory", "loaded $count certs from cacerts.pem")
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(ks)
            tmf.trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
        } catch (t: Throwable) {
            android.util.Log.w("HttpClientFactory", "legacy trust load failed", t)
            null
        }
    }
}
