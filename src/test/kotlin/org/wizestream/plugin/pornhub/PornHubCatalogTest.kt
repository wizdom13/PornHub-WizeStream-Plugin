/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.stream.StreamInfoItem

class PornHubCatalogTest {
    private val service = PornHubService(43123)
    private val firstUrl = PornHubUrls.searchUrl("fixture query")
    private val secondUrl = PornHubUrls.searchUrl("fixture query", 2)
    private lateinit var downloader: CatalogDownloader

    @Before
    fun setUp() {
        downloader = CatalogDownloader(
            mapOf(firstUrl to resource("search-first.html"), secondUrl to resource("search-second.html"))
        )
        NewPipe.init(downloader)
    }

    @Test
    fun `search extracts main results and preserves native service identity`() {
        val extractor = service.getSearchExtractor(service.searchQHFactory.fromQuery("fixture query"))
        extractor.fetchPage()
        val page = extractor.initialPage
        assertEquals(2, page.items.size)
        val first = page.items[0] as StreamInfoItem
        assertEquals(43123, first.serviceId)
        assertEquals("Fixture café & video", first.name)
        assertEquals(PornHubUrls.streamUrl("phfixture001"), first.url)
        assertEquals(754L, first.duration)
        assertEquals(1_250_000L, first.viewCount)
        assertEquals("Fixture uploader", first.uploaderName)
        assertEquals("https://www.pornhub.com/model/fixture-uploader", first.uploaderUrl)
        assertEquals("https://cdn.example.com/fixture-one.jpg", first.thumbnailUrl)
        assertNotNull(first.uploadDate)
        assertEquals("2026-01-02T12:00Z", first.uploadDate!!.offsetDateTime().toString())
        assertEquals(3723L, (page.items[1] as StreamInfoItem).duration)
        assertEquals(1234L, (page.items[1] as StreamInfoItem).viewCount)
        assertEquals(secondUrl, page.nextPage.url)
        assertTrue(page.errors.isEmpty())
        assertEquals(listOf(firstUrl), downloader.requested)
    }

    @Test
    fun `search follows a valid continuation and respects the disabled last page`() {
        val extractor = service.getSearchExtractor(service.searchQHFactory.fromQuery("fixture query"))
        extractor.fetchPage()
        val first = extractor.initialPage
        val second = extractor.getPage(first.nextPage)
        assertEquals(1, second.items.size)
        assertEquals("Fixture video three", second.items.single().name)
        assertFalse(second.hasNextPage())
        assertEquals(listOf(firstUrl, secondUrl), downloader.requested)
    }

    @Test
    fun `unsafe continuation never reaches the downloader`() {
        val extractor = service.getSearchExtractor(service.searchQHFactory.fromQuery("fixture query"))
        val rejected = listOf(
            "https://unrelated.example.com/video/search?search=fixture+query&page=2",
            "http://www.pornhub.com/video/search?search=fixture+query&page=2",
            "https://www.pornhub.com/video?search=fixture+query&page=2",
            "https://www.pornhub.com/video/search?search=changed&page=2",
            "https://www.pornhub.com/video/search?search=fixture+query&page=1",
            "https://www.pornhub.com/video/search?search=fixture+query&page=2&page=3",
            "https://www.pornhub.com/video/search?search=fixture+query&page=-1",
            "https://www.pornhub.com/video/search?search=fixture+query&page=2&extra=fixture",
            "https://www.pornhub.com/video/search?search=fixture+query&page=2#fragment",
            "https://www.pornhub.com/video/search?search=fixture+query&page=2147483648"
        )
        rejected.forEach { candidate ->
            assertThrows(candidate, ParsingException::class.java) { extractor.getPage(Page(candidate)) }
        }
        assertTrue(downloader.requested.isEmpty())
    }

    @Test
    fun `HTML next links cannot loop or change the active query`() {
        for (next in listOf("?search=fixture+query&page=1", "?search=changed&page=2", "//other.example.com/video/search?search=fixture+query&page=2")) {
            val document = Jsoup.parse(
                "<ul id=videoSearchResult></ul><li class=page_next><a href='$next'>Next</a></li>",
                firstUrl
            )
            assertThrows(ParsingException::class.java) {
                PornHubCatalog.parse(document, firstUrl, service.serviceId, "videoSearchResult")
            }
        }
    }

    @Test
    fun `catalog accepts rel next and preserves encoded query identity`() {
        val query = "fixture & query + café"
        val initial = PornHubUrls.searchUrl(query)
        val continuation = PornHubUrls.searchUrl(query, 2)
        val document = Jsoup.parse(
            "<head><link rel=next href='${continuation.replace("&", "&amp;")}'></head><ul id=videoSearchResult></ul>",
            initial
        )
        assertEquals(continuation, PornHubCatalog.parse(document, initial, service.serviceId, "videoSearchResult").nextPage.url)
    }

    @Test
    fun `empty results are distinct from an unrecognized response`() {
        for (html in listOf("<ul id=videoSearchResult></ul>", "<div class=noResults>No matching fixtures</div>")) {
            val result = PornHubCatalog.parse(Jsoup.parse(html, firstUrl), firstUrl, service.serviceId, "videoSearchResult")
            assertTrue(result.items.isEmpty())
            assertFalse(result.hasNextPage())
        }
        assertThrows(ParsingException::class.java) {
            PornHubCatalog.parse(Jsoup.parse("<main>Unexpected response</main>", firstUrl), firstUrl, service.serviceId, "videoSearchResult")
        }
    }

    @Test
    fun `malformed item does not hide the remaining valid items`() {
        val html = """
            <ul id=videoSearchResult>
              <li class=videoblock><a class=linkVideoThumb href='/view_video.php?viewkey=phfixture001'></a></li>
              <li class=videoblock><a class=linkVideoThumb href='/view_video.php?viewkey=phfixture001' title='Recovered fixture'></a></li>
              <li class=videoblock><a class=linkVideoThumb href='/view_video.php?viewkey=phfixture002' title='Second fixture'></a></li>
            </ul>
        """.trimIndent()
        val result = PornHubCatalog.parse(Jsoup.parse(html, firstUrl), firstUrl, service.serviceId, "videoSearchResult")
        assertEquals(listOf("Recovered fixture", "Second fixture"), result.items.map { it.name })
        assertEquals(1, result.errors.size)
        assertEquals(-1L, result.items[0].duration)
        assertEquals(-1L, result.items[0].viewCount)
        assertNull(result.items[0].uploadDate)
    }

    @Test
    fun `all malformed video titles produce a useful parser error`() {
        val html = "<ul id=videoSearchResult><li class=videoblock><a class=linkVideoThumb href='/view_video.php?viewkey=phfixture001'></a></li></ul>"
        assertThrows(ParsingException::class.java) {
            PornHubCatalog.parse(Jsoup.parse(html, firstUrl), firstUrl, service.serviceId, "videoSearchResult")
        }
    }

    @Test
    fun `video kiosk uses its own result container and paginates`() {
        val listing = PornHubUrls.listingUrl()
        val next = PornHubUrls.listingUrl(2)
        downloader = CatalogDownloader(mapOf(
            listing to """
                <ul id=videoCategory><li class=videoblock><a class=linkVideoThumb href='/view_video.php?viewkey=phfixture001' title='Listing fixture'></a></li></ul>
                <li class=page_next><a href='$next'>Next</a></li>
            """.trimIndent(),
            next to "<ul id=videoCategory></ul>"
        ))
        NewPipe.init(downloader)
        val kiosks = service.kioskList
        assertEquals(setOf("Videos"), kiosks.availableKiosks)
        assertEquals("Videos", kiosks.defaultKioskId)
        val extractor = kiosks.defaultKioskExtractor
        extractor.fetchPage()
        assertEquals("Videos", extractor.name)
        val first = extractor.initialPage
        assertEquals("Listing fixture", first.items.single().name)
        val second = extractor.getPage(first.nextPage)
        assertTrue(second.items.isEmpty())
        assertFalse(second.hasNextPage())
        assertEquals(listOf(listing, next), downloader.requested)
    }

    @Test
    fun `service exposes only implemented capabilities`() {
        assertEquals(43123, service.serviceId)
        assertEquals("PornHub", service.serviceInfo.name)
        assertEquals(listOf(StreamingService.ServiceInfo.MediaCapability.VIDEO), service.serviceInfo.mediaCapabilities)
        assertNotNull(service.channelLHFactory)
        assertNotNull(service.channelTabLHFactory)
        assertNull(service.playlistLHFactory)
        assertNull(service.commentsLHFactory)
        assertNull(service.suggestionExtractor)
        assertNull(service.subscriptionExtractor)
        assertTrue(service.streamLHFactory.acceptUrl(PornHubUrls.streamUrl("phfixture001")))
        assertFalse(service.streamLHFactory.acceptUrl("https://unrelated.example.com/watch/fixture"))
        assertTrue(service.infoCacheExpirationMillis <= 300_000L)
    }

    @Test
    fun `card uploader links open supported canonical channels`() {
        val links = listOf(
            "/model/fixture-uploader" to "model/fixture-uploader",
            "/users/fixture_user/videos/public" to "users/fixture_user",
            "https://fr.pornhub.org/channels/fixture-studio/videos?o=mr" to "channels/fixture-studio",
            "//www.pornhub.com/pornstar/fixture-person" to "pornstar/fixture-person"
        )
        for ((href, id) in links) {
            val card = Jsoup.parse(
                """<li class=videoblock><span class=usernameWrap><a href='$href'>Fixture uploader</a></span></li>""",
                firstUrl
            ).selectFirst("li")!!
            val item = PornHubStreamItemExtractor(card)
            assertEquals(href, "https://www.pornhub.com/$id", item.getUploaderUrl())
            assertEquals("Fixture uploader", item.getUploaderName())
            assertEquals(StreamingService.LinkType.CHANNEL, service.getLinkTypeByUrl(item.getUploaderUrl()))
        }
    }

    @Test
    fun `card uploader names do not invent missing or unsupported links`() {
        for (href in listOf(null, "", "#", "javascript:void(0)", "https://unrelated.example.com/model/fixture", "/playlist/42")) {
            val attribute = href?.let { "href='$it'" }.orEmpty()
            val card = Jsoup.parse(
                "<li class=videoblock><span class=usernameWrap><a $attribute>Fixture uploader</a></span></li>",
                firstUrl
            ).selectFirst("li")!!
            val item = PornHubStreamItemExtractor(card)
            assertEquals(href.orEmpty(), "", item.getUploaderUrl())
            assertEquals("Fixture uploader", item.getUploaderName())
        }
    }

    @Test
    fun `query and fragment links on channel cards do not fabricate uploader navigation`() {
        val listing = "https://www.pornhub.com/model/fixture-channel/videos"
        for (href in listOf("#", "#profile", "?page=2", "?o=mr")) {
            val card = Jsoup.parse(
                "<li class=videoblock><span class=usernameWrap><a href='$href'>Fixture uploader</a></span></li>",
                listing
            ).selectFirst("li")!!
            assertEquals(href, "", PornHubStreamItemExtractor(card).getUploaderUrl())
        }
    }

    @Test
    fun `card metadata supports grouped counts and refuses invalid media`() {
        val values = listOf("1 234" to 1234L, "1,2K" to 1200L, "2.5B" to 2_500_000_000L, "unknown" to -1L)
        for ((views, expected) in values) {
            val card = Jsoup.parse("""
                <li class=videoblock><a class=linkVideoThumb href='/view_video.php?viewkey=phfixture001' title='Fixture'></a>
                <img src='file:///fixture.jpg'><span class=duration>12:99</span><span class=views><var>$views</var></span></li>
            """.trimIndent(), firstUrl).selectFirst("li")!!
            val item = PornHubStreamItemExtractor(card)
            assertEquals(views, expected, item.getViewCount())
            assertEquals(-1L, item.getDuration())
            assertTrue(item.getThumbnails().isEmpty())
        }
    }

    private fun resource(name: String): String = checkNotNull(javaClass.getResourceAsStream("/catalog/$name"))
        .bufferedReader().use { it.readText() }

    private class CatalogDownloader(private val pages: Map<String, String>) : Downloader() {
        val requested = mutableListOf<String>()

        override fun execute(request: Request): Response {
            requested += request.url()
            val html = pages[request.url()] ?: throw AssertionError("Unexpected fixture request: ${request.url()}")
            return Response(200, "OK", mapOf("Content-Type" to listOf("text/html; charset=utf-8")), html, html.toByteArray(), request.url())
        }

        override fun executeAsync(request: Request, callback: AsyncCallback): CancellableCall =
            throw AssertionError("Catalog extraction must not use asynchronous requests")
    }
}
