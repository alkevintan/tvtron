package com.tvtron.player.util

import android.net.Uri

/**
 * Shared encoder/decoder for the TVTron QR / deep-link URIs:
 *
 *   tvtron://playlist?n=<name>&u=<m3u-url>&e=<epg-url>
 *   tvtron://channel?n=<name>&u=<stream-url>&l=<logo>&g=<group>&t=<tvg-id>&ua=<user-agent>&r=<referer>
 *
 * The pre-0.1.3 pipe format (`TVTRON|name|url|epg`) is still recognised for back-compat.
 */
object TvtronUri {

    sealed class Payload {
        data class PlaylistPayload(val name: String, val source: String, val epg: String) : Payload()
        data class ChannelPayload(
            val name: String,
            val streamUrl: String,
            val logo: String,
            val groupTitle: String,
            val tvgId: String,
            val userAgent: String,
            val referer: String
        ) : Payload()
    }

    fun parse(text: String): Payload? {
        val trimmed = text.trim()
        if (trimmed.startsWith("tvtron://", ignoreCase = true)) {
            val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
            return when (uri.host?.lowercase()) {
                "playlist" -> {
                    val u = uri.getQueryParameter("u").orEmpty()
                    if (u.isBlank()) null
                    else Payload.PlaylistPayload(
                        name = uri.getQueryParameter("n").orEmpty(),
                        source = u,
                        epg = uri.getQueryParameter("e").orEmpty()
                    )
                }
                "channel" -> {
                    val u = uri.getQueryParameter("u").orEmpty()
                    if (u.isBlank()) null
                    else Payload.ChannelPayload(
                        name = uri.getQueryParameter("n").orEmpty(),
                        streamUrl = u,
                        logo = uri.getQueryParameter("l").orEmpty(),
                        groupTitle = uri.getQueryParameter("g").orEmpty(),
                        tvgId = uri.getQueryParameter("t").orEmpty(),
                        userAgent = uri.getQueryParameter("ua").orEmpty(),
                        referer = uri.getQueryParameter("r").orEmpty()
                    )
                }
                else -> null
            }
        }
        // Legacy pipe format
        val parts = trimmed.split('|')
        if (parts.size >= 3 && parts[0] == "TVTRON") {
            return Payload.PlaylistPayload(
                name = parts.getOrNull(1).orEmpty(),
                source = parts.getOrNull(2).orEmpty(),
                epg = parts.getOrNull(3).orEmpty()
            )
        }
        return null
    }

    fun encodePlaylist(name: String, source: String, epg: String): String =
        Uri.Builder().scheme("tvtron").authority("playlist")
            .appendQueryParameter("n", name)
            .appendQueryParameter("u", source)
            .apply { if (epg.isNotBlank()) appendQueryParameter("e", epg) }
            .build().toString()

    fun encodeChannel(
        name: String,
        streamUrl: String,
        logo: String = "",
        groupTitle: String = "",
        tvgId: String = "",
        userAgent: String = "",
        referer: String = ""
    ): String =
        Uri.Builder().scheme("tvtron").authority("channel")
            .appendQueryParameter("n", name)
            .appendQueryParameter("u", streamUrl)
            .apply {
                if (logo.isNotBlank()) appendQueryParameter("l", logo)
                if (groupTitle.isNotBlank()) appendQueryParameter("g", groupTitle)
                if (tvgId.isNotBlank()) appendQueryParameter("t", tvgId)
                if (userAgent.isNotBlank()) appendQueryParameter("ua", userAgent)
                if (referer.isNotBlank()) appendQueryParameter("r", referer)
            }
            .build().toString()
}
