/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import java.io.IOException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

class PornHubStreamExtractorTest {
    private lateinit var network: FixtureDownloader
    private lateinit var service: PornHubService

    @Before
    fun setUp() {
        network = FixtureDownloader()
        NewPipe.init(network)
        service = PornHubService(1200)
    }

    @Test
    fun hostAggregationRetainsNativeIdentityAndHasNoExtractionErrors() {
        val info = StreamInfo.getInfo(service, WATCH)
        assertTrue("Host errors: " + info.errors, info.errors.isEmpty())
        assertEquals(1200, info.serviceId)
        assertEquals("phfixture123", info.id)
        assertEquals(WATCH, info.url)
        assertEquals("Neutral fixture café", info.name)
        assertEquals("Fixture uploader", info.uploaderName)
        assertEquals("https://www.pornhub.com/model/fixture-uploader", info.uploaderUrl)
        assertEquals(125L, info.duration)
        assertEquals(12345L, info.viewCount)
        assertEquals(18, info.ageLimit)
        assertEquals(StreamType.VIDEO_STREAM, info.streamType)
        assertEquals(4, info.videoStreams.size)
        assertEquals(1, info.subtitles.size)
    }

    @Test
    fun resolvesMetadataEndpointsAndKeepsPlaybackHeadersOnEverySource() {
        val stream = extract()
        assertEquals(4, stream.videoStreams.size)
        assertEquals(setOf(DeliveryMethod.HLS, DeliveryMethod.DASH, DeliveryMethod.PROGRESSIVE_HTTP),
            stream.videoStreams.map { it.deliveryMethod }.toSet())
        for (video in stream.videoStreams) {
            assertEquals(false, video.cacheable)
            assertEquals(MediaFormat.MPEG_4, video.format)
            assertFalse(video.isVideoOnly())
            assertEquals(WATCH, video.requestHeaders!!["Referer"])
            assertEquals(PornHubUrls.BASE_URL, video.requestHeaders!!["Origin"])
            assertFalse(video.content.contains("get_media"))
        }
        val mp4s = stream.videoStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        assertEquals(setOf("720p", "1080p"), mp4s.map { it.getResolution() }.toSet())
        assertEquals(VideoStream.RESOLUTION_UNKNOWN,
            stream.videoStreams.first { it.deliveryMethod == DeliveryMethod.HLS }.getResolution())
        assertEquals(setOf(WATCH, MEDIA), network.requests.map { it.url() }.toSet())
        assertEquals(stream.videoStreams.first().requestHeaders, stream.manifestRequestHeaders)
        assertTrue(stream.hlsUrl.endsWith("master.m3u8?token=a%2Fb"))
        assertTrue(stream.dashMpdUrl.endsWith("manifest.mpd"))
    }

    @Test
    fun metadataAndCapabilityFlagsAreAccurate() {
        val stream = extract()
        assertEquals(1200, stream.serviceId)
        assertEquals("https://cdn.example.com/fixture/poster.jpg", stream.thumbnailUrl)
        assertEquals("Synthetic fixture description", stream.description.content)
        assertEquals("2026-01-02T03:04:05Z", stream.textualUploadDate)
        assertEquals("2026-01-02T03:04:05Z", stream.uploadDate!!.offsetDateTime().toString())
        assertEquals(90L, stream.likeCount)
        assertEquals(2L, stream.dislikeCount)
        assertFalse(stream.isSupportComments)
        assertFalse(stream.isSupportRelatedItems)
        assertFalse(stream.isSupportBulletComments)
        assertTrue(stream.audioStreams.isEmpty())
        assertTrue(stream.videoOnlyStreams.isEmpty())
    }

    @Test
    fun uploaderLinksCanonicalizeSupportedProfileAndChannelPages() {
        network.uploaderHref = "https://de.pornhub.org/channels/fixture-studio/videos?o=mr"
        val info = StreamInfo.getInfo(service, WATCH)
        assertEquals("https://www.pornhub.com/channels/fixture-studio", info.uploaderUrl)
        assertTrue(service.channelLHFactory.acceptUrl(info.uploaderUrl))
        assertTrue("Host errors: " + info.errors, info.errors.isEmpty())
    }

    @Test
    fun uploaderUrlUsesSupportedStructuredAuthorWhenHtmlHasNoLink() {
        network.uploaderHref = null
        network.schema = """{"@type":"VideoObject","author":{"@type":"Person","name":"Fixture uploader","url":"/users/fixture_user/videos/public"}}"""
        assertEquals("https://www.pornhub.com/users/fixture_user", extract().uploaderUrl)
    }

    @Test
    fun uploaderNameAloneAndInvalidLinksRemainNonNavigable() {
        network.schema = """{"@type":"VideoObject","author":{"name":"Fixture uploader"}}"""
        for (href in listOf(null, "", "#", "javascript:void(0)", "https://unrelated.example.com/model/fixture", "/playlist/42")) {
            network.uploaderHref = href
            val stream = extract()
            assertEquals(href.orEmpty(), "", stream.uploaderUrl)
            assertEquals("Fixture uploader", stream.uploaderName)
        }
        network.uploaderHref = null
        network.schema = """{"@type":"VideoObject","author":{"name":"Fixture uploader","url":"https://www.pornhub.com.example.com/model/fixture"}}"""
        assertEquals("", extract().uploaderUrl)
    }

    @Test
    fun missingUploaderLinkNeverBorrowsARecommendedVideosUploader() {
        network.extraHtml = """
            <aside><div class=usernameWrap>
              <a href='/model/unrelated-uploader'>Unrelated uploader</a>
            </div></aside>
        """.trimIndent()
        network.schema = """{"@type":"VideoObject","author":{"name":"Fixture uploader"}}"""
        for (href in listOf(null, "", "#", "javascript:void(0)", "https://unrelated.example.com/model/fixture")) {
            network.uploaderHref = href
            val stream = extract()
            assertEquals("Fixture uploader", stream.uploaderName)
            assertEquals(href.orEmpty(), "", stream.uploaderUrl)
        }
        network.uploaderHref = null
        network.schema = """{"@type":"VideoObject","author":{"name":"Fixture uploader","url":"/users/fixture_user"}}"""
        assertEquals("https://www.pornhub.com/users/fixture_user", extract().uploaderUrl)
    }

    @Test
    fun balancedJsonParserKeepsEscapedQuotesBracesAndUnicode() {
        network.player["video_title"] = "Literal } brace, \"quoted\", café and \u2603"
        network.player["nested"] = obj("""{"text":"}; <tag>","data":{"value":"escaped \\ slash"}}""")
        network.title = ""
        assertEquals(network.player["video_title"], extract().name)
    }

    @Test
    fun neverExecutesJavascriptToObtainPlayerData() {
        network.overrideHtml = """<h1 class="title">Fixture</h1><script>
            var flashvars_42 = makePlayerData(); window.bad = true;
        </script>"""
        assertThrows(ParsingException::class.java) { extract() }
        network.overrideHtml = """<h1 class="title">Fixture</h1><script>
            var flashvars_42 = {"mediaDefinitions": [], "title": "unterminated };
        </script>"""
        assertThrows(ParsingException::class.java) { extract() }
    }

    @Test
    fun optionalMp4MetadataFailureKeepsAdvertisedAdaptiveSources() {
        network.metadataStatus = 503
        assertEquals(setOf(DeliveryMethod.HLS, DeliveryMethod.DASH),
            extract().videoStreams.map { it.deliveryMethod }.toSet())
        network.player["mediaDefinitions"] = array("""{"format":"mp4","videoUrl":"$MEDIA"}""")
        assertThrows(IOException::class.java) { extract() }
    }

    @Test
    fun repeatedMetadataEndpointsAreFetchedOnceAndDoNotRecurse() {
        network.player["mediaDefinitions"] = array(
            """{"format":"mp4","videoUrl":"$MEDIA"}""",
            """{"format":"mp4","videoUrl":"$MEDIA"}"""
        )
        network.metadata = """[
            {"videoUrl":"$MEDIA","quality":"720"},
            {"videoUrl":"https://cdn.example.com/fixture/720.mp4","quality":"720"},
            {"videoUrl":"https://cdn.example.com/fixture/720.mp4","quality":"720"}
        ]"""
        assertEquals(1, extract().videoStreams.size)
        assertEquals(1, network.requests.count { it.url() == MEDIA })
    }

    @Test
    fun foreignMetadataEndpointsCannotMakeNetworkRequests() {
        network.player["mediaDefinitions"] = array(
            """{"format":"mp4","videoUrl":"https://foreign.example.com/video/get_media?id=fixture"}"""
        )
        assertThrows(ParsingException::class.java) { extract() }
        assertEquals(listOf(WATCH), network.requests.map { it.url() })
    }

    @Test
    fun unsupportedEmptyUnsafeAndDrmSourcesAreNotInventedAsMp4() {
        network.player["mediaDefinitions"] = array(
            """{"videoUrl":"https://cdn.example.com/fixture/unknown","format":"proprietary"}""",
            """{"videoUrl":"http://cdn.example.com/fixture/video.mp4","format":"mp4"}""",
            """{"videoUrl":"file:///sdcard/fixture.mp4","format":"mp4"}""",
            """{"videoUrl":"https://localhost/fixture.mp4","format":"mp4"}""",
            """{"videoUrl":"https://secret@cdn.example.com/fixture/video.mp4","format":"mp4"}""",
            """{"videoUrl":"https://cdn.example.com/fixture/video.mp4","format":"mp4","drm":true}"""
        )
        assertThrows(ContentNotAvailableException::class.java) { extract() }
        network.player["mediaDefinitions"] = JsonArray()
        assertThrows(ContentNotAvailableException::class.java) { extract() }
    }

    @Test
    fun topLevelDrmIsReportedBeforeReturningSources() {
        network.player["drm"] = obj("""{"widevine":true}""")
        assertThrows(ContentNotSupportedException::class.java) { extract() }
        network.player["drm"] = 0.5
        assertThrows(ContentNotSupportedException::class.java) { extract() }
    }

    @Test
    fun redirectsAndCanonicalUrlsCannotSwitchVideoIdentity() {
        network.latestUrl = PornHubUrls.streamUrl("phother")
        assertThrows(ContentNotAvailableException::class.java) { extract() }
        network.latestUrl = WATCH
        network.canonical = PornHubUrls.streamUrl("phother")
        assertThrows(ParsingException::class.java) { extract() }
    }

    @Test
    fun missingTitleFailsInsteadOfReturningAnUnidentifiedStream() {
        network.title = ""
        network.player.remove("video_title")
        assertThrows(ParsingException::class.java) { extract() }
    }

    @Test
    fun captionsPreserveFormatLanguageAndRequestHeaders() {
        val subtitle = extract().subtitlesDefault.single()
        assertEquals(MediaFormat.SRT, subtitle.format)
        assertEquals("en", subtitle.languageTag)
        assertEquals(false, subtitle.cacheable)
        assertEquals(WATCH, subtitle.requestHeaders!!["Referer"])
        assertTrue(extract().getSubtitles(MediaFormat.VTT).isEmpty())
        network.player["closedCaptionsFile"] = "https://cdn.example.com/fixture/captions.vtt"
        network.player["closedCaptionsLanguage"] = "fr"
        assertEquals("fr", extract().getSubtitles(MediaFormat.VTT).single().languageTag)
        network.player["closedCaptionsFile"] = "https://cdn.example.com/fixture/captions?token=fixture"
        assertEquals(MediaFormat.SRT, extract().subtitlesDefault.single().format)
        network.player["closedCaptionsFile"] = "javascript:fixture()"
        assertTrue(extract().subtitlesDefault.isEmpty())
    }

    @Test
    fun failedRefreshClearsAllPreviousPlaybackSources() {
        val stream = extract()
        assertFalse(stream.videoStreams.isEmpty())
        network.watchStatus = 404
        assertThrows(ContentNotAvailableException::class.java) { stream.onFetchPage(network) }
        assertTrue(stream.videoStreams.isEmpty())
        assertTrue(stream.subtitlesDefault.isEmpty())
    }

    @Test
    fun optionalMetadataStaysUnknownWhenAbsent() {
        network.showStats = false
        network.player.remove("video_duration")
        network.player.remove("image_url")
        network.schema = """{"@type":"VideoObject"}"""
        val stream = extract()
        assertEquals(-1L, stream.length)
        assertEquals(-1L, stream.viewCount)
        assertEquals("", stream.thumbnailUrl)
        assertNull(stream.uploadDate)
    }

    @Test
    fun timestampsAreBoundedAndAmbiguousValuesAreIgnored() {
        assertEquals(62L, extract(WATCH + "&t=62").timeStamp)
        assertEquals(9L, extract(WATCH + "#t=9").timeStamp)
        for (suffix in listOf("&t=-1", "&t=9999999999999999999", "&t=1&start=2", "&t=bad")) {
            assertEquals(0L, extract(WATCH + suffix).timeStamp)
        }
    }

    @Test
    fun durationAndDateFallbacksUseStructuredMetadata() {
        network.player.remove("video_duration")
        network.schema = """{"@graph":[{"@type":"WebPage"},{"@type":"VideoObject","duration":"PT2M5S","uploadDate":"2026-01-02"}]}"""
        val stream = extract()
        assertEquals(125L, stream.length)
        assertEquals("2026-01-02T00:00Z", stream.uploadDate!!.offsetDateTime().toString())
    }

    private fun extract(url: String = WATCH): PornHubStreamExtractor =
        (service.getStreamExtractor(url) as PornHubStreamExtractor).apply { fetchPage() }

    private class FixtureDownloader : Downloader() {
        val requests = mutableListOf<Request>()
        var watchStatus = 200
        var metadataStatus = 200
        var latestUrl = WATCH
        var canonical = WATCH
        var title = "Neutral fixture café"
        var uploaderHref: String? = "/model/fixture-uploader"
        var extraHtml = ""
        var overrideHtml: String? = null
        var showStats = true
        var schema = """{"@type":"VideoObject","description":"Synthetic fixture description","uploadDate":"2026-01-02T03:04:05Z"}"""
        val player = obj("""{
            "video_title":"Fallback fixture",
            "video_duration":"125",
            "image_url":"https://cdn.example.com/fixture/poster.jpg",
            "closedCaptionsFile":"https://cdn.example.com/fixture/captions.srt",
            "mediaDefinitions":[
                {"format":"hls","quality":[240,720,1080],"videoUrl":"https://cdn.example.com/fixture/master.m3u8?token=a%2Fb"},
                {"format":"mp4","videoUrl":"$MEDIA"},
                {"format":"dash","videoUrl":"https://cdn.example.com/fixture/manifest.mpd"},
                {"format":"hls","videoUrl":"https://cdn.example.com/fixture/master.m3u8?token=a%2Fb"}
            ]
        }""")
        var metadata = """[
            {"videoUrl":"https://cdn.example.com/fixture/720.mp4","quality":"720"},
            {"videoUrl":"https://cdn.example.com/fixture/1080.mp4","quality":1080}
        ]"""

        override fun execute(request: Request): Response {
            requests += request
            val (body, status, finalUrl) = when (request.url()) {
                WATCH -> Triple(overrideHtml ?: html(), watchStatus, latestUrl)
                MEDIA -> Triple(metadata, metadataStatus, MEDIA)
                else -> throw IOException("Unexpected synthetic fixture request: " + request.url())
            }
            return Response(status, "fixture", emptyMap(), body, body.toByteArray(), finalUrl)
        }

        override fun executeAsync(request: Request, callback: AsyncCallback): CancellableCall =
            throw UnsupportedOperationException("Synthetic fixture downloader is synchronous")

        private fun html(): String = """
            <!doctype html><html><head>
            <meta name="twitter:title" content="$title">
            <link rel="canonical" href="$canonical">
            <script type="application/ld+json">$schema</script>
            </head><body>
            <div class="userInfoBlock"><div class="usernameWrap"><a ${uploaderHref?.let { "href='$it'" }.orEmpty()}>Fixture uploader</a></div></div>
            $extraHtml
        """ + (if (showStats) """<div class="views"><span class="count">12,345</span></div>
            <span class="votesUp">90</span><span class="votesDown">2</span>""" else "") +
            "<script>var flashvars_42 = " + JsonWriter.string(player) + ";</script></body></html>"
    }

    companion object {
        private const val WATCH = "https://www.pornhub.com/view_video.php?viewkey=phfixture123"
        private const val MEDIA = "https://www.pornhub.com/video/get_media?s=fixture"
        private fun obj(value: String): JsonObject = JsonParser.any().from(value) as JsonObject
        private fun array(vararg values: String): JsonArray = JsonArray().apply { values.forEach { add(obj(it)) } }
    }
}
