/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import com.grack.nanojson.JsonObject
import java.net.URI
import java.util.Locale
import org.jsoup.nodes.Document
import org.schabi.newpipe.extractor.exceptions.ParsingException

/** Public download links can provide MP4 renditions alongside an adaptive-only player. */
internal object PornHubDownloadSources {
    private val pathQuality = Regex("(?<![0-9])([1-9][0-9]{1,3})[pP]?_[0-9]+[kK](?![A-Za-z0-9])")

    fun definitions(page: Document, excludedUrls: Set<String> = emptySet()): List<JsonObject> {
        val seen = mutableSetOf<String>()
        return page.select("a.downloadBtn[href]").take(64).mapNotNull { link ->
            // jsoup's absUrl removes userinfo before validation. Resolve the original href
            // without that normalization so unsafe credentials and signed query bytes survive.
            val resolved = try {
                URI.create(link.baseUri()).resolve(link.attr("href").trim()).toString()
            } catch (_: IllegalArgumentException) {
                return@mapNotNull null
            }
            val source = PornHubUrls.mediaUrl(resolved) ?: return@mapNotNull null
            if (source in excludedUrls || !seen.add(source)) return@mapNotNull null
            val path = URI(source).path.orEmpty()
            val metadata = path == "/video/get_media" || path == "/video/get_media.php"
            if (metadata) {
                // Only the same public metadata endpoints used by mediaDefinitions are allowed.
                try {
                    PornHubUrls.siteUrl(source)
                } catch (_: ParsingException) {
                    return@mapNotNull null
                }
            } else if (!path.lowercase(Locale.ROOT).endsWith(".mp4")) {
                // A download-looking button is not evidence that a manifest/page is an MP4 file.
                return@mapNotNull null
            }
            JsonObject().apply {
                put("videoUrl", source)
                put("format", "mp4")
                if (!metadata) pathQuality.find(path)?.groupValues?.get(1)?.toIntOrNull()?.let {
                    put("quality", it)
                }
            }
        }
    }
}
