/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.search.SearchInfo

/** No provider traffic: even the real OkHttp cancellation handles are never executed. */
class PornHubRequestsTest {
    private val url = PornHubUrls.searchUrl("private fixture query")

    @Test(timeout = 5_000)
    fun `a stalled host callback times out and cancels the real transport without retrying`() {
        val downloader = ControlledDownloader()
        val error = assertThrows(SocketTimeoutException::class.java) {
            PornHubRequests(downloader, 20).get(url, emptyMap())
        }

        assertEquals(1, downloader.requests.size)
        assertTrue(downloader.handles.single().isCancelled)
        assertTrue(downloader.calls.single().isCanceled())
        assertFalse(error.message.orEmpty().contains("private"))
        assertFalse(error.message.orEmpty().contains(url))
    }

    @Test(timeout = 5_000)
    fun `the same deadline covers HTML and media metadata requests`() {
        for (metadata in listOf(false, true)) {
            val downloader = ControlledDownloader()
            val client = PornHubClient(downloader, 20)
            assertThrows(SocketTimeoutException::class.java) {
                if (metadata) {
                    client.getJsonArray(PornHubUrls.BASE_URL + "/video/get_media?s=fixture", url)
                } else {
                    client.getPage(url)
                }
            }
            assertEquals(1, downloader.requests.size)
            assertTrue(downloader.calls.single().isCanceled())
        }
    }

    @Test(timeout = 5_000)
    fun `successful asynchronous responses retain their final URL headers and body`() {
        val finalUrl = "https://fr.pornhub.org/video/search?search=fixture&page=1"
        val expected = response("fixture body", finalUrl)
        val started = CountDownLatch(1)
        val callbackReady = AtomicReference<Downloader.AsyncCallback>()
        val handleReady = AtomicReference<CancellableCall>()
        val callbackFailure = AtomicReference<Throwable?>()
        val downloader = ControlledDownloader { _, callback, handle ->
            callbackReady.set(callback)
            handleReady.set(handle)
            started.countDown()
        }
        val headers = mapOf("Referer" to listOf(PornHubUrls.BASE_URL + "/"))
        val callbackWorker = Thread {
            try {
                assertTrue("Request must start", started.await(2, TimeUnit.SECONDS))
                callbackReady.get().onSuccess(expected)
                handleReady.get().setFinished()
            } catch (error: Throwable) {
                callbackFailure.set(error)
            }
        }
        callbackWorker.start()
        try {
            val actual = PornHubRequests(downloader).get(url, headers)
            callbackWorker.join(2_000)
            assertFalse("Callback must finish", callbackWorker.isAlive)
            callbackFailure.get()?.let { throw AssertionError("Fixture callback failed", it) }
            assertSame(expected, actual)
            assertEquals(finalUrl, actual.latestUrl())
            assertEquals("text/html; charset=utf-8", actual.getHeader("Content-Type"))
            assertEquals("fixture body", actual.responseBody())
            assertEquals(headers["Referer"], downloader.requests.single().headers()["Referer"])
            assertFalse(downloader.calls.single().isCanceled())
        } finally {
            callbackWorker.interrupt()
            callbackWorker.join(2_000)
        }
    }

    @Test(timeout = 5_000)
    fun `host callback failures preserve their exception identity and category`() {
        val failures = listOf(
            IOException("Fixture transport failure"),
            ReCaptchaException("Fixture verification required", url),
            ContentNotAvailableException("Fixture unavailable")
        )
        for (failure in failures) {
            val downloader = ControlledDownloader { _, callback, handle ->
                callback.onError(failure)
                handle.setFinished()
            }
            val actual = assertThrows(failure.javaClass) {
                PornHubRequests(downloader).get(url, emptyMap())
            }
            assertSame(failure, actual)
            assertEquals(1, downloader.requests.size)
            assertFalse(downloader.calls.single().isCanceled())
        }
    }

    @Test(timeout = 5_000)
    fun `late callbacks after timeout cannot complete a later request`() {
        val downloader = ControlledDownloader()
        val requests = PornHubRequests(downloader, 300)
        assertThrows(SocketTimeoutException::class.java) { requests.get(url, emptyMap()) }
        val lateCallback = downloader.callbacks.single()
        val expected = response("second request", url)
        downloader.onStart = { _, callback, handle ->
            lateCallback.onSuccess(response("obsolete request", url))
            lateCallback.onError(IOException("Obsolete cancellation callback"))
            callback.onSuccess(expected)
            handle.setFinished()
        }

        assertSame(expected, requests.get(url, emptyMap()))
        assertEquals(2, downloader.requests.size)
        assertTrue(downloader.calls[0].isCanceled())
        assertFalse(downloader.calls[1].isCanceled())
    }

    @Test(timeout = 5_000)
    fun `the first callback is terminal even if another callback follows`() {
        val expected = response("completed request", url)
        val success = ControlledDownloader { _, callback, handle ->
            callback.onSuccess(expected)
            callback.onError(IOException("Late failure"))
            handle.setFinished()
        }
        assertSame(expected, PornHubRequests(success).get(url, emptyMap()))

        val failure = IOException("Original failure")
        val failed = ControlledDownloader { _, callback, handle ->
            callback.onError(failure)
            callback.onSuccess(expected)
            handle.setFinished()
        }
        assertSame(failure, assertThrows(IOException::class.java) {
            PornHubRequests(failed).get(url, emptyMap())
        })
    }

    @Test(timeout = 5_000)
    fun `interrupting a waiting extraction cancels the call and preserves interruption`() {
        val started = CountDownLatch(1)
        val downloader = ControlledDownloader { _, _, _ -> started.countDown() }
        val failure = AtomicReference<Throwable?>()
        val interrupted = AtomicBoolean()
        val worker = Thread {
            try {
                PornHubRequests(downloader, 10_000).get(url, emptyMap())
            } catch (error: Throwable) {
                failure.set(error)
                interrupted.set(Thread.currentThread().isInterrupted)
            }
        }
        worker.start()
        try {
            assertTrue("Request must start", started.await(2, TimeUnit.SECONDS))
            worker.interrupt()
            worker.join(2_000)
            assertFalse("Interrupted extraction must stop", worker.isAlive)
            assertTrue(failure.get() is InterruptedIOException)
            assertTrue(interrupted.get())
            assertTrue(downloader.calls.single().isCanceled())
            assertEquals(1, downloader.requests.size)
        } finally {
            worker.interrupt()
            worker.join(2_000)
        }
    }

    @Test(timeout = 5_000)
    fun `an already interrupted caller does not launch a request`() {
        val downloader = ControlledDownloader()
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) {
                PornHubRequests(downloader).get(url, emptyMap())
            }
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(downloader.requests.isEmpty())
        } finally {
            Thread.interrupted()
        }
    }

    @Test(timeout = 5_000)
    fun `request setup failures propagate without a synchronous fallback`() {
        val expected = IOException("Fixture dispatch failure")
        val downloader = object : Downloader() {
            override fun execute(request: Request): Response = error("No synchronous fallback is allowed")
            override fun executeAsync(request: Request, callback: AsyncCallback): CancellableCall = throw expected
        }
        assertSame(expected, assertThrows(IOException::class.java) {
            PornHubRequests(downloader).get(url, emptyMap())
        })
    }

    @Test(timeout = 5_000)
    fun `search and Videos both complete through the asynchronous host contract`() {
        val service = PornHubService(43123)
        val searchUrl = PornHubUrls.searchUrl("Fixture")
        val videosUrl = PornHubUrls.listingUrl()
        val downloader = ControlledDownloader { request, callback, handle ->
            val (container, finalUrl) = when (request.url()) {
                searchUrl -> "videoSearchResult" to PornHubUrls.searchUrl("fixture")
                videosUrl -> "videoCategory" to videosUrl.replace("www.pornhub.com", "fr.pornhub.org")
                else -> error("Unexpected fixture request")
            }
            callback.onSuccess(response(card(container), finalUrl))
            handle.setFinished()
        }
        NewPipe.init(downloader)

        val search = SearchInfo.getInfo(service, service.searchQHFactory.fromQuery("Fixture"))
        val kiosk = KioskInfo.getInfo(service, videosUrl)

        assertEquals(1, search.relatedItems.size)
        assertEquals(1, kiosk.relatedItems.size)
        assertEquals(PornHubUrls.streamUrl("phfixture"), search.relatedItems.single().url)
        assertEquals(PornHubUrls.streamUrl("phfixture"), kiosk.relatedItems.single().url)
        assertTrue(search.errors.isEmpty())
        assertTrue(kiosk.errors.isEmpty())
        assertEquals(listOf(searchUrl, videosUrl), downloader.requests.map { it.url() })
        assertTrue(downloader.calls.none { it.isCanceled() })
    }

    private fun card(container: String): String =
        "<ul id='$container'><li class='videoblock'><a class='linkVideoThumb' " +
            "href='/view_video.php?viewkey=phfixture' title='Fixture video'></a></li></ul>"

    private fun response(body: String, finalUrl: String): Response = Response(
        200, "OK", mapOf("Content-Type" to listOf("text/html; charset=utf-8")),
        body, body.toByteArray(), finalUrl
    )

    private class ControlledDownloader(
        var onStart: (Request, AsyncCallback, CancellableCall) -> Unit = { _, _, _ -> }
    ) : Downloader() {
        val requests = mutableListOf<Request>()
        val calls = mutableListOf<Call>()
        val handles = mutableListOf<CancellableCall>()
        val callbacks = mutableListOf<AsyncCallback>()

        override fun execute(request: Request): Response = error("No synchronous fallback is allowed")

        override fun executeAsync(request: Request, callback: AsyncCallback): CancellableCall {
            requests += request
            val call = fixtureHttpCall()
            val handle = CancellableCall(call)
            calls += call
            handles += handle
            callbacks += callback
            onStart(request, callback, handle)
            return handle
        }
    }
}
