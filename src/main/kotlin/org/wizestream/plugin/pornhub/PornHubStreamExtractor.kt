/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import com.grack.nanojson.JsonObject
import java.io.IOException
import java.net.URI
import java.util.Locale
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.LinkHandler
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream

class PornHubStreamExtractor(service: PornHubService, linkHandler: LinkHandler) :
    StreamExtractor(service, linkHandler) {
    private val watchUrl = PornHubUrls.streamUrl(linkHandler.id)
    private var page = Document.createShell(watchUrl)
    private var player = JsonObject()
    private var schema = JsonObject()
    private var videos = emptyList<VideoStream>()
    private var captions = emptyList<SubtitlesStream>()
    private val playbackHeaders = mapOf("Referer" to watchUrl, "Origin" to PornHubUrls.BASE_URL)

    override fun onFetchPage(downloader: Downloader) {
        // A failed refresh must never expose an old signed playback URL.
        page = Document.createShell(watchUrl)
        player = JsonObject()
        schema = JsonObject()
        videos = emptyList()
        captions = emptyList()
        val client = PornHubClient(downloader)
        page = client.getPage(watchUrl)
        val returnedId = try {
            PornHubUrls.streamId(page.location())
        } catch (cause: ParsingException) {
            throw ContentNotAvailableException("PornHub redirected away from the requested video", cause)
        }
        if (returnedId != id) throw ContentNotAvailableException("PornHub redirected to a different video")
        page.selectFirst("link[rel=canonical]")?.absUrl("href")?.takeIf(String::isNotEmpty)?.let {
            if (PornHubUrls.streamId(it) != id) throw ParsingException("PornHub returned metadata for a different video")
        }
        player = PornHubPlayerData.player(page)
        schema = PornHubPlayerData.videoSchema(page)
        if (PornHubPlayerData.hasDrm(player)) throw ContentNotSupportedException("DRM playback is not supported")
        getName()
        videos = parseVideos(client)
        if (videos.isEmpty()) throw ContentNotAvailableException("PornHub did not provide a supported public video source")
        captions = parseCaptions()
    }

    private fun parseVideos(client: PornHubClient): List<VideoStream> {
        val result = mutableListOf<VideoStream>()
        val seen = mutableSetOf<String>()
        val visitedMetadata = mutableSetOf<String>()
        val playerDefinitions = (player["mediaDefinitions"] as? List<*>).orEmpty().filterIsInstance<JsonObject>()
        val drmSources = playerDefinitions.filter(PornHubPlayerData::hasDrm)
            .mapNotNull { PornHubUrls.mediaUrl(it.getString("videoUrl")) }.toMutableSet()
        // Public download controls can expose MP4 renditions even when the player is HLS-only.
        // Each source has its own bounded budget; a full player list must not hide downloads.
        val definitions = playerDefinitions.take(64) + PornHubDownloadSources.definitions(page, drmSources)
        var metadataFailure: Exception? = null

        fun addSource(definition: JsonObject) {
            if (PornHubPlayerData.hasDrm(definition)) return
            val source = PornHubUrls.mediaUrl(definition.getString("videoUrl")) ?: return
            if (source in drmSources) return
            val path = URI(source).path.orEmpty().lowercase(Locale.ROOT)
            // Metadata endpoints cannot be handed to the player even when format says "mp4".
            if (path == "/video/get_media" || path == "/video/get_media.php") return
            val format = definition.getString("format", "").lowercase(Locale.ROOT)
            val delivery = when {
                path.endsWith(".m3u8") || format == "hls" -> DeliveryMethod.HLS
                path.endsWith(".mpd") || format == "dash" -> DeliveryMethod.DASH
                path.endsWith(".mp4") || format == "mp4" -> DeliveryMethod.PROGRESSIVE_HTTP
                else -> return
            }
            if (!seen.add(source)) return
            val quality = when (val value = definition["quality"]) {
                is Number -> value.toInt().takeIf { it > 0 }?.toString()
                is String -> value.removeSuffix("p").toIntOrNull()?.takeIf { it > 0 }?.toString()
                // A master playlist's array of qualities is adaptive, not a scalar resolution.
                else -> null
            }
            result += VideoStream.Builder()
                .setId("pornhub-" + delivery.name.lowercase(Locale.ROOT) + "-" + result.size)
                .setContent(source, true)
                .setMediaFormat(MediaFormat.MPEG_4)
                .setDeliveryMethod(delivery)
                .setManifestUrl(if (delivery == DeliveryMethod.PROGRESSIVE_HTTP) null else source)
                .setIsVideoOnly(false)
                .setResolution(quality?.let { it + "p" } ?: VideoStream.RESOLUTION_UNKNOWN)
                .setRequestHeaders(playbackHeaders)
                .setCacheable(false)
                .build()
        }

        for (definition in definitions) {
            if (PornHubPlayerData.hasDrm(definition)) continue
            val source = PornHubUrls.mediaUrl(definition.getString("videoUrl")) ?: continue
            if (source in drmSources) continue
            val path = URI(source).path.orEmpty()
            if (path == "/video/get_media" || path == "/video/get_media.php") {
                // Resolve at most eight distinct metadata requests; never recurse through them.
                if (visitedMetadata.size >= 8 || !visitedMetadata.add(source)) continue
                try {
                    val metadata = client.getJsonArray(source, watchUrl).filterIsInstance<JsonObject>()
                    metadata.filter(PornHubPlayerData::hasDrm)
                        .mapNotNullTo(drmSources) { PornHubUrls.mediaUrl(it.getString("videoUrl")) }
                    metadata.take(64).forEach {
                        // The provider's get_media response is the MP4 rendition list.
                        val entry = JsonObject().apply {
                            putAll(it)
                            if (!containsKey("format")) put("format", definition.getString("format", "mp4"))
                        }
                        addSource(entry)
                    }
                } catch (cause: IOException) {
                    metadataFailure = cause
                } catch (cause: ExtractionException) {
                    metadataFailure = cause
                }
            } else {
                addSource(definition)
            }
        }
        // A later metadata response can mark an earlier candidate as protected. A download
        // button must never reintroduce that source, regardless of definition order.
        val supported = result.filterNot { it.content in drmSources }
        // Failure of an optional MP4 metadata request must not discard an available HLS source.
        if (supported.isEmpty() && metadataFailure != null) throw metadataFailure
        return supported
    }

    private fun parseCaptions(): List<SubtitlesStream> {
        val url = PornHubUrls.mediaUrl(player.getString("closedCaptionsFile")) ?: return emptyList()
        val format = when (URI(url).path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "", "srt" -> MediaFormat.SRT
            "vtt" -> MediaFormat.VTT
            else -> return emptyList()
        }
        // The service's scalar closedCaptionsFile field represents its English subtitle track.
        val language = player.getString("closedCaptionsLanguage", "en").replace('_', '-')
        if (!Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*").matches(language)) return emptyList()
        return listOf(SubtitlesStream.Builder()
            .setId("pornhub-captions")
            .setContent(url, true)
            .setMediaFormat(format)
            .setLanguageCode(language)
            .setAutoGenerated(false)
            .setRequestHeaders(playbackHeaders)
            .setCacheable(false)
            .build())
    }

    override fun getName(): String = meta("twitter:title")
        .ifBlank { page.selectFirst("h1.title")?.text().orEmpty() }
        .ifBlank { meta("og:title") }
        .ifBlank { player.getString("video_title", "") }
        .ifBlank { schema.getString("name", "") }
        .takeIf(String::isNotBlank) ?: throw ParsingException("PornHub did not provide a video title")

    override fun getThumbnailUrl(): String = sequenceOf(
        player.getString("image_url"), meta("og:image"), schema["thumbnailUrl"] as? String,
        (schema["thumbnailUrl"] as? List<*>)?.firstOrNull() as? String
    ).firstNotNullOfOrNull(PornHubUrls::mediaUrl).orEmpty()

    override fun getDescription(): Description = Description(
        schema.getString("description", "").ifBlank { meta("og:description") },
        Description.PLAIN_TEXT
    )

    override fun getLength(): Long = PornHubPlayerData.seconds(player["video_duration"])
        ?: PornHubPlayerData.seconds(meta("video:duration"))
        ?: PornHubPlayerData.seconds(schema["duration"]) ?: -1L

    override fun getViewCount(): Long = PornHubPlayerData.count(
        page.selectFirst(".views .count, .viewsCount, span.count")?.text()
    )

    override fun getLikeCount(): Long = PornHubPlayerData.count(page.selectFirst(".votesUp")?.text())
    override fun getDislikeCount(): Long = PornHubPlayerData.count(page.selectFirst(".votesDown")?.text())
    override fun getUploaderName(): String = uploaderLink()
        ?.text().orEmpty().ifBlank {
            when (val author = schema["author"]) {
                is JsonObject -> author.getString("name", "")
                is String -> author
                else -> ""
            }
        }
    override fun getUploaderUrl(): String = PornHubUrls.channelUrlOrNull(uploaderLink()?.attr("href"), page.baseUri())
        ?: PornHubUrls.channelUrlOrNull((schema["author"] as? JsonObject)?.getString("url"), page.baseUri())
            .orEmpty()
    override fun getTextualUploadDate(): String? = schema.getString("uploadDate")?.takeIf(String::isNotBlank)
    override fun getUploadDate(): DateWrapper? = PornHubPlayerData.date(textualUploadDate)?.let(::DateWrapper)
    override fun getAgeLimit(): Int = 18
    override fun getHost(): String = "pornhub.com"
    override fun getStreamType(): StreamType = StreamType.VIDEO_STREAM
    override fun getVideoStreams(): List<VideoStream> = videos
    override fun getVideoOnlyStreams(): List<VideoStream> = emptyList()
    override fun getAudioStreams(): List<AudioStream> = emptyList()
    override fun getHlsUrl(): String = videos.firstOrNull { it.deliveryMethod == DeliveryMethod.HLS }?.content.orEmpty()
    override fun getDashMpdUrl(): String = videos.firstOrNull { it.deliveryMethod == DeliveryMethod.DASH }?.content.orEmpty()
    override fun getManifestRequestHeaders(): Map<String, String> = playbackHeaders
    override fun getSubtitlesDefault(): List<SubtitlesStream> = captions
    override fun getSubtitles(format: MediaFormat): List<SubtitlesStream> = captions.filter { it.format == format }
    override fun getRelatedItems(): StreamInfoItemsCollector = PornHubRelatedItems.parse(page, serviceId, id)
    override fun isSupportComments(): Boolean = false
    override fun isSupportRelatedItems(): Boolean = true
    override fun isSupportBulletComments(): Boolean = false

    override fun getTimeStamp(): Long {
        val values = try {
            val uri = URI(originalUrl)
            (PornHubUrls.parameters(uri) + PornHubUrls.parameters(URI("https://example.com/?" + uri.rawFragment.orEmpty())))
                .filter { it.first == "t" || it.first == "start" }.map { it.second }
        } catch (_: Exception) { return 0 }
        return values.singleOrNull()?.toLongOrNull()?.takeIf { it in 0L..(Long.MAX_VALUE / 1000L) } ?: 0L
    }

    private fun meta(key: String): String = page.selectFirst("meta[property=\"$key\"], meta[name=\"$key\"]")
        ?.attr("content").orEmpty()

    private fun uploaderLink(): Element? = (page.selectFirst(".userInfoBlock .usernameWrap")
        ?: page.selectFirst(".usernameWrap"))?.selectFirst("a")
}
