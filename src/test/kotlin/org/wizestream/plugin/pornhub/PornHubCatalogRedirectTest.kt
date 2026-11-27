/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.search.SearchInfo

class PornHubCatalogRedirectTest {
    private val service = PornHubService(43123)

    @Test
    fun `canonical search capitalization survives fresh extractor pagination`() {
        val requested = PornHubUrls.searchUrl("Lebanon")
        val canonical = "https://www.pornhub.com/video/search?search=lebanon"
        val continuation = "$canonical&page=2"
        val downloader = FixtureDownloader(mapOf(
            requested to Fixture(page("phcanonicalfirst", "?search=lebanon&page=2"), canonical),
            continuation to Fixture(page("phcanonicalsecond"))
        ))
        NewPipe.init(downloader)
        val handler = service.searchQHFactory.fromQuery("Lebanon")

        val first = SearchInfo.getInfo(service, handler)

        assertEquals(listOf(PornHubUrls.streamUrl("phcanonicalfirst")), first.relatedItems.map { it.url })
        assertTrue("Host errors: ${first.errors}", first.errors.isEmpty())
        assertEquals("Lebanon", first.searchString)
        assertEquals(continuation, first.nextPage.url)
        // The host constructs a fresh extractor with the original query for every next page.
        val second = SearchInfo.getMoreItems(service, handler, first.nextPage)
        assertEquals(listOf(PornHubUrls.streamUrl("phcanonicalsecond")), second.items.map { it.url })
        assertFalse(second.hasNextPage())
        assertEquals(listOf(requested, continuation), downloader.requested)
    }

    @Test
    fun `provider host aliases and a canonical slash preserve the listing`() {
        val requested = PornHubUrls.searchUrl("fixture")
        for (host in listOf("pornhub.com", "m.pornhub.com", "fr.pornhub.org", "en-gb.pornhub.com")) {
            val canonical = "https://$host/video/search/?search=fixture"
            val continuation = "$canonical&page=2"
            val downloader = FixtureDownloader(mapOf(
                requested to Fixture(page("phaliasfirst", "?search=fixture&page=2"), canonical),
                continuation to Fixture(page("phaliassecond"))
            ))
            NewPipe.init(downloader)
            val handler = service.searchQHFactory.fromQuery("fixture")
            val first = SearchInfo.getInfo(service, handler)
            assertEquals(host, 1, first.relatedItems.size)
            assertTrue("$host: ${first.errors}", first.errors.isEmpty())
            assertEquals(continuation, first.nextPage.url)
            val second = SearchInfo.getMoreItems(service, handler, first.nextPage)
            assertEquals(1, second.items.size)
            assertEquals(listOf(requested, continuation), downloader.requested)
        }
    }

    @Test
    fun `encoded search punctuation and Unicode survive capitalization changes`() {
        val requested = PornHubUrls.searchUrl("Fixture + CAFÉ & media")
        val canonical = PornHubUrls.searchUrl("fixture + café & media")
        val continuation = PornHubUrls.searchUrl("fixture + café & media", 2)
        val document = Jsoup.parse(page("phunicode", continuation), canonical)
        val result = PornHubCatalog.parse(document, requested, service.serviceId, "videoSearchResult")
        assertEquals(1, result.items.size)
        assertEquals(continuation, result.nextPage.url)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `redirects still cannot change search terms filters or requested page`() {
        val requested = PornHubUrls.searchUrl("Lebanon")
        val invalid = listOf(
            "https://www.pornhub.com/video/search?search=other&page=1",
            "https://www.pornhub.com/video/search?search=lebanon&o=mr&page=1",
            "https://www.pornhub.com/video/search?search=lebanon&page=2",
            "https://www.pornhub.com/video/search?search=lebanon&page=0",
            "https://www.pornhub.com/video/search?search=lebanon&page=1&page=2",
            "https://www.pornhub.com/video?search=lebanon&page=1",
            "https://www.pornhub.com/video/search?search=lebanon&page=1#fragment"
        )
        for (canonical in invalid) {
            assertThrows(canonical, ParsingException::class.java) {
                PornHubCatalog.parse(Jsoup.parse(page("phwronglisting"), canonical), requested, service.serviceId, "videoSearchResult")
            }
        }
    }

    @Test
    fun `continuations preserve filter values and do not case fold channel identities`() {
        val initial = "https://www.pornhub.com/video/search?search=Fixture&o=mr&page=1"
        val valid = "https://fr.pornhub.org/video/search?search=fixture&o=mr&page=2"
        assertEquals(valid, PornHubCatalog.validateContinuation(valid, initial))
        for (candidate in listOf(valid.replace("o=mr", "o=MR"), valid.replace("&o=mr", ""))) {
            assertThrows(ParsingException::class.java) { PornHubCatalog.validateContinuation(candidate, initial) }
        }
        assertThrows(ParsingException::class.java) {
            PornHubCatalog.validateChannelContinuation(
                "https://fr.pornhub.org/model/Fixture/videos?page=2",
                "https://www.pornhub.com/model/fixture/videos"
            )
        }
    }

    @Test
    fun `unsafe next URLs are rejected before any continuation request`() {
        val extractor = service.getSearchExtractor(service.searchQHFactory.fromQuery("Lebanon"))
        val downloader = FixtureDownloader(emptyMap())
        NewPipe.init(downloader)
        for (host in listOf("www.pornhub.com.other.example", "pornhub.example", "localhost")) {
            assertThrows(ParsingException::class.java) {
                extractor.getPage(Page("https://$host/video/search?search=lebanon&page=2"))
            }
        }
        assertThrows(ParsingException::class.java) {
            extractor.getPage(Page("http://fr.pornhub.org/video/search?search=lebanon&page=2"))
        }
        assertTrue(downloader.requested.isEmpty())
    }

    @Test
    fun `broken next links retain valid initial results and a visible error`() {
        val requested = PornHubUrls.searchUrl("fixture")
        for (next in listOf(
            "?search=fixture&page=1",
            "?search=other&page=2",
            "https://other.example/video/search?search=fixture&page=2"
        )) {
            val downloader = FixtureDownloader(mapOf(requested to Fixture(page("phretained", next))))
            NewPipe.init(downloader)
            val result = SearchInfo.getInfo(service, service.searchQHFactory.fromQuery("fixture"))
            assertEquals(listOf(PornHubUrls.streamUrl("phretained")), result.relatedItems.map { it.url })
            assertFalse(result.hasNextPage())
            assertEquals(1, result.errors.size)
            assertTrue(result.errors.single() is ParsingException)
            assertEquals(listOf(requested), downloader.requested)
        }
    }

    @Test
    fun `different listing query errors identify fields without echoing query values`() {
        val error = assertThrows(ParsingException::class.java) {
            PornHubCatalog.validateContinuation(
                PornHubUrls.searchUrl("other private query", 2),
                PornHubUrls.searchUrl("original private query")
            )
        }
        assertTrue(error.message.orEmpty().contains("parameters: search"))
        assertFalse(error.message.orEmpty().contains("private"))
    }

    private fun page(id: String, next: String? = null): String =
        "<ul id=videoSearchResult><li class=videoblock><a class=linkVideoThumb " +
            "href='/view_video.php?viewkey=$id' title='Fixture video'></a></li></ul>" +
            next?.let { "<li class=page_next><a href='${it.replace("&", "&amp;")}'>Next</a></li>" }.orEmpty()

    private data class Fixture(val html: String, val finalUrl: String? = null)

    private class FixtureDownloader(private val pages: Map<String, Fixture>) : Downloader() {
        val requested = mutableListOf<String>()

        override fun execute(request: Request): Response {
            requested += request.url()
            val fixture = pages[request.url()] ?: throw AssertionError("Unexpected fixture request: ${request.url()}")
            return Response(
                200, "OK", mapOf("Content-Type" to listOf("text/html; charset=utf-8")),
                fixture.html, fixture.html.toByteArray(), fixture.finalUrl ?: request.url()
            )
        }

        override fun executeAsync(request: Request, callback: AsyncCallback): CancellableCall =
            completedFixtureCall(callback) { execute(request) }
    }
}
