/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonWriter
import java.io.IOException
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo

class PornHubDownloadSourcesTest {
    private lateinit var network: DownloadFixture
    private lateinit var service: PornHubService

    @Before
    fun setUp() {
        network = DownloadFixture()
        NewPipe.init(network)
        service = PornHubService(1200)
    }

    @Test
    fun publicMp4LinksSupplementAdaptivePlaybackForHostDownloads() {
        network.links = """<a class="button downloadBtn" href="$MP4">Download fixture</a>"""
        val info = StreamInfo.getInfo(service, WATCH)
        assertTrue("Host errors: " + info.errors, info.errors.isEmpty())
        assertEquals(setOf(DeliveryMethod.HLS, DeliveryMethod.PROGRESSIVE_HTTP),
            info.videoStreams.map { it.deliveryMethod }.toSet())
        val download = info.videoStreams.single { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        assertTrue(download.isUrl)
        assertFalse(download.isVideoOnly())
        assertEquals("720p", download.getResolution())
        assertEquals(MP4, download.content)
        assertEquals(WATCH, download.requestHeaders!!["Referer"])
        assertEquals(PornHubUrls.BASE_URL, download.requestHeaders!!["Origin"])
        assertEquals(false, download.cacheable)
        assertEquals(listOf(WATCH), network.requests)
    }

    @Test
    fun downloadLinksPreserveSignedQueriesAndDeduplicateAcrossPlayerAndPage() {
        val signed = "https://cdn.example.com/fixture/1080P_3000K_video.MP4?token=a%2Fb&expires=123"
        network.definitions.add(source(signed, "mp4"))
        network.links = """
            <a class="downloadBtn" href="${signed.replace("&", "&amp;")}">Download fixture</a>
            <a class="downloadBtn" href="${signed.replace("&", "&amp;")}#duplicate">Download fixture</a>
            <a class="downloadBtn" href="${signed.removePrefix("https:").replace("&", "&amp;")}">Relative duplicate</a>
        """.trimIndent()
        val downloads = extract().videoStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        assertEquals(listOf(signed), downloads.map { it.content })
        val parsed = PornHubDownloadSources.definitions(Jsoup.parse(network.links, WATCH)).single()
        assertEquals(signed, parsed.getString("videoUrl"))
        assertEquals(1080, parsed.getInt("quality"))
    }

    @Test
    fun arbitraryLinksUnsafeUrlsAndManifestButtonsAreNotInventedAsDownloads() {
        val links = listOf(
            "https://cdn.example.com/fixture/master.m3u8",
            "https://cdn.example.com/fixture/manifest.mpd",
            "https://cdn.example.com/fixture/unknown?format=mp4",
            "https://cdn.example.com/fixture/page.html",
            "http://cdn.example.com/fixture/video.mp4",
            "https://127.0.0.1/fixture/video.mp4",
            "https://user:pass@cdn.example.com/fixture/video.mp4",
            "https://foreign.example.com/video/get_media?s=fixture",
            "javascript:downloadFixture()",
            "file:///sdcard/fixture.mp4",
            "/login",
            "#download"
        )
        network.links = links.joinToString("") { """<a class="downloadBtn" href="$it">Download 720p</a>""" } +
            """<aside class="ad"><a href="$MP4">Unrelated fixture</a></aside>""" +
            """<a class="downloadBtnOther" href="$MP4">Unrelated fixture</a>"""
        val candidates = PornHubDownloadSources.definitions(Jsoup.parse(network.links, WATCH))
        assertTrue("Unexpected fixture sources: $candidates", candidates.isEmpty())
        assertEquals(listOf(DeliveryMethod.HLS), extract().videoStreams.map { it.deliveryMethod })
        assertEquals(listOf(WATCH), network.requests)
    }

    @Test
    fun advertisedMetadataLinksUseTheExistingBoundedDownloader() {
        network.links = """
            <a class="downloadBtn" href="/video/get_media?s=fixture">Download fixture</a>
            <a class="downloadBtn" href="$MEDIA">Duplicate fixture</a>
        """.trimIndent()
        val downloads = extract().videoStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        assertEquals(listOf(MP4), downloads.map { it.content })
        assertEquals(listOf("720p"), downloads.map { it.getResolution() })
        assertEquals(listOf(WATCH, MEDIA), network.requests)
    }

    @Test
    fun metadataFailureDoesNotDiscardAvailableAdaptivePlayback() {
        network.links = """<a class="downloadBtn" href="$MEDIA">Download fixture</a>"""
        network.metadataStatus = 503
        assertEquals(listOf(DeliveryMethod.HLS), extract().videoStreams.map { it.deliveryMethod })
        network.definitions.clear()
        assertThrows(IOException::class.java) { extract() }
    }

    @Test
    fun pagesWithoutPublicDownloadLinksKeepAdaptiveSourcesWithoutGuessingMp4Urls() {
        assertTrue(PornHubDownloadSources.definitions(Jsoup.parse("<div>Fixture</div>", WATCH)).isEmpty())
        assertEquals(listOf(HLS), extract().videoStreams.map { it.content })
        assertEquals(listOf(WATCH), network.requests)
    }

    @Test
    fun playerDefinitionBudgetDoesNotSuppressPublicDownloadLinks() {
        network.definitions.clear()
        repeat(64) { network.definitions.add(source("https://cdn.example.com/fixture/master-$it.m3u8", "hls")) }
        network.links = (0 until 80).joinToString("") {
            """<a class="downloadBtn" href="https://cdn.example.com/fixture/video-$it.mp4">Fixture download</a>"""
        }
        val streams = extract().videoStreams
        assertEquals(64, streams.count { it.deliveryMethod == DeliveryMethod.HLS })
        assertEquals(64, streams.count { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP })
        assertEquals(listOf(WATCH), network.requests)
    }

    @Test
    fun downloadLinksCannotReintroduceSourcesMarkedAsDrm() {
        network.definitions.add(source(MP4, "mp4").apply { put("drm", true) })
        network.links = """<a class="downloadBtn" href="$MP4">Download fixture</a>"""
        assertTrue(PornHubDownloadSources.definitions(Jsoup.parse(network.links, WATCH), setOf(MP4)).isEmpty())
        assertEquals(listOf(HLS), extract().videoStreams.map { it.content })
    }

    @Test
    fun resolvedDrmMetadataExcludesEarlierSourcesAndLaterDownloadLinks() {
        for (advertisedEarlier in listOf(false, true)) {
            network.requests.clear()
            network.definitions.clear()
            network.definitions.add(source(HLS, "hls"))
            if (advertisedEarlier) network.definitions.add(source(MP4, "mp4"))
            network.definitions.add(source(MEDIA, "mp4"))
            network.links = """<a class="downloadBtn" href="$MP4">Download fixture</a>"""
            network.metadata = """[{"videoUrl":"$MP4","quality":"720","drm":true}]"""
            assertEquals(listOf(HLS), extract().videoStreams.map { it.content })
            assertEquals(listOf(WATCH, MEDIA), network.requests)
        }
    }

    @Test
    fun duplicateMetadataAndDownloadLinksCannotReintroduceADrmEndpoint() {
        network.definitions.add(source(MEDIA, "mp4").apply { put("drm", true) })
        network.definitions.add(source(MEDIA, "mp4"))
        network.links = """<a class="downloadBtn" href="$MEDIA">Download fixture</a>"""
        assertEquals(listOf(HLS), extract().videoStreams.map { it.content })
        assertEquals(listOf(WATCH), network.requests)
    }

    private fun extract(): PornHubStreamExtractor =
        (service.getStreamExtractor(WATCH) as PornHubStreamExtractor).apply { fetchPage() }

    private class DownloadFixture : Downloader() {
        val requests = mutableListOf<String>()
        val definitions = JsonArray().apply { add(source(HLS, "hls")) }
        var links = ""
        var metadataStatus = 200
        var metadata = """[{"videoUrl":"$MP4","quality":"720"}]"""

        override fun execute(request: Request): Response {
            requests += request.url()
            val (status, body) = when (request.url()) {
                WATCH -> 200 to """
                    <!doctype html><html><head><meta name="twitter:title" content="Neutral download fixture"></head>
                    <body>$links<script>var flashvars_1 = ${JsonWriter.string(JsonObject().apply {
                        put("mediaDefinitions", definitions)
                        put("video_duration", 60)
                    })};</script></body></html>
                """.trimIndent()
                MEDIA -> metadataStatus to metadata
                else -> throw IOException("Unexpected neutral fixture request: " + request.url())
            }
            return Response(status, "fixture", emptyMap(), body, body.toByteArray(), request.url())
        }

        override fun executeAsync(request: Request, callback: AsyncCallback): CancellableCall =
            completedFixtureCall(callback) { execute(request) }
    }

    companion object {
        private const val WATCH = "https://www.pornhub.com/view_video.php?viewkey=phdownloadfixture"
        private const val HLS = "https://cdn.example.com/fixture/master.m3u8"
        private const val MP4 = "https://cdn.example.com/fixture/720P_1500K_video.mp4"
        private const val MEDIA = "https://www.pornhub.com/video/get_media?s=fixture"

        private fun source(url: String, format: String) = JsonObject().apply {
            put("videoUrl", url)
            put("format", format)
        }
    }
}
