package com.tvtron.player.util

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

object UpdateChecker {

    data class Release(
        val versionName: String,
        val versionCode: IntArray,
        val apkUrl: String,
        val apkSize: Long,
        val notes: String,
        val htmlUrl: String
    )

    private data class GhRelease(
        @SerializedName("tag_name") val tagName: String?,
        @SerializedName("name") val name: String?,
        @SerializedName("body") val body: String?,
        @SerializedName("html_url") val htmlUrl: String?,
        @SerializedName("draft") val draft: Boolean = false,
        @SerializedName("prerelease") val prerelease: Boolean = false,
        @SerializedName("assets") val assets: List<GhAsset>?
    )

    private data class GhAsset(
        @SerializedName("name") val name: String?,
        @SerializedName("size") val size: Long?,
        @SerializedName("browser_download_url") val url: String?
    )

    suspend fun fetchLatest(context: Context): Release? = withContext(Dispatchers.IO) {
        val owner = context.getString(com.tvtron.player.R.string.update_github_owner)
        val repo = context.getString(com.tvtron.player.R.string.update_github_repo)
        if (owner.isBlank() || repo.isBlank()) return@withContext null

        val url = "https://api.github.com/repos/$owner/$repo/releases/latest"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "TVTron-UpdateChecker")
            .build()

        val client = HttpClientFactory.get(context)
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            resp.body?.string() ?: return@withContext null
        }

        val gh = Gson().fromJson(body, GhRelease::class.java) ?: return@withContext null
        if (gh.draft || gh.prerelease) return@withContext null

        val tag = gh.tagName?.removePrefix("v")?.trim().orEmpty()
        if (tag.isEmpty()) return@withContext null

        val apk = gh.assets?.firstOrNull { it.name?.endsWith(".apk", ignoreCase = true) == true }
            ?: return@withContext null
        val apkUrl = apk.url ?: return@withContext null

        Release(
            versionName = tag,
            versionCode = parseVersion(tag),
            apkUrl = apkUrl,
            apkSize = apk.size ?: 0L,
            notes = gh.body.orEmpty().trim(),
            htmlUrl = gh.htmlUrl.orEmpty()
        )
    }

    fun isNewer(latest: Release, currentVersionName: String): Boolean {
        val current = parseVersion(currentVersionName)
        return compare(latest.versionCode, current) > 0
    }

    private fun parseVersion(s: String): IntArray {
        val cleaned = s.removePrefix("v").substringBefore('-').substringBefore('+').trim()
        if (cleaned.isEmpty()) return intArrayOf(0)
        return cleaned.split('.').map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }.toIntArray()
    }

    private fun compare(a: IntArray, b: IntArray): Int {
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val ai = a.getOrElse(i) { 0 }
            val bi = b.getOrElse(i) { 0 }
            if (ai != bi) return ai - bi
        }
        return 0
    }
}
