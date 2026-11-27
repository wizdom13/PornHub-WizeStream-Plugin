/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response

/** Bounds the complete host request while retaining its final URL, headers and error categories. */
internal class PornHubRequests(
    private val downloader: Downloader,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
) {
    init {
        require(timeoutMillis > 0) { "Request timeout must be positive" }
    }

    fun get(url: String, headers: Map<String, List<String>>): Response {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("PornHub request was interrupted")
        }
        val outcome = AtomicReference<Outcome?>()
        val completed = CountDownLatch(1)
        val startedAt = System.nanoTime()
        val call = downloader.getAsync(url, headers, object : Downloader.AsyncCallback {
            override fun onSuccess(response: Response) {
                complete(Outcome.Success(response))
            }

            // Explicitly implement the callback: do not rely on a desugared interface default.
            override fun onError(error: Exception) {
                complete(Outcome.Failure(error))
            }

            private fun complete(result: Outcome) {
                if (outcome.compareAndSet(null, result)) completed.countDown()
            }
        })
        try {
            val remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis) - (System.nanoTime() - startedAt)
            if (remaining <= 0 || !completed.await(remaining, TimeUnit.NANOSECONDS)) {
                // Make the outcome terminal before cancellation can deliver a late callback.
                outcome.set(Outcome.Abandoned)
                call.cancel()
                throw SocketTimeoutException("PornHub request timed out; try again later")
            }
        } catch (cause: InterruptedException) {
            outcome.set(Outcome.Abandoned)
            call.cancel()
            Thread.currentThread().interrupt()
            throw InterruptedIOException("PornHub request was interrupted").apply { initCause(cause) }
        }
        return when (val result = outcome.get()) {
            is Outcome.Success -> result.response
            is Outcome.Failure -> throw result.error
            else -> throw IOException("PornHub request completed without a response")
        }
    }

    private sealed interface Outcome {
        class Success(val response: Response) : Outcome
        class Failure(val error: Exception) : Outcome
        data object Abandoned : Outcome
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
    }
}
