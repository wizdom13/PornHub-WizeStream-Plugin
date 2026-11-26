/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import org.jsoup.nodes.Document
import org.schabi.newpipe.extractor.exceptions.ParsingException

/** Parses literal structured data only; deliberately does not evaluate website JavaScript. */
internal object PornHubPlayerData {
    private val assignment = Regex("\\b(?:var\\s+|let\\s+|const\\s+)?flashvars_\\d+\\s*=\\s*")

    fun player(document: Document): JsonObject {
        for (script in document.select("script")) {
            val source = script.data()
            for (match in assignment.findAll(source)) {
                val start = match.range.last + 1
                if (source.getOrNull(start) != '{') continue
                val literal = jsonObjectLiteral(source, start)
                val parsed = try {
                    JsonParser.any().from(literal) as? JsonObject
                } catch (cause: Exception) {
                    throw ParsingException("PornHub player data is not supported literal JSON", cause)
                }
                if (parsed?.get("mediaDefinitions") is JsonArray) return parsed
            }
        }
        throw ParsingException("PornHub did not expose supported player metadata; browser verification may be required")
    }

    private fun jsonObjectLiteral(source: String, start: Int): String {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until source.length) {
            val character = source[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> if (--depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        throw ParsingException("PornHub returned incomplete player metadata")
    }

    fun videoSchema(document: Document): JsonObject {
        fun find(value: Any?): JsonObject? = when (value) {
            is JsonObject -> {
                val type = value["@type"]
                if (type == "VideoObject" || (type is List<*> && type.contains("VideoObject"))) value
                else (value["@graph"] as? List<*>)?.firstNotNullOfOrNull { find(it) }
            }
            is List<*> -> value.firstNotNullOfOrNull { find(it) }
            else -> null
        }
        return document.select("script[type=application/ld+json]").firstNotNullOfOrNull {
            try { find(JsonParser.any().from(it.data())) } catch (_: Exception) { null }
        } ?: JsonObject()
    }

    fun count(value: String?): Long {
        val text = value.orEmpty().replace(",", "").replace("\u00a0", " ").trim()
        val match = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*([kmb])?(?:\\s+views?)?$", RegexOption.IGNORE_CASE)
            .matchEntire(text) ?: return -1
        val number = match.groupValues[1].toDoubleOrNull() ?: return -1
        val multiplier = when (match.groupValues[2].lowercase(Locale.ROOT)) {
            "k" -> 1_000
            "m" -> 1_000_000
            "b" -> 1_000_000_000
            else -> 1
        }
        return (number * multiplier).takeIf { it.isFinite() && it >= 0 && it < Long.MAX_VALUE.toDouble() }
            ?.toLong() ?: -1
    }

    fun seconds(value: Any?): Long? {
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
                ?: try { Duration.parse(value).seconds.toDouble() } catch (_: Exception) { null }
            else -> null
        }
        return number?.takeIf { it.isFinite() && it >= 0 && it < Long.MAX_VALUE / 1000.0 }?.toLong()
    }

    fun date(value: String?): OffsetDateTime? {
        if (value.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(value)
        } catch (_: Exception) {
            try { LocalDate.parse(value).atStartOfDay().atOffset(ZoneOffset.UTC) } catch (_: Exception) { null }
        }
    }

    fun hasDrm(value: JsonObject): Boolean = listOf(
        "drm", "drm_info", "drm_required", "drmRequired", "license_url", "licenseUrl"
    ).any { key ->
        when (val flag = value[key]) {
            null, false -> false
            is String -> flag.isNotBlank() && flag != "false" && flag != "0"
            is Map<*, *> -> flag.isNotEmpty()
            is List<*> -> flag.isNotEmpty()
            is Number -> flag.toDouble() != 0.0
            else -> true
        }
    }
}
