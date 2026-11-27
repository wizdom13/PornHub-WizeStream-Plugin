/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.channel.ChannelExtractor
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelTabInfo
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ChannelTabs
import org.schabi.newpipe.extractor.stream.StreamInfoItem

class PornHubChannelExtractorTest {
    private val service = PornHubService(43123)
    private lateinit var network: ChannelDownloader

    @Before
    fun setUp() {
        network = ChannelDownloader()
        NewPipe.init(network)
    }

    @Test
    fun `host channel aggregation exposes metadata and a navigable Videos tab`() {
        network.pages[PROFILE] = Fixture(profileHtml())

        val info = ChannelInfo.getInfo(service, PROFILE)

        assertEquals(43123, info.serviceId)
        assertEquals(ID, info.id)
        assertEquals(PROFILE, info.url)
        assertEquals("Fixture channel", info.name)
        assertEquals("https://cdn.example.com/fixture-avatar.jpg", info.avatarUrl)
        assertEquals("A neutral channel description.", info.description)
        assertEquals(ChannelExtractor.UNKNOWN_SUBSCRIBER_COUNT, info.subscriberCount)
        assertTrue("Host errors: ${info.errors}", info.errors.isEmpty())
        assertTrue(info.relatedItems.isEmpty())
        val tab = info.tabs.single()
        assertEquals(ID, tab.id)
        assertEquals(VIDEOS, tab.url)
        assertEquals(ChannelTabs.VIDEOS, tab.contentFilters.first().name)
        assertEquals(listOf(PROFILE), network.requested)
    }

    @Test
    fun `missing optional metadata stays unknown without breaking channel aggregation`() {
        network.pages[PROFILE] = Fixture("<div id=topProfileHeader><h1 itemprop=name>Fixture channel</h1></div>")

        val info = ChannelInfo.getInfo(service, PROFILE)

        assertEquals("Fixture channel", info.name)
        assertTrue(info.avatars.isEmpty())
        assertTrue(info.banners.isEmpty())
        assertEquals("", info.description)
        assertEquals(ChannelExtractor.UNKNOWN_SUBSCRIBER_COUNT, info.subscriberCount)
        assertEquals(1, info.tabs.size)
        assertTrue("Host errors: ${info.errors}", info.errors.isEmpty())
    }

    @Test
    fun `model and pornstar profile aliases expose canonical identity and working Videos tabs`() {
        for ((originalKind, canonicalKind) in listOf("model" to "pornstar", "pornstar" to "model")) {
            network.pages.clear()
            network.requested.clear()
            val originalProfile = "https://www.pornhub.com/$originalKind/fixture-channel"
            val canonicalId = "$canonicalKind/fixture-channel"
            val canonicalProfile = "https://www.pornhub.com/$canonicalId"
            val canonicalVideos = "$canonicalProfile/videos"
            network.pages[originalProfile] = Fixture(profileHtml().replace(PROFILE, canonicalProfile), canonicalProfile)
            network.pages[canonicalVideos] = Fixture(videoPage(
                "<div class=profileVids><ul>${card("phaliasfirst", "First alias fixture")}</ul></div>",
                nextHref = "?page=2"
            ))
            network.pages["$canonicalVideos?page=2"] = Fixture(videoPage(
                "<div class=profileVids><ul>${card("phaliassecond", "Second alias fixture")}</ul></div>"
            ))

            val info = ChannelInfo.getInfo(service, originalProfile)

            assertEquals(canonicalId, info.id)
            assertEquals(canonicalProfile, info.url)
            assertEquals(originalProfile, info.originalUrl)
            assertTrue("Host errors: ${info.errors}", info.errors.isEmpty())
            val tab = info.tabs.single()
            assertEquals(canonicalId, tab.id)
            assertEquals(canonicalVideos, tab.url)
            assertEquals(listOf(ChannelTabs.VIDEOS), tab.contentFilters.map { it.name })
            val first = ChannelTabInfo.getInfo(service, tab)
            assertEquals(listOf("First alias fixture"), first.relatedItems.map { it.name })
            assertEquals("$canonicalVideos?page=2", first.nextPage.url)
            val second = ChannelTabInfo.getMoreItems(service, tab, first.nextPage)
            assertEquals(listOf("Second alias fixture"), second.items.map { it.name })
            assertFalse(second.hasNextPage())
            assertEquals(listOf(originalProfile, canonicalVideos, "$canonicalVideos?page=2"), network.requested)
        }
    }

    @Test
    fun `redirected Videos pages paginate using a fresh original alias handler`() {
        for ((originalKind, canonicalKind) in listOf("model" to "pornstar", "pornstar" to "model")) {
            network.pages.clear()
            network.requested.clear()
            val originalId = "$originalKind/fixture-channel"
            val originalVideos = "https://www.pornhub.com/$originalId/videos"
            val canonicalVideos = "https://www.pornhub.com/$canonicalKind/fixture-channel/videos"
            network.pages[originalVideos] = Fixture(videoPage(
                "<div class=profileVids><ul>${card("phaliasfirst", "First alias fixture")}</ul></div>",
                nextHref = "?page=2"
            ), canonicalVideos)
            network.pages["$canonicalVideos?page=2"] = Fixture(videoPage(
                "<div class=profileVids><ul>${card("phaliassecond", "Second alias fixture")}</ul></div>"
            ))
            val originalHandler = service.getChannelTabExtractorFromId(originalId, ChannelTabs.VIDEOS).linkHandler

            val first = ChannelTabInfo.getInfo(service, originalHandler)

            assertEquals(listOf("First alias fixture"), first.relatedItems.map { it.name })
            assertEquals("$canonicalVideos?page=2", first.nextPage.url)
            assertTrue("Host errors: ${first.errors}", first.errors.isEmpty())
            // The original handler is retained by the host; only Page carries the resolved alias.
            val second = ChannelTabInfo.getMoreItems(service, originalHandler, first.nextPage)
            assertEquals(listOf("Second alias fixture"), second.items.map { it.name })
            assertFalse(second.hasNextPage())
            assertEquals(listOf(originalVideos, "$canonicalVideos?page=2"), network.requested)
        }
    }

    @Test
    fun `profile aliases never allow a different account kind or slug`() {
        for (targetId in listOf("users/fixture-channel", "channels/fixture-channel", "pornstar/other-channel")) {
            network.requested.clear()
            val targetProfile = "https://www.pornhub.com/$targetId"
            network.pages[PROFILE] = Fixture(profileHtml().replace(PROFILE, targetProfile), targetProfile)
            assertThrows(targetId, ParsingException::class.java) { ChannelInfo.getInfo(service, PROFILE) }
            assertEquals(listOf(PROFILE), network.requested)
        }
        for (sourceId in listOf("users/fixture-channel", "channels/fixture-channel")) {
            network.requested.clear()
            val sourceProfile = "https://www.pornhub.com/$sourceId"
            network.pages[sourceProfile] = Fixture(profileHtml(), PROFILE)
            assertThrows(sourceId, ParsingException::class.java) { ChannelInfo.getInfo(service, sourceProfile) }
            assertEquals(listOf(sourceProfile), network.requested)
        }
        val handler = service.getChannelTabExtractorFromId(ID, ChannelTabs.VIDEOS).linkHandler
        network.requested.clear()
        for (target in listOf(
            "https://www.pornhub.com/users/fixture-channel/videos/public?page=2",
            "https://www.pornhub.com/channels/fixture-channel/videos?page=2",
            "https://www.pornhub.com/pornstar/other-channel/videos?page=2"
        )) {
            assertThrows(target, ParsingException::class.java) {
                ChannelTabInfo.getMoreItems(service, handler, Page(target))
            }
        }
        assertTrue(network.requested.isEmpty())
    }

    @Test
    fun `Videos tab collects only scoped channel uploads with native identity`() {
        network.pages[VIDEOS] = Fixture(videoPage(
            """
                <div id=mostRecentVideosSection><ul>
                  ${card("phfirst", "First fixture", "pcVideoListItem")}
                  ${card("phfirst", "Duplicate fixture")}
                  ${card("phadvert", "Advertisement", "videoblock ad")}
                  ${card("phsecond", "Second fixture", "videoBox")}
                </ul></div>
            """.trimIndent(),
            nextHref = "?page=2"
        ))
        val handler = service.getChannelTabExtractorFromId(ID, ChannelTabs.VIDEOS).linkHandler

        val info = ChannelTabInfo.getInfo(service, handler)

        assertEquals(listOf("First fixture", "Second fixture"), info.relatedItems.map { it.name })
        assertEquals(VIDEOS + "?page=2", info.nextPage.url)
        assertTrue("Host errors: ${info.errors}", info.errors.isEmpty())
        for (item in info.relatedItems.map { it as StreamInfoItem }) {
            assertEquals(43123, item.serviceId)
            assertEquals(PROFILE, item.uploaderUrl)
            assertEquals("Fixture channel", item.uploaderName)
        }
        assertEquals(listOf(VIDEOS), network.requested)
    }

    @Test
    fun `host pagination reconstructs a fresh channel tab extractor`() {
        network.pages[VIDEOS] = Fixture(videoPage(
            "<div id=mostRecentVideosSection><ul>${card("phfirst", "First fixture")}</ul></div>",
            nextHref = "?page=2"
        ))
        val secondUrl = "$VIDEOS?page=2"
        network.pages[secondUrl] = Fixture(videoPage(
            "<div id=mostRecentVideosSection><ul>${card("phsecond", "Second fixture")}</ul></div>",
            disabledNext = true
        ))
        val handler = service.getChannelTabExtractorFromId(ID, ChannelTabs.VIDEOS).linkHandler
        val first = ChannelTabInfo.getInfo(service, handler)

        // The host creates another extractor here without calling fetchPage() on it.
        val second = ChannelTabInfo.getMoreItems(service, handler, first.nextPage)

        assertEquals(listOf("Second fixture"), second.items.map { it.name })
        assertFalse(second.hasNextPage())
        assertTrue(second.errors.isEmpty())
        assertEquals(listOf(VIDEOS, secondUrl), network.requested)
    }

    @Test
    fun `known load more controls paginate through fresh extractors and stop at the last page`() {
        for (command in listOf(
            "loadMoreData('/model/fixture-channel/videos', 3)",
            "loadMoreData('/model/fixture-channel/videos/upload', '3', 0)"
        )) {
            network.requested.clear()
            for (page in 1..3) {
                val url = VIDEOS + if (page == 1) "" else "?page=$page"
                network.pages[url] = Fixture(videoPage(
                    "<div class=profileVids><ul>${card("phmore$page", "Page $page fixture")}</ul></div>" +
                        """<button id=moreDataBtn onclick="$command">More</button>"""
                ))
            }
            val handler = service.getChannelTabExtractorFromId(ID, ChannelTabs.VIDEOS).linkHandler

            val first = ChannelTabInfo.getInfo(service, handler)
            assertEquals("$VIDEOS?page=2", first.nextPage.url)
            val second = ChannelTabInfo.getMoreItems(service, handler, first.nextPage)
            assertEquals(listOf("Page 2 fixture"), second.items.map { it.name })
            assertEquals("$VIDEOS?page=3", second.nextPage.url)
            val third = ChannelTabInfo.getMoreItems(service, handler, second.nextPage)
            assertEquals(listOf("Page 3 fixture"), third.items.map { it.name })
            assertFalse(third.hasNextPage())
            assertEquals(listOf(VIDEOS, "$VIDEOS?page=2", "$VIDEOS?page=3"), network.requested)
        }
    }

    @Test
    fun `load more routes cannot change channel or query identity`() {
        for (route in listOf(
            "/model/other-channel/videos",
            "https://unrelated.example.com/model/fixture-channel/videos",
            "/model/fixture-channel/videos?o=changed"
        )) {
            network.requested.clear()
            network.pages[VIDEOS] = Fixture(videoPage(
                "<div class=profileVids><ul></ul></div>" +
                    """<button id=moreDataBtn onclick="loadMoreData('$route', 3)">More</button>"""
            ))
            val extractor = service.getChannelTabExtractorFromId(ID, ChannelTabs.VIDEOS)
            extractor.fetchPage()
            assertThrows(route, ParsingException::class.java) { extractor.initialPage }
            assertEquals(listOf(VIDEOS), network.requested)
        }
    }

    @Test
    fun `malformed load more commands never infer a continuation`() {
        for (command in listOf(
            "loadMoreData('/model/fixture-channel/videos', 'many')",
            "loadMoreData('/model/fixture-channel/videos', 3 + 99)",
            "loadMoreData('/model/fixture-channel/videos', 2147483648)",
            "loadMoreData('/model/fixture-channel/videos', -2)",
            "differentFunction('/model/fixture-channel/videos', 3)"
        )) {
            network.requested.clear()
            network.pages[VIDEOS] = Fixture(videoPage(
                "<div class=profileVids><ul></ul></div>" +
                    """<button id=moreDataBtn onclick="$command">More</button>"""
            ))
            val extractor = service.getChannelTabExtractorFromId(ID, ChannelTabs.VIDEOS)
            extractor.fetchPage()
            assertFalse(command, extractor.initialPage.hasNextPage())
            assertEquals(listOf(VIDEOS), network.requested)
        }
    }

    @Test
    fun `all supported channel kinds route to their public video lists`() {
        for (kind in listOf("model", "pornstar", "users", "channels")) {
            val id = "$kind/fixture-channel"
            val suffix = if (kind == "users") "/videos/public" else "/videos"
            val listing = "https://www.pornhub.com/$id$suffix"
            val scope = if (kind == "channels") {
                "<ul id=showAllChanelVideos>${card("phkind", "Kind fixture", "", "data-video-vkey='phkind'")}</ul>"
            } else {
                "<div class=profileVids><ul>${card("phkind", "Kind fixture", "pcVideoListItem")}</ul></div>"
            }
            network.pages[listing] = Fixture(videoPage(scope))
            val extractor = service.getChannelTabExtractorFromId(id, ChannelTabs.VIDEOS)

            assertEquals(listing, extractor.url)
            extractor.fetchPage()
            assertEquals(kind, "Kind fixture", extractor.initialPage.items.single().name)
        }
        assertEquals(
            listOf("$VIDEOS", "https://www.pornhub.com/pornstar/fixture-channel/videos",
                "https://www.pornhub.com/users/fixture-channel/videos/public",
                "https://www.pornhub.com/channels/fixture-channel/videos"),
            network.requested
        )
    }

    @Test
    fun `empty channel video container differs from an unrecognized page`() {
        network.pages[VIDEOS] = Fixture(videoPage("<div class=profileVids><ul></ul></div>"))
        val empty = service.getChannelTabExtractorFromId(ID, ChannelTabs.VIDEOS)
        empty.fetchPage()
        assertTrue(empty.initialPage.items.isEmpty())
        assertFalse(empty.initialPage.hasNextPage())

        network.pages[VIDEOS] = Fixture("<main>Unexpected fixture response</main>")
        val unknown = service.getChannelTabExtractorFromId(ID, ChannelTabs.VIDEOS)
        assertThrows(ParsingException::class.java) {
            unknown.fetchPage()
            unknown.initialPage
        }
    }

    @Test
    fun `unsupported and cross channel continuations are rejected before network access`() {
        val extractor = service.getChannelTabExtractorFromId(ID, ChannelTabs.VIDEOS)
        val rejected = listOf(
            "https://unrelated.example.com/model/fixture-channel/videos?page=2",
            "http://www.pornhub.com/model/fixture-channel/videos?page=2",
            "https://www.pornhub.com/model/other-channel/videos?page=2",
            "https://www.pornhub.com/users/fixture-channel/videos/public?page=2",
            "$PROFILE?page=2",
            "$VIDEOS?page=1",
            "$VIDEOS?page=2&page=3",
            "$VIDEOS?page=-1",
            "$VIDEOS?page=2147483648",
            "$VIDEOS?page=2&extra=fixture",
            "$VIDEOS?page=2#fragment"
        )
        for (url in rejected) {
            assertThrows(url, ParsingException::class.java) { extractor.getPage(Page(url)) }
        }
        assertTrue(network.requested.isEmpty())
    }

    @Test
    fun `HTML pagination cannot replace the selected channel`() {
        network.pages[VIDEOS] = Fixture(videoPage(
            "<div class=profileVids><ul>${card("phfirst", "First fixture")}</ul></div>",
            nextHref = "/model/other-channel/videos?page=2"
        ))
        val extractor = service.getChannelTabExtractorFromId(ID, ChannelTabs.VIDEOS)
        extractor.fetchPage()
        assertThrows(ParsingException::class.java) { extractor.initialPage }
        assertEquals(listOf(VIDEOS), network.requested)
    }

    @Test
    fun `redirected profile and video requests cannot change channel identity`() {
        network.pages[PROFILE] = Fixture(profileHtml(), "https://www.pornhub.com/model/other-channel")
        assertThrows(ParsingException::class.java) { ChannelInfo.getInfo(service, PROFILE) }

        network.pages[VIDEOS] = Fixture(
            videoPage("<div class=profileVids><ul></ul></div>"),
            "https://www.pornhub.com/model/other-channel/videos"
        )
        val extractor = service.getChannelTabExtractorFromId(ID, ChannelTabs.VIDEOS)
        assertThrows(ParsingException::class.java) {
            extractor.fetchPage()
            extractor.initialPage
        }
        assertEquals(listOf(PROFILE, VIDEOS), network.requested)
    }

    @Test
    fun `unknown profile responses and unsupported tabs fail explicitly`() {
        network.pages[PROFILE] = Fixture("<html><title>Unexpected response</title><main>Fixture</main></html>")
        assertThrows(ParsingException::class.java) { ChannelInfo.getInfo(service, PROFILE) }
        for (tab in listOf(ChannelTabs.SHORTS, ChannelTabs.PLAYLISTS, ChannelTabs.LIVESTREAMS)) {
            assertThrows(tab, ParsingException::class.java) { service.getChannelTabExtractorFromId(ID, tab) }
        }
        assertEquals(listOf(PROFILE), network.requested)
    }

    private fun profileHtml(): String = """
        <!doctype html><html><head><link rel=canonical href='$PROFILE'></head><body>
        <div id=topProfileHeader><h1 itemprop=name>Fixture channel</h1>
          <img id=getAvatar src='https://cdn.example.com/fixture-avatar.jpg'>
        </div><div itemprop=description>A neutral channel description.</div>
        </body></html>
    """.trimIndent()

    private fun videoPage(scope: String, nextHref: String? = null, disabledNext: Boolean = false): String = """
        <!doctype html><html><body>
        <nav><ul>${card("phnavigation", "Navigation fixture")}</ul></nav>
        <div class=container><h1 itemprop=name>Fixture channel</h1>
          $scope
          <aside><ul>${card("phrecommended", "Unrelated recommendation")}</ul></aside>
          ${nextHref?.let { "<ul class=pagination><li class=page_next><a href='$it'>Next</a></li></ul>" }.orEmpty()}
          ${if (disabledNext) "<li class='page_next disabled'><a href='?page=3'>Next</a></li>" else ""}
        </div></body></html>
    """.trimIndent()

    private fun card(id: String, title: String, classes: String = "videoblock", attributes: String = ""): String = """
        <li class='$classes' $attributes><a class=linkVideoThumb href='/view_video.php?viewkey=$id' title='$title'></a>
          <span class=usernameWrap><a href='/model/fixture-channel'>Fixture channel</a></span>
        </li>
    """.trimIndent()

    private data class Fixture(val html: String, val finalUrl: String? = null)

    private class ChannelDownloader : Downloader() {
        val pages = mutableMapOf<String, Fixture>()
        val requested = mutableListOf<String>()

        override fun execute(request: Request): Response {
            requested += request.url()
            val fixture = pages[request.url()] ?: throw AssertionError("Unexpected fixture request: ${request.url()}")
            return Response(200, "OK", emptyMap(), fixture.html, fixture.html.toByteArray(), fixture.finalUrl ?: request.url())
        }

        override fun executeAsync(request: Request, callback: AsyncCallback): CancellableCall =
            throw AssertionError("Channel fixtures use synchronous extraction")
    }

    companion object {
        private const val ID = "model/fixture-channel"
        private const val PROFILE = "https://www.pornhub.com/$ID"
        private const val VIDEOS = "$PROFILE/videos"
    }
}
