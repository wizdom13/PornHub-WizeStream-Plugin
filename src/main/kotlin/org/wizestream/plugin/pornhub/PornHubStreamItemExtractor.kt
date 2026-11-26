/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Locale
import org.jsoup.nodes.Element
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor
import org.schabi.newpipe.extractor.stream.StreamType

internal class PornHubStreamItemExtractor(private val card: Element) : StreamInfoItemExtractor {
    private val link: Element?
        get() = card.selectFirst("a.linkVideoThumb[href], .title a[href], a[href*=viewkey]")

    override fun getUrl(): String {
        val selected = link ?: throw ParsingException("PornHub video card is missing its link")
        return PornHubUrls.streamUrl(PornHubUrls.streamId(selected.absUrl("href")))
    }

    override fun getName(): String = sequenceOf(
        link?.attr("title"),
        card.selectFirst(".title a")?.attr("title"),
        card.selectFirst(".title")?.text(),
        card.selectFirst("img")?.attr("alt")
    ).filterNotNull().map(String::trim).firstOrNull(String::isNotEmpty)
        ?: throw ParsingException("PornHub video card is missing its title")

    override fun getStreamType(): StreamType = StreamType.VIDEO_STREAM

    override fun isAd(): Boolean = card.hasClass("ad") || card.hasClass("adContainer") ||
        card.hasClass("sponsored") || card.attr("data-is-ad") == "1" ||
        card.attr("data-ad") in setOf("true", "1") ||
        card.selectFirst(".ad-link, .adLabel, .sponsoredLabel") != null

    override fun getDuration(): Long {
        val raw = card.selectFirst(".duration")?.text()?.trim().orEmpty()
        if (!raw.matches(Regex("\\d+(?::\\d{2}){1,2}"))) return -1
        val parts = raw.split(':').map { it.toLongOrNull() ?: return -1 }
        if (parts.drop(1).any { it !in 0..59 }) return -1
        return try {
            parts.fold(0L) { total, value -> Math.addExact(Math.multiplyExact(total, 60L), value) }
        } catch (_: ArithmeticException) {
            -1
        }
    }

    override fun getViewCount(): Long {
        val raw = (card.selectFirst(".views var") ?: card.selectFirst(".views"))
            ?.text()?.trim().orEmpty()
        val match = Regex("^(\\d+(?:[.,]\\d+)*)(?:\\s*([kmb]))?", RegexOption.IGNORE_CASE)
            .find(raw.replace(Regex("[\\s\u00a0\u202f]+"), "")) ?: return -1
        val suffix = match.groupValues[2].lowercase(Locale.ROOT)
        val number = if (suffix.isEmpty()) {
            match.groupValues[1].replace(",", "").replace(".", "").toDoubleOrNull()
        } else {
            match.groupValues[1].replace(',', '.').toDoubleOrNull()
        } ?: return -1
        val multiplier = when (suffix) {
            "k" -> 1_000.0
            "m" -> 1_000_000.0
            "b" -> 1_000_000_000.0
            else -> 1.0
        }
        val count = number * multiplier
        return if (count.isFinite() && count >= 0 && count < Long.MAX_VALUE.toDouble()) count.toLong() else -1
    }

    override fun getUploaderName(): String = card.selectFirst(
        ".usernameWrap a, .usernameWrap, a.username, .username, .uploader, .author"
    )?.text()?.trim().orEmpty()

    // Channel browsing is not implemented. The host must not display a clickable dead end.
    override fun getUploaderUrl(): String = ""

    override fun getThumbnails(): List<Image> {
        val image = card.selectFirst("img") ?: return emptyList()
        val url = sequenceOf("data-mediumthumb", "data-thumb_url", "data-src", "data-original", "src")
            .mapNotNull { attribute -> PornHubUrls.mediaUrl(image.absUrl(attribute)) }
            .firstOrNull() ?: return emptyList()
        return listOf(Image(url, Image.HEIGHT_UNKNOWN, Image.WIDTH_UNKNOWN, Image.ResolutionLevel.UNKNOWN))
    }

    override fun getTextualUploadDate(): String? = sequenceOf(
        card.selectFirst("time[datetime]")?.attr("datetime"),
        card.selectFirst(".added, .addedDate")?.text()
    ).filterNotNull().map(String::trim).firstOrNull(String::isNotEmpty)

    override fun getUploadDate(): DateWrapper? {
        val value = card.selectFirst("time[datetime]")?.attr("datetime") ?: return null
        return try {
            DateWrapper(OffsetDateTime.parse(value))
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
