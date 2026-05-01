package com.tvtron.player.util

import android.util.Xml
import com.tvtron.player.data.EpgChannel
import com.tvtron.player.data.EpgProgram
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object XmltvParser {

    data class Parsed(
        val channels: List<EpgChannel>,
        val programs: List<EpgProgram>
    )

    /** Parses XMLTV stream. Caller supplies playlistId + retention window for in-loop pruning. */
    fun parse(
        stream: InputStream,
        playlistId: Long,
        keepFromMs: Long,
        keepUntilMs: Long
    ): Parsed {
        val channels = mutableListOf<EpgChannel>()
        val programs = mutableListOf<EpgProgram>()

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, null)

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> readChannel(parser, playlistId)?.let { channels += it }
                    "programme" -> readProgramme(parser, playlistId, keepFromMs, keepUntilMs)?.let { programs += it }
                }
            }
            event = parser.next()
        }
        return Parsed(channels, programs)
    }

    private fun readChannel(parser: XmlPullParser, playlistId: Long): EpgChannel? {
        val id = parser.getAttributeValue(null, "id") ?: return skipTo(parser, "channel").let { null }
        var displayName = ""
        var icon = ""
        while (true) {
            val ev = parser.next()
            if (ev == XmlPullParser.END_TAG && parser.name == "channel") break
            if (ev == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "display-name" -> if (displayName.isBlank()) displayName = readText(parser)
                    "icon" -> {
                        icon = parser.getAttributeValue(null, "src").orEmpty()
                        skipTo(parser, "icon")
                    }
                    else -> skipCurrent(parser)
                }
            }
            if (ev == XmlPullParser.END_DOCUMENT) break
        }
        return EpgChannel(playlistId = playlistId, xmltvId = id, displayName = displayName, icon = icon)
    }

    private fun readProgramme(
        parser: XmlPullParser,
        playlistId: Long,
        keepFromMs: Long,
        keepUntilMs: Long
    ): EpgProgram? {
        val startStr = parser.getAttributeValue(null, "start") ?: return skipTo(parser, "programme").let { null }
        val stopStr = parser.getAttributeValue(null, "stop") ?: return skipTo(parser, "programme").let { null }
        val channelId = parser.getAttributeValue(null, "channel") ?: return skipTo(parser, "programme").let { null }

        val start = parseXmltvTime(startStr)
        val stop = parseXmltvTime(stopStr)
        if (start == null || stop == null) {
            skipTo(parser, "programme")
            return null
        }
        // window prune
        val keep = stop >= keepFromMs && start <= keepUntilMs

        var title = ""
        var desc = ""
        var category = ""
        while (true) {
            val ev = parser.next()
            if (ev == XmlPullParser.END_TAG && parser.name == "programme") break
            if (ev == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> if (title.isBlank()) title = readText(parser)
                    "desc" -> if (desc.isBlank()) desc = readText(parser)
                    "category" -> if (category.isBlank()) category = readText(parser)
                    else -> skipCurrent(parser)
                }
            }
            if (ev == XmlPullParser.END_DOCUMENT) break
        }
        if (!keep) return null
        return EpgProgram(
            playlistId = playlistId,
            xmltvId = channelId,
            start = start,
            stop = stop,
            title = title,
            description = desc,
            category = category
        )
    }

    private fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        while (true) {
            val ev = parser.next()
            if (ev == XmlPullParser.END_TAG) break
            if (ev == XmlPullParser.TEXT) sb.append(parser.text)
            if (ev == XmlPullParser.END_DOCUMENT) break
        }
        return sb.toString().trim()
    }

    private fun skipCurrent(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun skipTo(parser: XmlPullParser, tag: String) {
        while (true) {
            val ev = parser.next()
            if (ev == XmlPullParser.END_DOCUMENT) return
            if (ev == XmlPullParser.END_TAG && parser.name == tag) return
        }
    }

    /** XMLTV time: YYYYMMDDhhmmss [+ZZZZ]. Optional offset; default UTC. */
    fun parseXmltvTime(s: String): Long? {
        val raw = s.trim()
        if (raw.length < 14) return null
        val datePart = raw.substring(0, 14)
        val tzPart = raw.substring(14).trim()
        val tz = if (tzPart.isEmpty()) TimeZone.getTimeZone("UTC") else TimeZone.getTimeZone("GMT$tzPart")
        return try {
            val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = tz }
            fmt.parse(datePart)?.time
        } catch (_: Throwable) { null }
    }
}
