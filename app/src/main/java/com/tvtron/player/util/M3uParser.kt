package com.tvtron.player.util

import com.tvtron.player.data.Channel

object M3uParser {

    data class ParsedPlaylist(
        val urlTvg: String,
        val channels: List<ParsedChannel>
    )

    data class ParsedChannel(
        val tvgId: String,
        val name: String,
        val logo: String,
        val groupTitle: String,
        val streamUrl: String,
        val userAgent: String,
        val referer: String
    ) {
        fun toEntity(playlistId: Long, sortIndex: Int): Channel = Channel(
            playlistId = playlistId,
            tvgId = tvgId,
            name = name.ifBlank { "Channel" },
            logo = logo,
            groupTitle = groupTitle,
            streamUrl = streamUrl,
            userAgent = userAgent,
            referer = referer,
            sortIndex = sortIndex
        )
    }

    private val ATTR_REGEX = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    fun parse(content: String): ParsedPlaylist {
        val lines = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty() || !lines[0].startsWith("#EXTM3U")) {
            return ParsedPlaylist("", emptyList())
        }

        // Different M3U authors use different attr names for the EPG URL.
        val headerAttrs = parseAttrs(lines[0])
        val urlTvg = (headerAttrs["url-tvg"] ?: headerAttrs["tvg-url"] ?: headerAttrs["x-tvg-url"])
            ?.split(',')?.firstOrNull()?.trim().orEmpty()
        val channels = mutableListOf<ParsedChannel>()

        var name = ""
        var attrs: Map<String, String> = emptyMap()
        var groupOverride = ""
        var ua = ""
        var ref = ""

        for (line in lines.drop(1)) {
            when {
                line.startsWith("#EXTM3U") -> { /* skip stray */ }
                line.startsWith("#EXTINF:") -> {
                    attrs = parseAttrs(line)
                    val commaIdx = line.indexOf(',')
                    name = if (commaIdx >= 0) line.substring(commaIdx + 1).trim() else ""
                }
                line.startsWith("#EXTGRP:") -> groupOverride = line.substringAfter("#EXTGRP:").trim()
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val v = line.substringAfter(':').trim()
                    val eq = v.indexOf('=')
                    if (eq > 0) {
                        val k = v.substring(0, eq).trim().lowercase()
                        val vv = v.substring(eq + 1).trim()
                        when (k) {
                            "http-user-agent" -> ua = vv
                            "http-referrer" -> ref = vv
                        }
                    }
                }
                line.startsWith("#KODIPROP:", ignoreCase = true) -> {
                    val v = line.substringAfter(':').trim()
                    val eq = v.indexOf('=')
                    if (eq > 0) {
                        val k = v.substring(0, eq).trim().lowercase()
                        val vv = v.substring(eq + 1).trim()
                        when (k) {
                            "http-user-agent" -> ua = vv
                            "http-referrer" -> ref = vv
                        }
                    }
                }
                line.startsWith("#") -> { /* skip other directives */ }
                else -> {
                    if (looksLikeUrl(line)) {
                        channels += ParsedChannel(
                            tvgId = attrs["tvg-id"].orEmpty(),
                            name = name.ifBlank { attrs["tvg-name"].orEmpty() },
                            logo = attrs["tvg-logo"].orEmpty(),
                            groupTitle = groupOverride.ifBlank { attrs["group-title"].orEmpty() },
                            streamUrl = line,
                            userAgent = ua,
                            referer = ref
                        )
                        name = ""
                        attrs = emptyMap()
                        groupOverride = ""
                        ua = ""
                        ref = ""
                    }
                }
            }
        }
        return ParsedPlaylist(urlTvg, channels)
    }

    private fun parseAttrs(line: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (m in ATTR_REGEX.findAll(line)) {
            out[m.groupValues[1].lowercase()] = m.groupValues[2]
        }
        return out
    }

    private fun looksLikeUrl(s: String): Boolean {
        val l = s.lowercase()
        return l.startsWith("http://") || l.startsWith("https://") ||
                l.startsWith("rtsp://") || l.startsWith("rtmp://") ||
                l.startsWith("rtmps://") || l.startsWith("udp://") ||
                l.startsWith("rtp://") || l.startsWith("mms://") ||
                l.startsWith("file://") || l.startsWith("content://")
    }
}
