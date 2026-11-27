/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/** Synthetic HTTP responses only: these tests never contact the provider. */
class PornHubClientTest {
    private val watchUrl = PornHubUrls.streamUrl("ph0123456789ab")
    private val metadataUrl = PornHubUrls.BASE_URL + "/video/get_media?s=fixture%2Fsignature"

    @Test
    fun `page requests use the host downloader and explicit public request headers`() {
        val downloader = RecordingDownloader { response("<html><title>Fixture page</title></html>", latestUrl = "") }
        val document = PornHubClient(downloader).getPage("$watchUrl#t=12")
        assertEquals("Fixture page", document.title())
        assertEquals(watchUrl, document.location())
        val request = downloader.requests.single()
        assertEquals(watchUrl, request.url())
        assertEquals("GET", request.httpMethod())
        assertEquals(listOf(PornHubUrls.BASE_URL + "/"), request.headers()["Referer"])
        assertEquals(listOf(PornHubUrls.BASE_URL), request.headers()["Origin"])
        assertEquals(listOf("en-US,en;q=0.8"), request.headers()["Accept-Language"])
        assertNull(request.dataToSend())
        assertNoCredentials(request)
    }

    @Test
    fun `media metadata accepts only the known JSON array endpoints`() {
        listOf("/video/get_media", "/video/get_media.php").forEach { path ->
            val url = PornHubUrls.BASE_URL + path + "?s=a%2Fb%2Bc"
            val downloader = RecordingDownloader { response("[{\"quality\":\"720\",\"videoUrl\":\"https://cdn.example.com/fixture.mp4\"}]", latestUrl = url) }
            val array = PornHubClient(downloader).getJsonArray(url, watchUrl)
            assertEquals(1, array.size)
            assertEquals("720", array.getObject(0).getString("quality"))
            val request = downloader.requests.single()
            assertEquals(url, request.url())
            assertEquals(listOf(watchUrl), request.headers()["Referer"])
            assertEquals(listOf(PornHubUrls.BASE_URL), request.headers()["Origin"])
            assertNoCredentials(request)
        }
    }

    @Test
    fun `untrusted requests and unsupported metadata paths are rejected before networking`() {
        val downloader = RecordingDownloader { error("Invalid URLs must not reach the downloader") }
        val client = PornHubClient(downloader)
        listOf(
            "https://example.com/video/get_media",
            "https://www.pornhub.com.example.com/video/get_media",
            "http://www.pornhub.com/video/get_media",
            "https://fixture@www.pornhub.com/video/get_media",
            "file:///video/get_media",
            PornHubUrls.BASE_URL + "/video/get_media/extra",
            PornHubUrls.BASE_URL + "/get_media",
            PornHubUrls.BASE_URL + "/fixture.mp4",
            PornHubUrls.BASE_URL + "/fixture.m3u8"
        ).forEach { url ->
            assertThrows(url, ParsingException::class.java) { client.getJsonArray(url, watchUrl) }
        }
        assertThrows(ParsingException::class.java) { client.getJsonArray(metadataUrl, "https://example.com/watch") }
        assertThrows(ParsingException::class.java) { client.getPage("https://example.com/watch") }
        assertTrue(downloader.requests.isEmpty())
    }

    @Test
    fun `response cookies are never replayed or used to manufacture consent`() {
        val downloader = RecordingDownloader { request ->
            if (request.url() == watchUrl) {
                response("<html><title>Fixture</title></html>", headers = mapOf("Set-Cookie" to listOf("fixture_session=unused")))
            } else {
                response("[]", latestUrl = metadataUrl)
            }
        }
        val client = PornHubClient(downloader)
        client.getPage(watchUrl)
        assertTrue(client.getJsonArray(metadataUrl, watchUrl).isEmpty())
        assertEquals(2, downloader.requests.size)
        downloader.requests.forEach(::assertNoCredentials)
    }

    @Test
    fun `denied and unavailable HTTP statuses retain their useful failure categories`() {
        val cases = listOf(
            401 to PrivateContentException::class.java,
            403 to ContentNotAvailableException::class.java,
            404 to ContentNotAvailableException::class.java,
            410 to ContentNotAvailableException::class.java,
            429 to ContentNotAvailableException::class.java,
            451 to GeographicRestrictionException::class.java,
            500 to IOException::class.java,
            502 to IOException::class.java
        )
        cases.forEach { (status, expected) ->
            val pageDownloader = RecordingDownloader { response("<html><body>Request failed</body></html>", status) }
            assertThrows("page status $status", expected) { PornHubClient(pageDownloader).getPage(watchUrl) }
            assertEquals(1, pageDownloader.requests.size)
            val jsonDownloader = RecordingDownloader { response("[]", status, metadataUrl) }
            assertThrows("metadata status $status", expected) { PornHubClient(jsonDownloader).getJsonArray(metadataUrl, watchUrl) }
            assertEquals(1, jsonDownloader.requests.size)
        }
    }

    @Test
    fun `allowed page redirects use the final URL as the document base`() {
        val finalUrl = "https://fr.pornhub.org/view_video.php?viewkey=ph0123456789ab"
        val downloader = RecordingDownloader { response("<a href='/video'>Browse</a>", latestUrl = finalUrl) }
        val document = PornHubClient(downloader).getPage(watchUrl)
        assertEquals(finalUrl, document.location())
        assertEquals("https://fr.pornhub.org/video", document.selectFirst("a")!!.absUrl("href"))
        assertEquals(1, downloader.requests.size)
    }

    @Test
    fun `foreign redirect responses are rejected without following another request`() {
        val downloader = RecordingDownloader { response("[]", latestUrl = "https://example.com/video/get_media") }
        val client = PornHubClient(downloader)
        assertThrows(ParsingException::class.java) { client.getPage(watchUrl) }
        assertThrows(ParsingException::class.java) { client.getJsonArray(metadataUrl, watchUrl) }
        assertEquals(2, downloader.requests.size)
        assertTrue(downloader.requests.all { it.url().startsWith(PornHubUrls.BASE_URL + "/") })
    }

    @Test
    fun `metadata redirects cannot silently change the requested endpoint`() {
        val downloader = RecordingDownloader { response("[]", latestUrl = PornHubUrls.BASE_URL + "/login") }
        assertThrows(ContentNotAvailableException::class.java) {
            PornHubClient(downloader).getJsonArray(metadataUrl, watchUrl)
        }
        assertEquals(1, downloader.requests.size)
    }

    @Test
    fun `visible age gates and age verification redirects stop extraction`() {
        listOf(
            "<div id='age-verification'>Verify age</div>",
            "<div id='ageVerification'>Verify age</div>",
            "<form action='/age-verification'>Verify age</form>",
            "<main>Age verification required</main>"
        ).forEach { body ->
            assertPageFailure(AgeRestrictedContentException::class.java, body)
        }
        listOf("/age-verification", "/age_verification").forEach { path ->
            assertPageFailure(AgeRestrictedContentException::class.java, "<main>Continue</main>", PornHubUrls.BASE_URL + path)
        }
    }

    @Test
    fun `login pages and locked players stop extraction`() {
        assertPageFailure(PrivateContentException::class.java, "<div id='lockedPlayer'>Private</div>")
        listOf("/login", "/login/continue").forEach { path ->
            assertPageFailure(PrivateContentException::class.java, "<form>Sign in</form>", PornHubUrls.BASE_URL + path)
        }
    }

    @Test
    fun `visible region barriers and removal messages stop extraction`() {
        listOf(
            "<div class='geoBlocked'>Restricted</div>",
            "<div id='geoBlocked'>Restricted</div>",
            "<main>Not available in your country</main>",
            "<main>Not available in your region</main>"
        ).forEach { assertPageFailure(GeographicRestrictionException::class.java, it) }
        listOf(
            "<div class='removed'>Removed</div>",
            "<section class='noVideo'>Unavailable</section>",
            "<div class='userMessageSection'>This item is unavailable</div>"
        ).forEach { assertPageFailure(ContentNotAvailableException::class.java, it) }
    }

    @Test
    fun `browser challenges are reported without executing script or retrying`() {
        listOf(
            "<form id='challenge-form'></form>",
            "<div id='cf-challenge-running'></div>",
            "<div class='g-recaptcha'></div>",
            "<body onload='go()'></body>",
            "<script>var key = 'RNKEY'; document.location.reload(true);</script>"
        ).forEach { body ->
            val downloader = RecordingDownloader { response(body) }
            val exception = assertThrows(ReCaptchaException::class.java) { PornHubClient(downloader).getPage(watchUrl) }
            assertEquals(watchUrl, exception.url)
            assertEquals(1, downloader.requests.size)
            assertNoCredentials(downloader.requests.single())
        }
    }

    @Test
    fun `hidden barrier templates do not block ordinary page content`() {
        listOf(
            "<div class='geoBlocked' hidden>Not available in your country</div>",
            "<div hidden><div class='geoBlocked'>Not available in your region</div></div>",
            "<div style='display: none'><div id='ageVerification'>Age verification required</div></div>",
            "<div class='hidden'><div id='lockedPlayer'>Private</div></div>",
            "<div hidden><section class='noVideo'>Unavailable</section></div>",
            "<div hidden><div class='userMessageSection'>This item is unavailable</div></div>",
            "<div hidden><div class='g-recaptcha'></div></div>"
        ).forEach { template ->
            val body = "<html><body><main id='fixture'>Ordinary page content</main>$template</body></html>"
            val downloader = RecordingDownloader { response(body) }
            val document = PornHubClient(downloader).getPage(watchUrl)
            assertNotNull(template, document.getElementById("fixture"))
        }
    }

    @Test
    fun `malformed or non-array metadata is an extraction error`() {
        listOf("{\"sources\": []}", "<html><body>Unexpected page</body></html>", "null", "[", "[] trailing-data")
            .forEach { body ->
                val downloader = RecordingDownloader { response(body, latestUrl = metadataUrl) }
                assertThrows(body, ParsingException::class.java) { PornHubClient(downloader).getJsonArray(metadataUrl, watchUrl) }
                assertEquals(1, downloader.requests.size)
            }
    }

    @Test
    fun `oversized responses are rejected before HTML or JSON parsing`() {
        val oversized = " ".repeat(8 * 1024 * 1024 + 1)
        val downloader = RecordingDownloader { request -> response(oversized, latestUrl = request.url()) }
        val client = PornHubClient(downloader)
        assertThrows(ParsingException::class.java) { client.getPage(watchUrl) }
        assertThrows(ParsingException::class.java) { client.getJsonArray(metadataUrl, watchUrl) }
        assertEquals(2, downloader.requests.size)
    }

    @Test
    fun publicContentAndOrdinaryCaptchaWidgetsAreNotChallengePages() {
        val body = """<ul id="videoSearchResult">
            <li>Not available in your country; age verification required — fixture title</li>
            </ul><div class="g-recaptcha"></div>"""
        val downloader = RecordingDownloader { response(body) }
        assertNotNull(PornHubClient(downloader).getPage(watchUrl).getElementById("videoSearchResult"))
        assertPageFailure(AgeRestrictedContentException::class.java, body + "<div id='ageVerification'>Verify</div>")
    }

    private fun assertPageFailure(type: Class<out Throwable>, body: String, latestUrl: String = watchUrl) {
        val downloader = RecordingDownloader { response(body, latestUrl = latestUrl) }
        assertThrows(body, type) { PornHubClient(downloader).getPage(watchUrl) }
        assertEquals(1, downloader.requests.size)
    }

    private fun assertNoCredentials(request: Request) {
        assertFalse(request.headers().keys.any { it.equals("Cookie", ignoreCase = true) })
        assertFalse(request.headers().keys.any { it.equals("Authorization", ignoreCase = true) })
    }

    private fun response(
        body: String,
        status: Int = 200,
        latestUrl: String = watchUrl,
        headers: Map<String, List<String>> = emptyMap()
    ): Response = Response(status, "Fixture response", headers, body, body.toByteArray(), latestUrl)

    private class RecordingDownloader(private val responder: (Request) -> Response) : Downloader() {
        val requests = mutableListOf<Request>()

        override fun execute(request: Request): Response {
            requests += request
            return responder(request)
        }

        override fun executeAsync(request: Request, callback: AsyncCallback): CancellableCall =
            completedFixtureCall(callback) { execute(request) }
    }
}
