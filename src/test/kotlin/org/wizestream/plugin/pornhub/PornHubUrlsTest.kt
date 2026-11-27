/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ChannelTabs
import org.schabi.newpipe.extractor.plugin.PluginManifest
import org.schabi.newpipe.extractor.plugin.WizeStreamPlugin

class PornHubUrlsTest {
    private val videoId = "ph0123456789ab"

    @Test
    fun `pasted watch URLs preserve identity across supported host variants`() {
        val variants = listOf(
            "https://www.pornhub.com/view_video.php?viewkey=$videoId",
            "http://pornhub.com/view_video.php?viewkey=$videoId",
            "https://m.pornhub.com/view_video.php?viewkey=$videoId&t=12",
            "https://de.pornhub.com/view_video.php?utm_source=fixture&viewkey=$videoId",
            "https://www.pornhub.org/view_video.php?viewkey=$videoId",
            "https://fr.pornhub.org/view_video.php?viewkey=$videoId#fixture"
        )
        variants.forEach { original ->
            assertEquals(original, videoId, PornHubUrls.streamId(original))
            assertEquals(
                original,
                "https://www.pornhub.com/view_video.php?viewkey=$videoId",
                PornHubUrls.streamUrl(PornHubUrls.streamId(original))
            )
        }
    }

    @Test
    fun `embed and watch links resolve to the same stable video`() {
        listOf(
            "https://www.pornhub.com/embed/$videoId",
            "https://www.pornhub.org/embed/$videoId/"
        ).forEach { assertEquals(it, videoId, PornHubUrls.streamId(it)) }
    }

    @Test
    fun `channel handlers preserve profile identity across supported URL families`() {
        val service = PornHubService(43123)
        val cases = listOf(
            "https://www.pornhub.com/model/fixture-uploader" to "model/fixture-uploader",
            "http://m.pornhub.com/model/fixture-uploader/videos/" to "model/fixture-uploader",
            "https://fr.pornhub.org/users/fixture_user/videos/public?page=2" to "users/fixture_user",
            "https://pornhub.com/channels/fixture-studio/videos?o=mr#top" to "channels/fixture-studio",
            "https://www.pornhub.org/pornstar/fixture-person/" to "pornstar/fixture-person"
        )
        for ((url, id) in cases) {
            val handler = checkNotNull(service.channelLHFactory).fromUrl(url)
            assertEquals(url, id, handler.id)
            assertEquals(url, "https://www.pornhub.com/$id", handler.url)
            assertEquals(url, StreamingService.LinkType.CHANNEL, service.getLinkTypeByUrl(url))
            assertFalse(url, service.streamLHFactory.acceptUrl(url))
            assertEquals(handler.url, service.channelLHFactory.fromId(handler.id).url)
        }
    }

    @Test
    fun `channel video URLs distinguish public user uploads from other profile types`() {
        for (kind in listOf("model", "pornstar", "channels", "users")) {
            val id = "$kind/fixture-name"
            assertEquals("https://www.pornhub.com/$id", PornHubUrls.channelUrl(id))
            val suffix = if (kind == "users") "/videos/public" else "/videos"
            assertEquals("https://www.pornhub.com/$id$suffix", PornHubUrls.channelVideosUrl(id))
            assertEquals(id, PornHubUrls.channelId(PornHubUrls.channelVideosUrl(id)))
        }
    }

    @Test
    fun `channel tab factory retains the Videos filter for direct URL and ID entry points`() {
        val factory = checkNotNull(PornHubService(43123).channelTabLHFactory)
        for (id in listOf("model/fixture-channel", "users/fixture_user")) {
            val expectedUrl = PornHubUrls.channelVideosUrl(id)
            val handlers = listOf(
                factory.fromId(id),
                factory.fromUrl(PornHubUrls.channelUrl(id)),
                factory.fromUrl(expectedUrl)
            )
            for (handler in handlers) {
                assertEquals(id, handler.id)
                assertEquals(expectedUrl, handler.url)
                assertEquals(listOf(ChannelTabs.VIDEOS), handler.contentFilters.map { it.name })
                assertTrue(handler.sortFilter.isNullOrEmpty())
            }
        }
    }

    @Test
    fun `channel links reject foreign identities and unsupported paths`() {
        val service = PornHubService(43123)
        val rejected = listOf(
            "https://www.pornhub.com.example.com/model/fixture",
            "https://fixture@www.pornhub.com/model/fixture",
            "https://www.pornhub.com:8443/model/fixture",
            "https://arbitrary.pornhub.com/model/fixture",
            "file:///model/fixture",
            "https://www.pornhub.com/model",
            "https://www.pornhub.com/model/",
            "https://www.pornhub.com/model/fixture/extra",
            "https://www.pornhub.com/users/fixture/videos/private",
            "https://www.pornhub.com/model/../fixture",
            "https://www.pornhub.com/model/%2e%2e",
            "https://www.pornhub.com/model/fixture%2fother",
            "https://www.pornhub.com/model/fixture%5cother",
            "https://www.pornhub.com/model/fixture%00other",
            "https://www.pornhub.com/view_video.php?viewkey=$videoId",
            "https://www.pornhub.com/playlist/42"
        )
        for (url in rejected) {
            assertThrows(url, ParsingException::class.java) { PornHubUrls.channelId(url) }
            assertFalse(url, checkNotNull(service.channelLHFactory).acceptUrl(url))
        }
        for (id in listOf("", "fixture", "model/", "model/..", "model/fixture/extra", "users/fixture?x=1", "channels/fixture#fragment")) {
            assertThrows(id, ParsingException::class.java) { PornHubUrls.channelUrl(id) }
            assertThrows(id, ParsingException::class.java) { PornHubUrls.channelVideosUrl(id) }
        }
    }

    @Test
    fun `uploader hrefs resolve against the page without inventing unsupported destinations`() {
        val watch = PornHubUrls.streamUrl(videoId)
        assertEquals("https://www.pornhub.com/model/fixture", PornHubUrls.channelUrlOrNull("/model/fixture", watch))
        assertEquals("https://www.pornhub.com/channels/fixture", PornHubUrls.channelUrlOrNull("//fr.pornhub.org/channels/fixture/videos", watch))
        for (href in listOf(null, "", "#", "javascript:void(0)", "/playlist/42", "https://unrelated.example.com/model/fixture")) {
            assertNull(href, PornHubUrls.channelUrlOrNull(href, watch))
        }
    }

    @Test
    fun `stream handler rejects foreign destinations and ambiguous identities`() {
        val rejected = listOf(
            "https://pornhub.com.example.com/view_video.php?viewkey=$videoId",
            "https://notpornhub.com/view_video.php?viewkey=$videoId",
            "https://arbitrary.pornhub.com/view_video.php?viewkey=$videoId",
            "https://www.pornhub.com@localhost/view_video.php?viewkey=$videoId",
            "https://fixture@www.pornhub.com/view_video.php?viewkey=$videoId",
            "https://www.pornhub.com:8443/view_video.php?viewkey=$videoId",
            "ftp://www.pornhub.com/view_video.php?viewkey=$videoId",
            "https://www.pornhub.com/view_video.php",
            "https://www.pornhub.com/view_video.php?viewkey=",
            "https://www.pornhub.com/view_video.php?viewkey=$videoId&viewkey=ph999",
            "https://www.pornhub.com/view_video.php?viewkey=$videoId&viewkey=$videoId",
            "https://www.pornhub.com/view_video.php?viewkey=ph%2Ffixture",
            "https://www.pornhub.com/view_video.php?viewkey=ph%20fixture",
            "https://www.pornhub.com/other?viewkey=$videoId",
            "https://www.pornhub.com/embed/$videoId/extra",
            "https://www.pornhub.com/embed/../$videoId",
            "https://www.pornhub.com/view_video.php?viewkey=%",
            "https://www.pornhub.com/view_video.php?viewkey=" + "a".repeat(65)
        )
        rejected.forEach { original ->
            assertThrows(original, ParsingException::class.java) {
                PornHubUrls.streamId(original)
            }
        }
    }

    @Test
    fun `generated video links cannot inject a path or query`() {
        listOf("", "../fixture", "ph/fixture", "ph?fixture", "ph&fixture", "ph%2Ffixture", "ph fixture", "é", "a".repeat(65))
            .forEach { value ->
                assertThrows(value, ParsingException::class.java) { PornHubUrls.streamUrl(value) }
            }
        assertEquals(
            "https://www.pornhub.com/view_video.php?viewkey=abc123",
            PornHubUrls.streamUrl("abc123")
        )
    }

    @Test
    fun `search encodes the query without allowing pagination injection`() {
        val query = "fixture &page=999 + café?"
        val uri = URI(PornHubUrls.searchUrl(query, 2))
        assertEquals("https", uri.scheme)
        assertEquals("www.pornhub.com", uri.host)
        assertEquals("/video/search", uri.path)
        assertEquals(query, parameters(uri)["search"])
        assertEquals("2", parameters(uri)["page"])
        assertEquals(1, uri.rawQuery.split('&').count { it.startsWith("search=") })
        assertEquals(1, uri.rawQuery.split('&').count { it.startsWith("page=") })
        assertNull(uri.fragment)
    }

    @Test
    fun `listing pagination stays on the canonical service`() {
        val uri = URI(PornHubUrls.listingUrl(3))
        assertEquals("https", uri.scheme)
        assertEquals("www.pornhub.com", uri.host)
        assertEquals("3", parameters(uri)["page"])
        assertNull(uri.userInfo)
    }

    @Test
    fun `pagination rejects nonpositive page numbers`() {
        listOf(0, -1, Int.MIN_VALUE).forEach { page ->
            assertThrows(IllegalArgumentException::class.java) { PornHubUrls.searchUrl("fixture", page) }
            assertThrows(IllegalArgumentException::class.java) { PornHubUrls.listingUrl(page) }
        }
    }

    @Test
    fun `site requests accept only secure service owned destinations`() {
        listOf(
            "https://www.pornhub.com/video/search?search=fixture&page=2",
            "https://fr.pornhub.org/video/search?search=fixture&page=2"
        ).forEach { original ->
            val accepted = URI(PornHubUrls.siteUrl(original))
            assertEquals("https", accepted.scheme)
            assertTrue(accepted.host == "www.pornhub.com" || accepted.host == "fr.pornhub.org")
            assertEquals("fixture", parameters(accepted)["search"])
        }
        listOf(
            "http://www.pornhub.com/video",
            "https://example.com/video",
            "https://www.pornhub.com.example.com/video",
            "https://fixture@www.pornhub.com/video",
            "https://www.pornhub.com:444/video",
            "file:///tmp/fixture",
            "https://127.0.0.1/video",
            "https://localhost/video"
        ).forEach { original ->
            assertThrows(original, ParsingException::class.java) { PornHubUrls.siteUrl(original) }
        }
    }

    @Test
    fun `media URLs retain signed query parameters`() {
        val signedUrl = "https://cdn.example.com/sample/video.mp4?token=a%2Fb%2Bc&expires=12345"
        assertEquals(signedUrl, PornHubUrls.mediaUrl(signedUrl))
        assertEquals("https://cdn.example.com:443/sample/master.m3u8", PornHubUrls.mediaUrl("https://cdn.example.com:443/sample/master.m3u8"))
    }

    @Test
    fun `media metadata cannot point at local files or private network literals`() {
        val rejected = listOf(
            null,
            "",
            "http://cdn.example.com/sample.mp4",
            "file:///tmp/fixture.mp4",
            "data:video/mp4;base64,fixture",
            "javascript:fixture()",
            "https://localhost/sample.mp4",
            "https://localhost./sample.mp4",
            "https://host.localhost/sample.mp4",
            "https://host.local/sample.mp4",
            "https://host.internal/sample.mp4",
            "https://host.test/sample.mp4",
            "https://127.0.0.1/sample.mp4",
            "https://10.1.2.3/sample.mp4",
            "https://192.168.1.1/sample.mp4",
            "https://169.254.169.254/sample.mp4",
            "https://2130706433/sample.mp4",
            "https://127.1/sample.mp4",
            "https://[::1]/sample.mp4",
            "https://[::ffff:127.0.0.1]/sample.mp4",
            "https://fixture@cdn.example.com/sample.mp4",
            "https://cdn.example.com:8443/sample.mp4",
            "https://cdn.example.com/%"
        )
        rejected.forEach { original -> assertNull(original, PornHubUrls.mediaUrl(original)) }
    }

    @Test
    fun `packaged manifest advertises the implemented native entry and tested baseline`() {
        val manifestText = checkNotNull(javaClass.getResourceAsStream("/plugin.json"))
            .bufferedReader().use { it.readText() }
        val manifest = PluginManifest.parse(manifestText)
        assertEquals("wizestream", manifest.host)
        assertEquals("2.0", manifest.apiVersion)
        assertEquals("PornHub", manifest.name)
        assertTrue(PluginManifest.isValidPluginId(manifest.pluginId))
        assertTrue(manifest.minHostVersionCode >= 1052002)
        assertTrue(manifest.minAndroidSdk >= 24)
        assertTrue(manifest.requiredCapabilities.all { it == "profile-preferences" })
        val entryClass = Class.forName(manifest.entryClass)
        assertTrue(WizeStreamPlugin::class.java.isAssignableFrom(entryClass))
        assertNotNull(entryClass.getConstructor().newInstance())
    }

    private fun parameters(uri: URI): Map<String, String> = uri.rawQuery.orEmpty()
        .split('&')
        .filter(String::isNotEmpty)
        .associate { component ->
            val pair = component.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
}
